package com.irisbox.buffer.viewer.broker;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 绑定用户这一次启动的日志窗口；latest.logdir和init身份核验后才展示对应材料。
 * 启动器、init与持续Guest logcat分开标注，完整脱敏展示落盘，按字节分页读取。
 * elapsed用单调时钟，文件时间判断另用wallBegan；不能混用两种时间比较。
 * Only one user start attempt's diagnostic material; never reads cumulative command logs.
 */
final class BootAttempt {
    private final Path instance;
    private final String previousLog;
    final long began=SystemClock.elapsedRealtime(),wallBegan=System.currentTimeMillis();
    volatile long finished,lastGuestSample;
    volatile String session="";
    private Path logDirectory;
    private final ArrayList<String> pending=new ArrayList<>();
    private final ByteArrayOutputStream partial=new ByteArrayOutputStream();
    private BootLogStore store;
    private long initOffset;
    private Process guestCapture;
    private boolean closed,recovered;
    private String key=UUID.randomUUID().toString().replace("-","");
    private String guestCursor="",guestFingerprint="";
    String key(){return key;}
    static BootAttempt restore(Path instance,String expected) {
        BootAttempt result=new BootAttempt(instance);result.recovered=true;
        try {
            Path path=result.checkedDirectory(result.marker());
            if(path==null)return null;
            result.logDirectory=path;
            if(!expected.isEmpty()&&!result.logIdentity(expected))return null;
            result.session=expected;result.openStore();
            result.collectInit();return result;
        }catch(Exception unavailable){result.close();return null;}
    }
    BootAttempt(Path root){instance=root;previousLog=marker();}
    private String marker() {
        try {
            Path p=instance.resolve("latest.logdir");if(Files.isSymbolicLink(p)||Files.size(p)>512)return "";
            return new String(Files.readAllBytes(p),StandardCharsets.UTF_8).trim();
        }catch(Exception e){return "";}
    }
    /** UI脱敏仅为启发式，不能保证全部隐私已过滤；原始日志不要直接公开。
     */
    static String safe(String line) {
        String lower=line.toLowerCase(Locale.US);
        if(lower.contains("token")||lower.contains("secret")||lower.contains("password")||lower.contains("passwd")||
            lower.contains("authorization")||lower.contains("bearer")||lower.contains("private key")||lower.contains("private_key")||
            lower.contains("ssh-rsa")||lower.contains("ssh-ed25519"))return "[已隐藏可能包含凭据的日志行]";
        String value=line.replaceAll("[\\p{Cntrl}&&[^\\t]]","").replaceAll("(?i)[0-9a-f]{48,}","[长标识已隐藏]");
        return value;
    }
    private synchronized void append(String line) {
        if(closed||line.trim().isEmpty())return;
        try{if(store==null)pending.add(line);else store.append(line);}catch(IOException failed){System.err.println("Startup log write failed");}
    }
    synchronized void command(byte[] bytes,int count) {
        // 先按换行拆字节，再解UTF-8，避免输出块恰好切开汉字时变乱码。
        for(int n=0;n<count;n++) {
            if(bytes[n]=='\n'){append("[启动器] "+safe(new String(partial.toByteArray(),StandardCharsets.UTF_8)));partial.reset();}
            else partial.write(bytes[n]);
        }
    }
    synchronized void commandEnded() {if(partial.size()>0){append("[启动器] "+safe(new String(partial.toByteArray(),StandardCharsets.UTF_8)));partial.reset();}}
    private Path checkedDirectory(String value)throws IOException {
        if(value.isEmpty())return null;Path path=Paths.get(value).normalize();
        if(!path.getParent().equals(instance)||!path.getFileName().toString().matches("[0-9]{8}_[0-9]{6}")||
            Files.isSymbolicLink(path)||!Files.isDirectory(path)||!path.toRealPath().equals(path))return null;
        return path;
    }
    private void openStore()throws IOException {
        Path keyFile=logDirectory.resolve("ui-log.key");
        if(Files.isSymbolicLink(keyFile))throw new IOException("Unexpected log key link");
        if(recovered&&Files.exists(keyFile)){
            String saved=new String(Files.readAllBytes(keyFile),StandardCharsets.US_ASCII).trim();
            if(!saved.matches("[a-f0-9]{32}"))throw new IOException("Invalid log key");key=saved;
        }else Files.write(keyFile,key.getBytes(StandardCharsets.US_ASCII));
        store=new BootLogStore(logDirectory.resolve("ui-startup.log"));
        initOffset=readOffset("ui-init.offset");
        Path cursor=logDirectory.resolve("ui-guest.cursor");
        if(Files.exists(cursor)&&!Files.isSymbolicLink(cursor)&&Files.size(cursor)<128){
            String[] values=new String(Files.readAllBytes(cursor),StandardCharsets.US_ASCII).trim().split(" ");
            if(values.length==2&&values[0].matches("[0-9]+\\.[0-9]{6}")&&values[1].matches("[a-f0-9]{64}")){guestCursor=values[0];guestFingerprint=values[1];}
        }
        for(String line:pending)store.append(line);pending.clear();
    }
    private long readOffset(String name)throws IOException {
        Path path=logDirectory.resolve(name);if(!Files.exists(path))return 0;
        if(Files.isSymbolicLink(path)||Files.size(path)>32)throw new IOException("Invalid log offset");
        String value=new String(Files.readAllBytes(path),StandardCharsets.US_ASCII).trim();
        if(!value.matches("[0-9]{1,18}"))throw new IOException("Invalid log offset");return Long.parseLong(value);
    }
    /** 增量读原init输出，不反复扫描整份文件；未结束的一行留给下一次读取。 */
    synchronized void collectInit() {
        bindLog();if(store==null||closed||!session.isEmpty()&&!logIdentity(session))return;
        try {
            Path path=logDirectory.resolve("init.log");if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path))return;
            try(RandomAccessFile input=new RandomAccessFile(path.toFile(),"r")) {
                if(initOffset>input.length())throw new IOException("Log replaced");input.seek(initOffset);
                byte[] block=new byte[(int)Math.min(65536,input.length()-initOffset)];input.readFully(block);
                int began=0;
                for(int n=0;n<block.length;n++)if(block[n]=='\n'){
                    append("[init] "+safe(new String(block,began,n-began,StandardCharsets.UTF_8)));began=n+1;
                }
                initOffset+=began;
            }
            store.size();Files.write(logDirectory.resolve("ui-init.offset"),Long.toString(initOffset).getBytes(StandardCharsets.US_ASCII));
        }catch(Exception ignored){}
    }
    /** 只启一个随Guest会话结束的logcat，保留logd现存开头并持续接收，不采样最后16行。 */
    synchronized void captureGuest(int id,String expected) {
        if(closed||store==null||!expected.equals(session)||guestCapture!=null&&guestCapture.isAlive())return;
        try {
            ArrayList<String> cmd=new ArrayList<>(Arrays.asList("/system/bin/sh","/data/irisbox/runtime/ctl.sh","exec",Integer.toString(id),"logcat","-b","main,system,events,crash","-v","threadtime,epoch,usec"));
            String cursor=guestCursor,fingerprint=guestFingerprint;
            if(!cursor.isEmpty()){cmd.add("-T");cmd.add(cursor);}
            ProcessBuilder builder=new ProcessBuilder(cmd).redirectErrorStream(true);builder.environment().remove("CLASSPATH");builder.environment().put("IRIS_EXPECT_SESSION",expected);
            Process process=builder.start();process.getOutputStream().close();guestCapture=process;
            Thread reader=new Thread(()->{
                try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;boolean skipBoundary=!cursor.isEmpty();
                    while((line=input.readLine())!=null) {
                        synchronized(BootAttempt.this) {
                            if(closed||!expected.equals(session))break;
                            String stamp=line.trim().split(" +",2)[0];
                            if(stamp.matches("[0-9]+\\.[0-9]{6}")) {
                                String hash=digest(line);
                                if(skipBoundary&&stamp.equals(cursor)&&hash.equals(fingerprint)){skipBoundary=false;continue;}
                                guestCursor=stamp;guestFingerprint=hash;
                            }
                            append("[Guest日志] "+safe(line));
                        }
                    }
                }catch(Exception ignored){}
            },"iris-startup-log-"+id);reader.setDaemon(true);reader.start();
        }catch(IOException ignored){}
    }
    private static String digest(String line)throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
        char[] hex="0123456789abcdef".toCharArray(),value=new char[bytes.length*2];for(int n=0;n<bytes.length;n++){int b=bytes[n]&255;value[n*2]=hex[b>>>4];value[n*2+1]=hex[b&15];}return new String(value);
    }
    synchronized void checkpoint() {
        if(store==null||closed)return;
        try{store.size();if(!guestCursor.isEmpty())Files.write(logDirectory.resolve("ui-guest.cursor"),(guestCursor+" "+guestFingerprint).getBytes(StandardCharsets.US_ASCII));}catch(IOException ignored){}
    }
    synchronized JSONObject page(long offset)throws Exception {
        collectInit();checkpoint();
        if(store==null)return new JSONObject().put("key",key).put("offset",offset).put("next",0).put("total",0).put("text","");
        BootLogStore.Page page=store.page(offset,8192);
        return new JSONObject().put("key",key).put("offset",page.offset).put("next",page.next).put("total",page.total).put("text",page.text);
    }
    synchronized void stopCapture() {if(guestCapture!=null){guestCapture.destroy();guestCapture=null;}}
    synchronized void close() {
        checkpoint();closed=true;if(guestCapture!=null)guestCapture.destroy();
        if(store!=null)try{store.close();}catch(IOException ignored){}
    }
    /** 只认本次产生的新日志目录及时间；不把上一次启动尾部展示为这一次进度。
     */
    synchronized void bindLog() {
        if(logDirectory!=null||finished!=0)return;
        try {
            String value=marker();if(value.isEmpty()||value.equals(previousLog))return;
            Path path=checkedDirectory(value);if(path==null)return;
            if(Files.getLastModifiedTime(instance.resolve("latest.logdir")).toMillis()<wallBegan-1000)return;
            logDirectory=path;openStore();
        }catch(Exception ignored){}
    }
    private boolean logIdentity(String expected) {
        if(logDirectory==null)return false;
        try {
            Path p=logDirectory.resolve("init.identity");
            if(Files.isSymbolicLink(p)||Files.size(p)>128||!p.toRealPath().equals(p))return false;
            String value=new String(Files.readAllBytes(p),StandardCharsets.US_ASCII).trim().replaceAll(" +",":");
            return value.equals(expected);
        }catch(Exception e){return false;}
    }
    synchronized boolean bindSession(String expected) {
        bindLog();if(expected.isEmpty()||!logIdentity(expected))return false;
        if(session.isEmpty())session=expected;
        return session.equals(expected);
    }
    synchronized JSONArray logs() {
        collectInit();checkpoint();JSONArray lines=new JSONArray();
        if(store!=null)for(String line:store.summary())lines.put(line.length()>320?line.substring(0,320)+"…":line);
        else for(String line:pending)lines.put(line);return lines;
    }
    long elapsed(){if(recovered)return 0;return Math.max(0,(finished==0?SystemClock.elapsedRealtime():finished)-began);}
}
