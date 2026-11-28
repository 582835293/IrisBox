package com.irisbox.audio;
import android.content.Context;
import android.media.*;
import android.net.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;

/**
 * 虹膜空间音频桥接（AudioBridge）：
 * 1. Guest 侧捕获 RemoteSubmix PCM (48kHz/立体声/16bit) 经私有 Unix Socket 送至宿主机；
 * 2. 宿主机侧使用独立读取线程 + 有界缓冲队列解耦 AudioTrack 播放，避免热循环磁盘 I/O 卡顿；
 * 3. 后台每 1500ms 异步更新一次状态文件，每 300ms 热加载实例音频配置（音量、静音、开关）。
 */
public final class AudioBridge {
    private static final String AUDIO_CONF_DIR = "/data/irisbox/runtime/config/audio_v1";

    static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{return target.getClass().getMethod(name,types).invoke(target,args);}
    static Context context()throws Exception{
        Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Object thread=at.getMethod("systemMain").invoke(null);
        return (Context)at.getMethod("getSystemContext").invoke(thread);
    }
    static AudioFormat format(){return new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();}
    static void stats(String path,long bytes,long nonzero,int peak,long head)throws Exception{
        File temp=new File(path+".tmp");
        try(FileWriter f=new FileWriter(temp)){f.write("bytes="+bytes+" nonzero="+nonzero+" peak="+peak+" head="+head+"\n");}
        if(!temp.renameTo(new File(path)))throw new IOException("status commit");
    }
    static String readToken(String path)throws Exception{
        try(FileInputStream in=new FileInputStream(path)){byte[] bytes=new byte[256];int n=in.read(bytes);if(n<1||n>=256)throw new IOException("token size");String token=new String(bytes,0,n,StandardCharsets.US_ASCII).trim();if(!token.matches("[a-f0-9]{64}"))throw new IOException("token format");return token;}
    }
    static String expected;
    static boolean alive(){return alive(expected);}
    static boolean alive(String identity){
        try{
            String[] parts=identity.split(":");String text;
            try(BufferedReader reader=new BufferedReader(new FileReader("/proc/"+parts[0]+"/stat"))){text=reader.readLine();}
            String[] values=text.substring(text.lastIndexOf(')')+2).split(" +");
            return !values[0].equals("Z")&&!values[0].equals("X")&&values[19].equals(parts[1]);
        }catch(Exception failure){return false;}
    }
    static String selfIdentity()throws Exception{
        String pid=Integer.toString(android.os.Process.myPid());
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/"+pid+"/stat"))){String text=reader.readLine();return pid+":"+text.substring(text.lastIndexOf(')')+2).split(" +")[19];}
    }
    public static void main(String[] args){
        int exitCode=0;
        try{run(args);}catch(Throwable failure){failure.printStackTrace();exitCode=4;}
        // ActivityThread 会创建非守护 Binder 线程；需在清理音频资源后显式退出。
        System.exit(exitCode);
    }
    static void run(String[] args)throws Exception{
        System.err.println("IRISBOX_AUDIO_START "+(args.length>0?args[0]:"invalid"));
        if(args.length!=6)throw new IllegalArgumentException("guest|host socket tokenFile stats seconds session");
        String mode=args[0],socket=args[1],status=args[3];int seconds=Integer.parseInt(args[4]);expected=args[5];
        if(seconds<0||seconds>86400||!expected.matches("[1-9][0-9]*:[1-9][0-9]*"))throw new IllegalArgumentException();
        Context ctx=context();
        System.err.println("IRISBOX_AUDIO_CONTEXT_READY");
        try{if(mode.equals("guest"))capture(ctx,socket,readToken(args[2]),status,seconds);else if(mode.equals("host-mapped"))play(Integer.parseInt(socket),status,seconds);else throw new IllegalArgumentException();}catch(IOException disconnected){System.err.println("IRISBOX_AUDIO_DISCONNECTED "+disconnected.getClass().getSimpleName());}
    }

    /** 读取实时音量、静音及输出开关配置（仅在文件修改时间戳变化时读取，避免热循环磁盘 I/O） */
    static final class AudioControl {
        final File confFile;
        long lastCheckMs = 0L;
        long lastMtime = -1L;
        boolean outputEnabled = true;
        boolean muted = false;
        int volume = 100;
        float appliedGain = -1.0f;

        AudioControl(int id) {
            this.confFile = new File(AUDIO_CONF_DIR, id + ".conf");
        }

        void applyIfNeeded(AudioTrack track, long nowMs) {
            if (nowMs - lastCheckMs < 300L && appliedGain >= 0.0f) return;
            lastCheckMs = nowMs;
            try {
                if (confFile.isFile()) {
                    long mtime = confFile.lastModified();
                    if (mtime != lastMtime) {
                        lastMtime = mtime;
                        boolean en = true, m = false;
                        int vol = 100;
                        try (BufferedReader br = new BufferedReader(new FileReader(confFile))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                int eq = line.indexOf('=');
                                if (eq <= 0) continue;
                                String k = line.substring(0, eq).trim();
                                String v = line.substring(eq + 1).trim();
                                if ("output_enabled".equals(k)) en = !"0".equals(v) && !"false".equalsIgnoreCase(v);
                                else if ("muted".equals(k)) m = "1".equals(v) || "true".equalsIgnoreCase(v);
                                else if ("volume".equals(k)) {
                                    try { vol = Math.max(0, Math.min(100, Integer.parseInt(v))); } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                        outputEnabled = en;
                        muted = m;
                        volume = vol;
                    }
                }
            } catch (Throwable ignored) {}
            float targetGain = (!outputEnabled || muted) ? 0.0f : (volume / 100.0f);
            if (Math.abs(targetGain - appliedGain) > 0.001f) {
                track.setVolume(targetGain);
                appliedGain = targetGain;
            }
        }
    }

    static Thread startStatsReporter(final String statusPath, final AtomicBoolean running,
                                       final AtomicLong bytesRef, final AtomicLong nzRef,
                                       final AtomicInteger peakRef, final AtomicLong headRef) {
        Thread t = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException ignored) {
                    if (!running.get()) break;
                }
                if (!running.get()) break;
                try {
                    stats(statusPath, bytesRef.get(), nzRef.get(), peakRef.get(), headRef.get());
                } catch (Throwable ignored) {}
            }
        }, "IrisAudioStats");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void capture(Context ctx,String path,String token,String status,int seconds)throws Exception{
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        Class<?> ruleClass=Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object rules=Class.forName("android.media.audiopolicy.AudioMixingRule$Builder").getConstructor().newInstance();
        for(int usage:new int[]{0,1,4,5,6,7,8,9,10,11,12,13,14,16}){
            call(rules,"addRule",new Class<?>[]{AudioAttributes.class,int.class},new AudioAttributes.Builder().setUsage(usage).build(),1);
        }
        Object rule=call(rules,"build",new Class<?>[]{});
        Class<?> mixClass=Class.forName("android.media.audiopolicy.AudioMix");
        Object mb=Class.forName("android.media.audiopolicy.AudioMix$Builder").getConstructor(ruleClass).newInstance(rule);
        call(mb,"setFormat",new Class<?>[]{AudioFormat.class},format());
        call(mb,"setRouteFlags",new Class<?>[]{int.class},2);
        Object mix=call(mb,"build",new Class<?>[]{});
        Object pb=Class.forName("android.media.audiopolicy.AudioPolicy$Builder").getConstructor(Context.class).newInstance(ctx);
        call(pb,"addMix",new Class<?>[]{mixClass},mix);
        Object policy=call(pb,"build",new Class<?>[]{});
        Class<?> policyClass=Class.forName("android.media.audiopolicy.AudioPolicy");
        AudioManager manager=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        int result=(Integer)manager.getClass().getMethod("registerAudioPolicy",policyClass).invoke(manager,policy);
        System.err.println("IRISBOX_AUDIO_POLICY "+result);
        if(result!=0)throw new IllegalStateException("policy="+result);
        AudioRecord record=null;
        LocalSocket bind=new LocalSocket();
        boolean bound=false;
        try{
            record=(AudioRecord)call(policy,"createAudioRecordSink",new Class<?>[]{mixClass},mix);
            if(record==null||record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("record initialization");
            File socketFile=new File(path),identityFile=new File(socketFile.getParentFile(),"capture.identity");
            if(socketFile.exists()){
                if(identityFile.isFile())try(BufferedReader reader=new BufferedReader(new FileReader(identityFile))){if(alive(reader.readLine()))throw new IOException("capture already active");}
                if(!android.system.OsConstants.S_ISSOCK(Os.lstat(path).st_mode))throw new IOException("unexpected socket path");
                if(!socketFile.delete())throw new IOException("stale socket cleanup");
            }
            bind.bind(new LocalSocketAddress(path,LocalSocketAddress.Namespace.FILESYSTEM));
            bound=true;
            try(FileWriter writer=new FileWriter(identityFile)){writer.write(selfIdentity()+"\n");}
            Os.chmod(path,0600);
            try(LocalServerSocket server=new LocalServerSocket(bind.getFileDescriptor());LocalSocket client=server.accept()){
                client.setSoTimeout(5000);
                if(client.getPeerCredentials().getUid()!=0)throw new SecurityException("peer");
                DataInputStream input=new DataInputStream(client.getInputStream());
                if(!token.equals(input.readUTF()))throw new SecurityException("token");
                DataOutputStream out=new DataOutputStream(client.getOutputStream());
                out.writeInt(0x52564131);out.writeInt(48000);out.writeInt(2);out.flush();
                record.startRecording();
                final AtomicBoolean running=new AtomicBoolean(true);
                final AtomicLong bytesRef=new AtomicLong(0),nzRef=new AtomicLong(0),headRef=new AtomicLong(-1);
                final AtomicInteger peakRef=new AtomicInteger(0);
                Thread reporter=startStatsReporter(status,running,bytesRef,nzRef,peakRef,headRef);
                try {
                    long end=SystemClock.elapsedRealtime()+seconds*1000L;
                    byte[] buffer=new byte[3840];
                    while(seconds==0||SystemClock.elapsedRealtime()<end){
                        int count=record.read(buffer,0,buffer.length);if(count<0)throw new IOException("read="+count);
                        long frameNz=0;int framePeak=peakRef.get();
                        for(int i=0;i+1<count;i+=2){int s=(short)((buffer[i]&255)|(buffer[i+1]<<8));if(s!=0)frameNz++;int a=s<0?-s:s;if(a>framePeak)framePeak=a;}
                        out.write(buffer,0,count);
                        bytesRef.addAndGet(count);
                        if(frameNz>0)nzRef.addAndGet(frameNz);
                        if(framePeak>peakRef.get())peakRef.set(framePeak);
                    }
                    try{stats(status,bytesRef.get(),nzRef.get(),peakRef.get(),-1);}catch(Throwable ignored){}
                } finally {
                    running.set(false);
                    reporter.interrupt();
                }
            }
        }finally{
            if(record!=null){try{record.stop();}catch(RuntimeException ignored){}record.release();}
            if(bound)try{new File(path).delete();}catch(RuntimeException ignored){}
            try{bind.close();}catch(IOException ignored){}
            try{manager.getClass().getMethod("unregisterAudioPolicyAsync",policyClass).invoke(manager,policy);}catch(Exception ignored){}
        }
    }
    static InputStream mappedAudio(int id)throws Exception{
        if(id<1007||id>1134||Os.getuid()!=0)throw new SecurityException("Host audio identity");
        String name="irisbox_audio_bridge_"+UUID.randomUUID().toString().replace("-","");
        String[] identity=expected.split(":");
        LocalServerSocket server=new LocalServerSocket(name);
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        java.lang.Process helper=null;FileDescriptor received=null;
        timer.schedule(()->{try{server.close();}catch(IOException ignored){}},6,TimeUnit.SECONDS);
        try{
            helper=new ProcessBuilder("/data/irisbox/runtime/irisbox_userns","audio-bridge",Integer.toString(id),identity[0],identity[1],name)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try(LocalSocket connection=server.accept()){
                connection.setSoTimeout(3000);
                if(connection.getPeerCredentials().getUid()!=0)throw new SecurityException("Audio connector");
                int marker=connection.getInputStream().read();FileDescriptor[] fds=connection.getAncillaryFileDescriptors();
                if(marker!='A'||fds==null||fds.length!=1){if(fds!=null)for(FileDescriptor fd:fds)Os.close(fd);throw new IOException("Audio FD");}
                received=fds[0];
                if(!helper.waitFor(3,TimeUnit.SECONDS)||helper.exitValue()!=0)throw new IOException("Audio connector failed");
                Os.setsockoptTimeval(received,android.system.OsConstants.SOL_SOCKET,android.system.OsConstants.SO_RCVTIMEO,android.system.StructTimeval.fromMillis(3000));
                InputStream input=new FileInputStream(received);received=null;return input;
            }
        }finally{if(received!=null)Os.close(received);if(helper!=null&&helper.isAlive())helper.destroyForcibly();server.close();timer.shutdownNow();}
    }
    static void play(int id,String status,int seconds)throws Exception{
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) {}
        try(DataInputStream in=new DataInputStream(mappedAudio(id))){
            if(in.readInt()!=0x52564131||in.readInt()!=48000||in.readInt()!=2)throw new IOException("format");
            int min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            int bufSize=Math.max(min*2,49152);
            AudioTrack track=new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(format())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("track initialization");
            final AtomicBoolean running=new AtomicBoolean(true);
            final AtomicLong bytesRef=new AtomicLong(0),nzRef=new AtomicLong(0),headRef=new AtomicLong(0);
            final AtomicInteger peakRef=new AtomicInteger(0);
            final AudioControl control=new AudioControl(id);
            final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(64);
            final Throwable[] readError=new Throwable[1];
            final AtomicBoolean eof=new AtomicBoolean(false);
            Thread reader=new Thread(()->{
                try{
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                }catch(Throwable ignored){}
                try{
                    long end=SystemClock.elapsedRealtime()+seconds*1000L;
                    while(running.get()&&(seconds==0||SystemClock.elapsedRealtime()<end)&&alive()){
                        byte[] frame=new byte[3840];
                        in.readFully(frame);
                        while(running.get()){
                            if(queue.offer(frame,200,TimeUnit.MILLISECONDS))break;
                            if(!alive()){running.set(false);break;}
                        }
                    }
                }catch(Throwable t){
                    readError[0]=t;
                }finally{
                    eof.set(true);
                }
            },"IrisAudioReader");
            reader.setDaemon(true);
            Thread reporter=startStatsReporter(status,running,bytesRef,nzRef,peakRef,headRef);
            try{
                control.applyIfNeeded(track,SystemClock.elapsedRealtime());
                track.play();
                reader.start();
                long end=SystemClock.elapsedRealtime()+seconds*1000L;
                while((seconds==0||SystemClock.elapsedRealtime()<end)&&alive()){
                    byte[] frame=queue.poll(200,TimeUnit.MILLISECONDS);
                    if(frame==null){
                        if(eof.get()){
                            if(readError[0]!=null){
                                if(readError[0] instanceof Exception)throw (Exception)readError[0];
                                throw new IOException(readError[0]);
                            }
                            break;
                        }
                        control.applyIfNeeded(track,SystemClock.elapsedRealtime());
                        continue;
                    }
                    long now=SystemClock.elapsedRealtime();
                    control.applyIfNeeded(track,now);
                    int count=frame.length;
                    long frameNz=0;int framePeak=peakRef.get();
                    for(int i=0;i+1<count;i+=2){int s=(short)((frame[i]&255)|(frame[i+1]<<8));if(s!=0)frameNz++;int a=s<0?-s:s;if(a>framePeak)framePeak=a;}
                    int offset=0;
                    while(offset<count){
                        int n=track.write(frame,offset,count-offset);
                        if(n<=0)throw new IOException("write="+n);
                        offset+=n;
                    }
                    bytesRef.addAndGet(count);
                    if(frameNz>0)nzRef.addAndGet(frameNz);
                    if(framePeak>peakRef.get())peakRef.set(framePeak);
                    headRef.set(track.getPlaybackHeadPosition()&0xFFFFFFFFL);
                }
                try{stats(status,bytesRef.get(),nzRef.get(),peakRef.get(),track.getPlaybackHeadPosition()&0xFFFFFFFFL);}catch(Throwable ignored){}
            }finally{
                running.set(false);
                reader.interrupt();
                reporter.interrupt();
                try{track.stop();}catch(RuntimeException ignored){}
                track.release();
            }
        }
    }
}
