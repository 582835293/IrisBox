package com.irisbox.buffer.viewer.gateway;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Host root一次性交接器：验证Guest会话与Viewer UID/PID/token，再交两个socket FD。
 * 顺序固定为graphics(SEQPACKET)、input(STREAM)，与DisplayActivity接收顺序配套。
 * 不读取像素、不转发每个输入；TAKEN后只关自己的引用，不能shutdown已交给App的连接。
 * One authenticated two-FD handoff. Never reads frame pixels or forwards input.
 */
public final class BufferGateway {
    private static final int MAX_CONTROLLED_SECONDS=900;
    private static final Path BASE=Paths.get("/data/irisbox/runtime");
    private static String read(Path p,int limit)throws IOException {
        if(Files.size(p)>limit)throw new IOException("State exceeds limit");
        return new String(Files.readAllBytes(p),StandardCharsets.UTF_8).trim();
    }
    private static String tick(int pid)throws IOException {
        String stat=read(Paths.get("/proc",Integer.toString(pid),"stat"),4096);
        String[] f=stat.substring(stat.lastIndexOf(')')+2).split(" +");
        if(f[0].equals("Z"))throw new IOException("Exited process");return f[19];
    }
    private static String session(int id)throws IOException {
        Path instance=BASE.resolve("instances/"+id);
        String[] ident=read(instance.resolve("init.identity"),128).split(" +");
        if(ident.length!=2||!ident[0].matches("[0-9]+")||!ident[1].matches("[0-9]+"))throw new IOException("Invalid identity");
        int pid=Integer.parseInt(ident[0]);Path proc=Paths.get("/proc",ident[0]);
        if(!tick(pid).equals(ident[1]))throw new IOException("Exited session");
        if(!read(proc.resolve("cmdline"),4096).contains("/system/bin/iris_init"))throw new IOException("Not Guest init");
        boolean pidOne=false;
        for(String line:read(proc.resolve("status"),16384).split("\n"))
            if(line.startsWith("NSpid:"))pidOne=line.trim().matches(".*\\s1");
        if(!pidOne)throw new IOException("Not Guest PID 1");
        for(String ns:new String[]{"pid","mnt","net","ipc","uts","cgroup"})
            if(Files.readSymbolicLink(proc.resolve("ns/"+ns)).equals(Files.readSymbolicLink(Paths.get("/proc/1/ns/"+ns))))throw new IOException("Shared Host namespace");
        if(!read(proc.resolve("mountinfo"),262144).contains("upperdir="+instance+"/upper,"))throw new IOException("Wrong Guest mount");
        String cg=read(proc.resolve("cgroup"),16384);
        if(!cg.contains("0::/irisbox_"+id+"/managed")||!cg.contains(":memory:/irisbox_"+id+"/managed"))throw new IOException("Wrong Guest groups");
        return ident[0]+":"+ident[1];
    }
    private static String line(InputStream in,int max)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<max;i++) {
            int b=in.read();if(b<0)throw new EOFException("Handshake EOF");
            if(b==10)return out.toString("US-ASCII");
            if(b<32||b>126)throw new IOException("Handshake characters");out.write(b);
        }
        throw new IOException("Handshake length");
    }
    private static void rootPrivate(Path path)throws Exception {
        if(Files.isSymbolicLink(path)||Os.stat(path.toString()).st_uid!=0||(Os.stat(path.toString()).st_mode&077)!=0)
            throw new IOException("Private root file required");
    }
    private static FileDescriptor[] connectMapped(int id,String expected,int width,int height,
                                                  ScheduledExecutorService timer)throws Exception {
        String[] identity=expected.split(":");
        String name="irisbox_userns_bridge_"+java.util.UUID.randomUUID().toString().replace("-","");
        LocalServerSocket control=new LocalServerSocket(name);
        Process helper=null;
        ScheduledFuture<?> expiry=timer.schedule(()->{try{control.close();}catch(IOException ignored){}},6,TimeUnit.SECONDS);
        FileDescriptor[] pair=null;
        try {
            helper=new ProcessBuilder(BASE.resolve("irisbox_userns").toString(),"bridge",Integer.toString(id),
                    identity[0],identity[1],name,Integer.toString(width),Integer.toString(height))
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try(LocalSocket connection=control.accept()) {
                connection.setSoTimeout(3000);
                // Credentials are captured at connect, before the native helper drops Host root.
                if(connection.getPeerCredentials().getUid()!=0)throw new IOException("Untrusted connector");
                int marker=connection.getInputStream().read();
                pair=connection.getAncillaryFileDescriptors();
                if(marker!='F'||pair==null||pair.length!=2)throw new IOException("Expected graphics and input FDs");
                if(!helper.waitFor(3,TimeUnit.SECONDS)||helper.exitValue()!=0)throw new IOException("Mapped connector failed");
                FileDescriptor[] result=pair;pair=null;return result;
            }
        } finally {
            expiry.cancel(false);control.close();
            if(helper!=null&&helper.isAlive())helper.destroyForcibly();
            closePair(pair);
        }
    }
    private static void closePair(FileDescriptor[] pair) {
        if(pair!=null)for(FileDescriptor fd:pair)try{Os.close(fd);}catch(Exception ignored){}
    }
    public static void main(String[] args)throws Exception {
        // ID SESSION ABSTRACT_NAME RUN_TOKEN_FILE APK_UID SECONDS INPUT_WIDTH INPUT_HEIGHT
        if(args.length!=8)throw new IllegalArgumentException("Expected eight gateway arguments");
        int id=Integer.parseInt(args[0]),uid=Integer.parseInt(args[4]),seconds=Integer.parseInt(args[5]);
        int width=Integer.parseInt(args[6]),height=Integer.parseInt(args[7]);String expected=args[1],name=args[2];
        if((id<1007||id>1134)||!expected.matches("[0-9]+:[0-9]+")||!name.matches("irisbox_viewer_[a-zA-Z0-9_]{1,60}")||
            uid<10000||seconds<0||seconds>MAX_CONTROLLED_SECONDS||width<960||width>1920||height<540||height>1080||width*9!=height*16)throw new IllegalArgumentException("Invalid gateway arguments");
        if(Os.getuid()!=0)throw new IllegalStateException("Host root required");
        for(String ns:new String[]{"pid","mnt","net","ipc","uts","cgroup"})
            if(!Files.readSymbolicLink(Paths.get("/proc/self/ns/"+ns)).equals(Files.readSymbolicLink(Paths.get("/proc/1/ns/"+ns))))throw new IllegalStateException("Host namespace required");
        if(!expected.equals(session(id)))throw new IOException("Initial Guest session changed");
        Path tokenPath=Paths.get(args[3]).normalize(),runs=BASE.resolve("display/buffer_viewer/runs");
        if(!tokenPath.startsWith(runs)||!tokenPath.getFileName().toString().equals("token")||!tokenPath.toRealPath().equals(tokenPath))throw new IOException("Invalid token path");
        rootPrivate(tokenPath);String token=read(tokenPath,128);
        if(!token.matches("[0-9a-f]{64}"))throw new IOException("Invalid token");
        Path geometry=tokenPath.getParent().resolve("resolution.effective");rootPrivate(geometry);
        String[] effective=read(geometry,256).split(" +");
        if(effective.length!=7||!effective[0].equals(expected)||!effective[2].equals(args[6])||!effective[3].equals(args[7])||
            !read(BASE.resolve("instances/"+id+"/resolution.effective"),256).equals(read(geometry,256)))throw new IOException("Gateway geometry does not match this Guest session");
        Path data=BASE.resolve("instances/"+id+"/data");
        Path graphicsPath=data.resolve("iris_buffer/"+expected.replace(':','_')+"/output.sock");
        Path inputDir=data.resolve("iris_stream/"+expected.replace(':','_'));
        final LocalServerSocket server=new LocalServerSocket(name);
        final ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor();
        if(seconds>0)deadline.schedule(()->{System.err.println("GATEWAY_DEADLINE");Runtime.getRuntime().halt(3);},seconds,TimeUnit.SECONDS);
        long began=SystemClock.elapsedRealtime();
        System.out.println("GATEWAY_READY id="+id+" session="+expected+" allowedUid="+uid);
        try(LocalSocket app=server.accept()) {
            app.setSoTimeout(3000);
            int peerPid=app.getPeerCredentials().getPid();
            if(app.getPeerCredentials().getUid()!=uid||peerPid<=1)throw new IOException("Wrong APK UID/PID");
            String peerTick=tick(peerPid);
            if(!read(Paths.get("/proc",Integer.toString(peerPid),"cmdline"),4096).equals("com.irisbox.viewer:display"))throw new IOException("Dedicated display process required");
            // Android gives ordinary apps a storage mount namespace. It is a
            // legitimate Host app, not a Guest: do not require Host init's mnt.
            for(String ns:new String[]{"pid","net","ipc","uts","cgroup"})
                if(!Files.readSymbolicLink(Paths.get("/proc",Integer.toString(peerPid),"ns",ns)).equals(Files.readSymbolicLink(Paths.get("/proc/1/ns",ns))))throw new IOException("Display Host namespace required");
            String[] hello=line(app.getInputStream(),160).split(" ");
            if(hello.length!=5||!hello[0].equals("HELLO")||!hello[1].equals("2")||!hello[2].equals(args[0])||!hello[3].equals(expected)||!hello[4].equals(token))throw new IOException("Handshake rejected");
            if(!expected.equals(session(id)))throw new IOException("Guest changed before handoff");
            FileDescriptor[] pair=connectMapped(id,expected,width,height,deadline);
            try {
                if(!expected.equals(session(id))||!peerTick.equals(tick(peerPid)))throw new IOException("Session changed during connect");
                Path identity=tokenPath.getParent().resolve("display.identity");
                Files.write(identity,(peerPid+" "+peerTick+"\n").getBytes(StandardCharsets.US_ASCII));Os.chmod(identity.toString(),0600);
                /** FD传送得到的是同一socket的另一引用；App复制并回TAKEN后，本进程退出即可。
                 */
                app.setFileDescriptorsForSend(pair);
                app.getOutputStream().write(("OK 2 "+id+" "+expected+"\n").getBytes(StandardCharsets.US_ASCII));
                app.setFileDescriptorsForSend(null);
                if(!line(app.getInputStream(),16).equals("TAKEN"))throw new IOException("APK did not take both FDs");
                // Close references only. shutdown would break APK-owned duplicate sockets.
                // No P command here: the newly authenticated input sequence starts at 1.
                System.out.println("GATEWAY_HANDED_OFF id="+id+" session="+expected+" display="+peerPid+":"+peerTick+" elapsedMs="+(SystemClock.elapsedRealtime()-began));
            } finally {closePair(pair);}
        } finally {server.close();deadline.shutdownNow();}
    }
}
