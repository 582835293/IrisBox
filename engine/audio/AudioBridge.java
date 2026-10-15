package com.rootvmos.audio;
import android.content.Context;
import android.media.*;
import android.net.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class AudioBridge {
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
        // ActivityThread creates non-daemon Binder threads. Exit only after audio cleanup.
        System.exit(exitCode);
    }
    static void run(String[] args)throws Exception{
        System.err.println("ROOTVMOS_AUDIO_START "+(args.length>0?args[0]:"invalid"));
        if(args.length!=6)throw new IllegalArgumentException("guest|host socket tokenFile stats seconds session");
        String mode=args[0],socket=args[1],token=readToken(args[2]),status=args[3];int seconds=Integer.parseInt(args[4]);expected=args[5];
        if(seconds<0||seconds>86400||!expected.matches("[1-9][0-9]*:[1-9][0-9]*"))throw new IllegalArgumentException();
        Context ctx=context();
        System.err.println("ROOTVMOS_AUDIO_CONTEXT_READY");
        try{if(mode.equals("guest"))capture(ctx,socket,token,status,seconds);else if(mode.equals("host"))play(socket,token,status,seconds);else throw new IllegalArgumentException();}catch(IOException disconnected){System.err.println("ROOTVMOS_AUDIO_DISCONNECTED "+disconnected.getClass().getSimpleName());}
    }
    static void capture(Context ctx,String path,String token,String status,int seconds)throws Exception{
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
        System.err.println("ROOTVMOS_AUDIO_POLICY "+result);
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
                record.startRecording();if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("record start");
                long end=SystemClock.elapsedRealtime()+seconds*1000L,last=0,bytes=0,nz=0;int peak=0;
                byte[] buffer=new byte[3840];
                while(seconds==0||SystemClock.elapsedRealtime()<end){
                    int count=record.read(buffer,0,buffer.length);if(count<0)throw new IOException("read="+count);
                    for(int i=0;i+1<count;i+=2){int s=(short)((buffer[i]&255)|(buffer[i+1]<<8));if(s!=0)nz++;peak=Math.max(peak,Math.abs(s));}
                    out.write(buffer,0,count);bytes+=count;
                    if(SystemClock.elapsedRealtime()-last>1000){stats(status,bytes,nz,peak,-1);last=SystemClock.elapsedRealtime();}
                }
            }
        }finally{
            if(record!=null){try{record.stop();}catch(IllegalStateException ignored){}record.release();}
            manager.getClass().getMethod("unregisterAudioPolicy",policyClass).invoke(manager,policy);
            bind.close();if(bound)new File(path).delete();
        }
    }
    static void play(String path,String token,String status,int seconds)throws Exception{
        try(LocalSocket socket=new LocalSocket()){
            socket.connect(new LocalSocketAddress(path,LocalSocketAddress.Namespace.FILESYSTEM));socket.setSoTimeout(3000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeUTF(token);out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());
            if(in.readInt()!=0x52564131||in.readInt()!=48000||in.readInt()!=2)throw new IOException("format");
            int min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(format()).setBufferSizeInBytes(Math.max(min,15360)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("track initialization");
            try{
                track.play();long end=SystemClock.elapsedRealtime()+seconds*1000L,last=0,bytes=0,nz=0;int peak=0;byte[] buffer=new byte[3840];
                while((seconds==0||SystemClock.elapsedRealtime()<end)&&alive()){
                    in.readFully(buffer);int count=buffer.length;
                    for(int i=0;i+1<count;i+=2){int s=(short)((buffer[i]&255)|(buffer[i+1]<<8));if(s!=0)nz++;peak=Math.max(peak,Math.abs(s));}
                    int offset=0;while(offset<count){int n=track.write(buffer,offset,count-offset);if(n<=0)throw new IOException("write="+n);offset+=n;}
                    bytes+=count;if(SystemClock.elapsedRealtime()-last>1000){stats(status,bytes,nz,peak,track.getPlaybackHeadPosition());last=SystemClock.elapsedRealtime();}
                }
            }finally{track.stop();track.release();}
        }
    }
}
