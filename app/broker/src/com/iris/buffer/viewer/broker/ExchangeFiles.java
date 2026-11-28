package com.irisbox.buffer.viewer.broker;

import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.irisbox.buffer.viewer.broker.ExchangeProtocol.Failure;

/** Host文件中间层：HTTP流 ↔ ctl exec启动的一次性Guest MediaProvider客户端。
 * Job绑定id/session与期限，Helper用有界块队列排空管道，不把整文件读入内存。
 * 导入按pending/staged/COMMIT/committed握手，账本供中断后精确恢复；停止先取消传输。
 * Session-bound streaming exchange through one-shot Guest MediaProvider clients.
 */
final class ExchangeFiles implements AutoCloseable {
    interface State {
        String session(int id);
        boolean ready(int id, String session);
        boolean busy(int id);
        boolean stopping(int id);
    }
    private static final String BASE="/data/irisbox/runtime", BB="/data/irisbox/host/bin/busybox";
    private static final String HOST_EXPORT_ROOT="/data/media/0/Download/IrisBoxExport";
    private static final Path DIRECTORY=Paths.get(BASE,"display/buffer_viewer");
    private final State state;
    private final Object lock=new Object();
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1);
    private Job active;
    private volatile boolean closed;
    ExchangeFiles(State state){this.state=state;deadlines.setRemoveOnCancelPolicy(true);}

    private final class Job implements ExchangeProtocol.Guard {
        final int id;final String session;final Socket socket;
        final long deadline=SystemClock.elapsedRealtime()+ExchangeProtocol.TRANSFER_SECONDS*1000L;
        volatile Failure failure;volatile boolean discarding;
        volatile Helper helper;
        volatile boolean helperExited=true;
        String transaction;
        Job(int id,String session,Socket socket){this.id=id;this.session=session;this.socket=socket;}
        public void check()throws IOException {
            if(failure!=null)throw failure;
            if(closed)throw new Failure(503,"MANAGER_STOPPED","文件管理器已停止");
            if(SystemClock.elapsedRealtime()>=deadline)throw new Failure(408,"TRANSFER_TIMEOUT","文件操作超时，请重试");
            if(!session.equals(state.session(id)))throw new Failure(409,"SESSION_CHANGED","实例会话已改变，请返回列表重新进入");
            if(state.stopping(id))throw new Failure(409,"SESSION_STOPPING","实例正在停止，文件操作已取消");
        }
        void cancel(String code,String message){
            if(failure==null)failure=new Failure(code.equals("TRANSFER_TIMEOUT")?408:409,code,message);
            discarding=true;
            try{socket.close();}catch(IOException ignored){}
            // Closing stdin gives an uncommitted import EOF, allowing its exact-URI rollback.
            // A pipe close can wait for another writer's monitor/flush. Never do it
            // on the shared deadline scheduler or on the Guest stop caller.
            Helper running=helper;if(running!=null)running.closeInputAsync();
        }
    }

    /** Bounded stdout queue keeps pipe waits interruptible without buffering a whole file. */
    private final class Helper implements AutoCloseable {
        final Job job;final Process process;final OutputStream input;
        final ArrayBlockingQueue<byte[]> output=new ArrayBlockingQueue<>(2);
        volatile boolean ended;volatile IOException readError;volatile boolean succeeded;
        volatile int terminalExit=-1;
        final Thread stdout;
        final AtomicBoolean closingInput=new AtomicBoolean();
        byte[] block;int offset;
        final InputStream stream=new InputStream(){
            private final byte[] one=new byte[1];
            public int read()throws IOException{int n=read(one,0,1);return n<0?-1:one[0]&255;}
            public int read(byte[] bytes,int start,int count)throws IOException {
                if(count==0)return 0;
                while(block==null||offset==block.length){
                    job.check();
                    try{block=output.poll(200,TimeUnit.MILLISECONDS);offset=0;}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted",e);}
                    if(block==null&&ended&&output.isEmpty()){
                        if(readError!=null)throw readError;return -1;
                    }
                }
                int n=Math.min(count,block.length-offset);System.arraycopy(block,offset,bytes,start,n);offset+=n;return n;
            }
        };
        Helper(Job job,String...args)throws Exception {
            this.job=job;job.check();
            ArrayList<String> command=new ArrayList<>(Arrays.asList(BB,"timeout","-s","KILL",Integer.toString(ExchangeProtocol.TRANSFER_SECONDS+10),"/system/bin/sh",BASE+"/ctl.sh","exec",Integer.toString(job.id),"/system/bin/busybox","setuidgid","2000","/system/bin/busybox","env",
                    "CLASSPATH=/system/framework/iris_file_exchange.jar","IRIS_EXCHANGE_DEADLINE_MS="+job.deadline,"/system/bin/app_process","/system/bin","com.iris.fileexchange.GuestExchange"));
            command.addAll(Arrays.asList(args));
            ProcessBuilder builder=new ProcessBuilder(command);
            builder.environment().remove("CLASSPATH");builder.environment().remove("IRIS_CTL_LOCKED");builder.environment().remove("IRIS_NETWORK_LOCKED");
            builder.environment().put("IRIS_EXPECT_SESSION",job.session);
            process=builder.start();job.helperExited=false;input=process.getOutputStream();job.helper=this;
            if(job.failure!=null||closed)closeInputAsync();
            final boolean observeImport=args.length>0&&args[0].equals("import");
            stdout=new Thread(()->{
                ByteArrayOutputStream notice=new ByteArrayOutputStream();boolean oversized=false;
                try(InputStream source=process.getInputStream()){
                    byte[] bytes=new byte[ExchangeProtocol.BLOCK_BYTES];int count;
                    while((count=source.read(bytes))!=-1){
                        // A cancelled HTTP reader cannot consume the final error. Keep
                        // observing bounded import JSON while draining; never inspect
                        // export's binary stream or treat pipe exit alone as completion.
                        if(observeImport)for(int i=0;i<count;i++){
                            int b=bytes[i]&255;
                            if(b=='\n'){
                                if(!oversized)try{
                                    String phase=new JSONObject(new String(notice.toByteArray(),StandardCharsets.UTF_8)).optString("phase");
                                    if(phase.equals("error"))terminalExit=1;
                                    else if(phase.equals("committed"))terminalExit=0;
                                }catch(Exception ignored){}
                                notice.reset();oversized=false;
                            }else if(notice.size()<65536)notice.write(b);else oversized=true;
                        }
                        if(job.discarding)continue;
                        byte[] copy=Arrays.copyOf(bytes,count);
                        while(!job.discarding&&!output.offer(copy,200,TimeUnit.MILLISECONDS)){}
                    }
                }catch(Exception e){if(e instanceof IOException)readError=(IOException)e;}
                finally{ended=true;}
            },"exchange-stdout");stdout.setDaemon(true);stdout.start();
            Thread stderr=new Thread(()->{
                try(InputStream source=process.getErrorStream()){
                    byte[] bytes=new byte[1024];int count,total=0;
                    while((count=source.read(bytes))!=-1){if(total<4096)System.err.write(bytes,0,Math.min(count,4096-total));total+=count;}
                }catch(IOException ignored){}
            },"exchange-stderr");stderr.setDaemon(true);stderr.start();
        }
        void closeInput(){try{input.close();}catch(IOException ignored){}}
        void closeInputAsync(){
            if(!closingInput.compareAndSet(false,true))return;
            Thread closer=new Thread(this::closeInput,"exchange-cancel-input");closer.setDaemon(true);closer.start();
        }
        JSONObject message()throws Exception {
            ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
            while((b=stream.read())!=-1&&b!='\n'){
                if(line.size()>=65536)throw new Failure(503,"HELPER_PROTOCOL","实例文件服务响应过大");line.write(b);
            }
            if(b<0)throw new Failure(503,"STORAGE_NOT_READY","实例文件服务已退出，请重试");
            JSONObject value=new JSONObject(new String(line.toByteArray(),StandardCharsets.UTF_8));
            if("error".equals(value.optString("phase"))){
                terminalExit=1;
                int status=value.optInt("status",503);if(status<400||status>599)status=503;
                throw new Failure(status,value.optString("code","STORAGE_ERROR"),value.optString("error","实例文件服务操作失败"));
            }
            return value;
        }
        JSONObject phase(String expected)throws Exception {
            JSONObject value=message();if(!expected.equals(value.optString("phase")))throw new Failure(503,"HELPER_PROTOCOL","实例文件服务响应阶段不符");
            if(expected.equals("listed")||expected.equals("recovered")||expected.equals("committed"))terminalExit=0;
            return value;
        }
        void finish()throws Exception {
            closeInput();
            if(stream.read()!=-1)throw new Failure(503,"HELPER_PROTOCOL","实例文件服务包含多余数据");
            if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new Failure(503,"STORAGE_ERROR","实例文件服务未正常完成");
            succeeded=true;terminalExit=0;
        }
        public void close(){
            if(!succeeded)job.discarding=true;
            closeInput();
            // Do not kill the namespace proxy first and orphan an unbounded child.
            // Every helper shares this Job's absolute deadline, with a 5 s hard
            // exit grace. Recovery cannot give a later helper a new 120 s budget.
            long until=job.deadline+12000;
            try{while(process.isAlive()&&SystemClock.elapsedRealtime()<until)process.waitFor(200,TimeUnit.MILLISECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(process.isAlive())process.destroyForcibly();
            else {
                try{stdout.join(500);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                if(terminalExit>=0&&process.exitValue()==terminalExit)job.helperExited=true;
            }
            if(job.helper==this)job.helper=null;
        }
    }

    private static Path journal(int id){return DIRECTORY.resolve("exchange_"+id+".pending.json");}
    private static String bootId()throws IOException {
        Path file=Paths.get("/proc/sys/kernel/random/boot_id");
        if(Files.size(file)>128)throw new IOException("Boot identity exceeds limit");
        String value=new String(Files.readAllBytes(file),StandardCharsets.US_ASCII).trim();
        if(!value.matches("[0-9a-f-]{36}"))throw new IOException("Boot identity unavailable");
        return value;
    }
    private static String token(){byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private void recover(Job job)throws Exception {
        Path record=journal(job.id);if(!Files.exists(record,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(record)||Files.size(record)>2048)throw new Failure(503,"RECOVERY_FAILED","文件事务记录异常，未执行清理");
        JSONObject value=new JSONObject(new String(Files.readAllBytes(record),StandardCharsets.UTF_8));
        String transaction=value.optString("token");
        if(value.optInt("id")!=job.id||!transaction.matches("[0-9a-f]{32}"))throw new Failure(503,"RECOVERY_FAILED","文件事务身份不符，未执行清理");
        long after=value.optLong("recoverAfterElapsedMs",-1);
        if(after<0||!value.optString("bootId").matches("[0-9a-f-]{36}")||!value.optString("session").matches("[0-9]+:[0-9]+"))
            throw new Failure(503,"RECOVERY_FAILED","旧事务缺少退出期限，未自动删除");
        if(!value.optBoolean("helperExited",false)&&value.optString("bootId").equals(bootId())&&value.optString("session").equals(job.session)&&SystemClock.elapsedRealtime()<after)
            throw new Failure(423,"RECOVERY_WAIT","上次文件服务仍在回收，请稍后重试");
        try(Helper helper=new Helper(job,"recover",transaction)){helper.phase("recovered");helper.finish();}
        job.check();Files.delete(record);
    }
    private void writeRecord(int id,JSONObject value)throws Exception {
        Path temporary=DIRECTORY.resolve("exchange_"+id+".pending.tmp");
        if(Files.isSymbolicLink(temporary))throw new Failure(503,"RECOVERY_FAILED","文件事务临时记录异常");
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        Files.deleteIfExists(temporary);
        try(FileOutputStream output=new FileOutputStream(temporary.toFile())){Os.chmod(temporary.toString(),0600);output.write(bytes);output.getFD().sync();}
        Files.move(temporary,journal(id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void record(Job job,String transaction,String name)throws Exception {
        if(Files.exists(journal(job.id),LinkOption.NOFOLLOW_LINKS))throw new Failure(503,"RECOVERY_REQUIRED","上次文件事务尚未清理");
        // If the Broker dies, a helper could have been launched just before this
        // job's deadline. Cover its independent 130 s outer bound plus 15 s grace.
        writeRecord(job.id,new JSONObject().put("id",job.id).put("session",job.session).put("token",transaction).put("name",name)
                .put("bootId",bootId()).put("recoverAfterElapsedMs",job.deadline+25000L).put("helperExited",false));
        job.transaction=transaction;
    }
    private void releaseRecoveryLease(Job job){
        if(job.transaction==null||!job.helperExited)return;
        try{
            Path file=journal(job.id);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            if(Files.isSymbolicLink(file)||Files.size(file)>2048)throw new IOException("Journal identity");
            JSONObject value=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            if(!job.transaction.equals(value.optString("token"))||!job.session.equals(value.optString("session")))throw new IOException("Journal replaced");
            value.put("helperExited",true);writeRecord(job.id,value);
        }catch(Exception error){System.err.println("Exchange recovery lease retained: "+error.getClass().getSimpleName());}
    }

    void requireStoppedIdle(int id)throws Exception {
        synchronized(lock){
            if(active!=null&&active.id==id)throw new Failure(409,"TRANSFER_BUSY","请等待该实例的文件操作结束");
            if(!state.session(id).isEmpty())throw new Failure(409,"INSTANCE_RUNNING","请先停止实例");
        }
    }
    void forgetStopped(int id)throws Exception {
        synchronized(lock){
            if(active!=null&&active.id==id)throw new Failure(409,"TRANSFER_BUSY","请等待该实例的文件操作结束");
            if(!state.session(id).isEmpty())throw new Failure(409,"INSTANCE_RUNNING","请先停止实例");
            // The stopped Guest's private MediaProvider data is about to be erased.
            // Its old transaction cannot be recovered into the replacement data.
            for(Path p:new Path[]{journal(id),DIRECTORY.resolve("exchange_"+id+".pending.tmp")}){
                if(Files.isSymbolicLink(p))throw new IOException("Invalid file transaction record");
                Files.deleteIfExists(p);
            }
        }
    }

    /** 受理前核会话/就绪和唯一活跃Job，停止时取消；任何异步读写都由Job再次检查期限/身份。
     */
    void handle(Socket socket,InputStream body,String path,String method,Map<String,String> query,String contentLength,String contentType)throws Exception {
        int id;
        try{id=Integer.parseInt(query.getOrDefault("id","0"));}catch(NumberFormatException error){throw new Failure(400,"INVALID_INSTANCE","实例编号不正确");}
        if(!InstanceCatalog.contains(id))throw new Failure(400,"INVALID_INSTANCE","实例编号不正确");
        String expected=query.getOrDefault("session","");
        if(expected.isEmpty())throw new Failure(409,"SESSION_CHANGED","实例会话已改变，请返回列表重新进入");
        boolean importing=path.equals("/files/import")&&method.equals("POST"),
                exporting=path.equals("/files/export")&&method.equals("GET"),
                exportingHost=path.equals("/files/export_host")&&method.equals("POST"),
                listing=path.equals("/files")&&method.equals("GET");
        if(!importing&&!exporting&&!exportingHost&&!listing)throw new Failure(404,"UNKNOWN_ENDPOINT","没有这个文件操作");
        boolean hasDir=query.containsKey("dir");
        if(importing&&hasDir)throw new Failure(400,"INVALID_QUERY","文件操作参数不正确");
        Set<String> allowed=new HashSet<>(Arrays.asList("id","session"));
        if(!listing)allowed.add("name");
        if(hasDir&&!importing)allowed.add("dir");
        if(!allowed.equals(query.keySet()))throw new Failure(400,"INVALID_QUERY","文件操作参数不正确");
        String dir=hasDir?ExchangeProtocol.relativeDir(query.get("dir")):ExchangeProtocol.DIRECTORY;
        String name=listing?"":ExchangeProtocol.filename(query.get("name"));
        long length=importing?ExchangeProtocol.contentLength(contentLength):0;
        if(importing&&!"application/octet-stream".equalsIgnoreCase(contentType))throw new Failure(400,"INVALID_CONTENT_TYPE","需要原始文件数据");
        if(!importing&&contentLength!=null&&ExchangeProtocol.contentLength(contentLength)!=0)throw new Failure(400,"BODY_NOT_ALLOWED","此操作不接受请求正文");
        Job job=new Job(id,expected,socket);
        synchronized(lock){
            job.check();
            if(!state.ready(id,expected))throw new Failure(503,"STORAGE_NOT_READY","实例尚未就绪，请稍后重试");
            if(state.busy(id))throw new Failure(409,"INSTANCE_BUSY","实例正在执行其他操作，请稍后重试");
            if(active!=null)throw new Failure(423,"TRANSFER_BUSY","已有文件操作正在进行，请稍后重试");
            active=job;
        }
        ScheduledFuture<?> expiry=null;
        boolean responseStarted=false;
        try {
            expiry=deadlines.schedule(()->job.cancel("TRANSFER_TIMEOUT","文件操作超时"),ExchangeProtocol.TRANSFER_SECONDS,TimeUnit.SECONDS);
            socket.setSoTimeout(ExchangeProtocol.IDLE_SECONDS*1000);
            if(listing){
                if(!hasDir){
                    recover(job);
                    JSONObject result;
                    try(Helper helper=new Helper(job,"list")){result=helper.phase("listed");helper.finish();}
                    job.check();result.remove("phase");
                    result.put("id",id).put("session",expected).put("dir",ExchangeProtocol.DIRECTORY)
                        .put("directory",ExchangeProtocol.DIRECTORY).put("dirs",new JSONArray())
                        .put("maxFileBytes",ExchangeProtocol.MAX_FILE_BYTES);
                    respond(socket,200,result);
                }else{
                    respond(socket,200,listDirectChildren(job,id,expected,dir));
                }
            }else if(importing){
                recover(job);
                String transaction=token();record(job,transaction,name);
                JSONObject committed;
                try(Helper helper=new Helper(job,"import",transaction,name,Long.toString(length))){
                    JSONObject pending=helper.phase("pending");
                    if(!pending.optString("uri").matches("content://media/external_primary/downloads/[0-9]+"))throw new Failure(503,"HELPER_PROTOCOL","实例待写入文件身份不符");
                    ExchangeProtocol.Copied copied=ExchangeProtocol.copyExactly(body,helper.input,length,job);helper.input.flush();
                    JSONObject staged=helper.phase("staged");
                    if(staged.optLong("bytes",-1)!=length||!copied.sha256.equals(staged.optString("sha256")))throw new Failure(503,"WRITE_MISMATCH","实例文件内容校验失败");
                    job.check();helper.input.write("COMMIT\n".getBytes(StandardCharsets.US_ASCII));helper.input.flush();
                    committed=helper.phase("committed");
                    if(!name.equals(committed.optString("name"))||committed.optLong("bytes",-1)!=length||!copied.sha256.equals(committed.optString("sha256")))throw new Failure(503,"PUBLISH_UNCONFIRMED","文件结果未确认，请刷新列表");
                    helper.finish();
                }
                job.check();Files.delete(journal(id));committed.remove("phase");committed.put("id",id).put("session",expected);respond(socket,201,committed);
            }else if(exportingHost){
                respond(socket,200,exportDirectToHost(job,id,expected,dir,name));
            }else if(!hasDir){
                recover(job);
                try(Helper helper=new Helper(job,"export",name)){
                    JSONObject metadata=helper.phase("export");long bytes=metadata.optLong("bytes",-1);String digest=metadata.optString("sha256");
                    if(!name.equals(metadata.optString("name"))||bytes<0||bytes>ExchangeProtocol.MAX_FILE_BYTES||!digest.matches("[0-9a-f]{64}"))throw new Failure(503,"HELPER_PROTOCOL","实例文件信息不正确");
                    job.check();OutputStream output=socket.getOutputStream();
                    responseStarted=true;
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+bytes+"\r\nX-Iris-Session: "+expected+"\r\nX-Iris-SHA256: "+digest+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    ExchangeProtocol.Copied copied=ExchangeProtocol.copyExactly(helper.stream,output,bytes,job);output.flush();
                    if(!copied.sha256.equals(digest))throw new Failure(409,"FILE_CHANGED","导出时文件已改变，不能确认完成");
                    helper.finish();job.check();
                }
            }else{
                Path hostSrc=resolveSharedFile(id,dir,name);
                long bytes=Files.size(hostSrc);
                if(bytes>ExchangeProtocol.MAX_FILE_BYTES)throw new Failure(413,"FILE_TOO_LARGE","单个文件不能超过 4 GiB");
                String digest=sha256File(hostSrc,bytes,job);
                job.check();
                OutputStream output=socket.getOutputStream();
                responseStarted=true;
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+bytes+"\r\nX-Iris-Session: "+expected+"\r\nX-Iris-SHA256: "+digest+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                try(InputStream fileIn=Files.newInputStream(hostSrc,LinkOption.NOFOLLOW_LINKS)){
                    ExchangeProtocol.Copied copied=ExchangeProtocol.copyExactly(fileIn,output,bytes,job);output.flush();
                    if(!copied.sha256.equals(digest))throw new Failure(409,"FILE_CHANGED","导出时文件已改变，不能确认完成");
                }
                job.check();
            }
        }catch(Exception failure){
            // Never append JSON to a response that already advertised raw bytes.
            if(responseStarted)try{socket.close();}catch(IOException ignored){}
            throw failure;
        }finally{
            if(expiry!=null)expiry.cancel(false);
            releaseRecoveryLease(job);
            synchronized(lock){if(active==job)active=null;lock.notifyAll();}
        }
    }

    private static Path resolveSharedPath(int id,String cleanDir,String cleanName)throws Failure {
        StringBuilder guestPath=new StringBuilder("/data/media/0");
        if(!cleanDir.isEmpty())guestPath.append('/').append(cleanDir);
        if(cleanName!=null&&!cleanName.isEmpty())guestPath.append('/').append(cleanName);
        Path resolved=HostAppImporter.safeInstanceDataPath(id,guestPath.toString());
        if(resolved==null)throw new Failure(400,"INVALID_PATH","非法共享存储路径");
        return resolved;
    }

    private static Path resolveSharedFile(int id,String cleanDir,String cleanName)throws Failure {
        Path hostSrc=resolveSharedPath(id,cleanDir,cleanName);
        if(!Files.exists(hostSrc,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(hostSrc)||!Files.isRegularFile(hostSrc,LinkOption.NOFOLLOW_LINKS)){
            throw new Failure(404,"FILE_NOT_FOUND","实例中未找到该文件："+cleanName);
        }
        return hostSrc;
    }

    private JSONObject listDirectChildren(Job job,int id,String expected,String dir)throws Exception {
        job.check();
        Path hostDir=resolveSharedPath(id,dir,null);
        JSONArray dirsJson=new JSONArray(),filesJson=new JSONArray();
        if(!Files.exists(hostDir,LinkOption.NOFOLLOW_LINKS)){
            if(dir.equals(ExchangeProtocol.DIRECTORY)){
                return new JSONObject().put("id",id).put("session",expected).put("dir",dir)
                    .put("directory",dir).put("dirs",dirsJson).put("files",filesJson)
                    .put("maxFileBytes",ExchangeProtocol.MAX_FILE_BYTES);
            }
            throw new Failure(404,"DIR_NOT_FOUND","实例目录不存在："+(dir.isEmpty()?"/sdcard":dir));
        }
        if(Files.isSymbolicLink(hostDir)||!Files.isDirectory(hostDir,LinkOption.NOFOLLOW_LINKS)){
            throw new Failure(400,"NOT_A_DIRECTORY","目标路径不是有效目录");
        }
        ArrayList<String> subdirs=new ArrayList<>();
        ArrayList<JSONObject> entries=new ArrayList<>();
        try(DirectoryStream<Path> ds=Files.newDirectoryStream(hostDir)){
            int scanned=0;
            for(Path entry:ds){
                if(++scanned>2048)break;
                job.check();
                String entryName=entry.getFileName().toString();
                if(entryName.startsWith(".")||Files.isSymbolicLink(entry))continue;
                if(dir.isEmpty()&&"Android".equalsIgnoreCase(entryName))continue;
                if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)){
                    subdirs.add(entryName);
                }else if(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)){
                    try{
                        entries.add(new JSONObject().put("name",entryName)
                            .put("bytes",Files.size(entry))
                            .put("modifiedAt",Files.getLastModifiedTime(entry).toMillis()));
                    }catch(IOException ignored){}
                }
            }
        }
        Collections.sort(subdirs,String.CASE_INSENSITIVE_ORDER);
        Collections.sort(entries,(a,b)->{
            int cmp=Long.compare(b.optLong("modifiedAt",0),a.optLong("modifiedAt",0));
            return cmp!=0?cmp:a.optString("name").compareToIgnoreCase(b.optString("name"));
        });
        int limit=ExchangeProtocol.MAX_LIST_ENTRIES;
        for(int i=0;i<subdirs.size()&&i<limit;i++){
            dirsJson.put(new JSONObject().put("name",subdirs.get(i)));
        }
        int fileBudget=Math.max(0,limit-dirsJson.length());
        for(int i=0;i<entries.size()&&i<fileBudget;i++){
            filesJson.put(entries.get(i));
        }
        return new JSONObject().put("id",id).put("session",expected).put("dir",dir)
            .put("directory",dir.isEmpty()?"/sdcard":dir)
            .put("dirs",dirsJson).put("files",filesJson)
            .put("maxFileBytes",ExchangeProtocol.MAX_FILE_BYTES);
    }

    private static String sha256File(Path src,long expectedLen,Job job)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buf=new byte[ExchangeProtocol.BLOCK_BYTES];
        long total=0;
        try(InputStream in=Files.newInputStream(src,LinkOption.NOFOLLOW_LINKS)){
            int n;
            while((n=in.read(buf))!=-1){
                job.check();
                digest.update(buf,0,n);
                total+=n;
                if(total>expectedLen)throw new Failure(409,"FILE_CHANGED","文件在读取过程中发生变化，请重试");
            }
        }
        if(total!=expectedLen)throw new Failure(409,"FILE_CHANGED","文件大小已变化，请重试");
        byte[] hash=digest.digest();
        char[] hex="0123456789abcdef".toCharArray();
        char[] out=new char[hash.length*2];
        for(int i=0;i<hash.length;i++){out[i*2]=hex[(hash[i]&255)>>>4];out[i*2+1]=hex[hash[i]&15];}
        return new String(out);
    }

    private static Path allocateUniqueExportPath(Path root,String name)throws Failure {
        Path direct=root.resolve(name).normalize();
        if(!direct.startsWith(root))throw new Failure(400,"INVALID_NAME","非法目标文件名");
        if(!Files.exists(direct,LinkOption.NOFOLLOW_LINKS))return direct;
        int dot=name.lastIndexOf('.');
        String stem=(dot>0)?name.substring(0,dot):name;
        String ext=(dot>0)?name.substring(dot):"";
        for(int idx=1;idx<1000;idx++){
            String candName=stem+" ("+idx+")"+ext;
            Path cand=root.resolve(candName).normalize();
            if(cand.startsWith(root)&&!Files.exists(cand,LinkOption.NOFOLLOW_LINKS))return cand;
        }
        Path ts=root.resolve(stem+"_"+System.currentTimeMillis()+ext).normalize();
        if(!ts.startsWith(root)||Files.exists(ts,LinkOption.NOFOLLOW_LINKS))throw new Failure(409,"TARGET_EXISTS","目标文件名冲突，无法写入");
        return ts;
    }

    private JSONObject exportDirectToHost(Job job,int id,String expected,String dir,String name)throws Exception {
        Path hostSrc=resolveSharedFile(id,dir,name);
        long bytes=Files.size(hostSrc);
        if(bytes>ExchangeProtocol.MAX_FILE_BYTES)throw new Failure(413,"FILE_TOO_LARGE","单个文件不能超过 4 GiB");
        Path exportRoot=Paths.get(HOST_EXPORT_ROOT);
        HostAppImporter.ensureDirOwned(exportRoot,1023,1079,0775);
        Path hostDst=allocateUniqueExportPath(exportRoot,name);
        ExchangeProtocol.Copied copied;
        boolean completed=false;
        try(InputStream in=Files.newInputStream(hostSrc,LinkOption.NOFOLLOW_LINKS);
            OutputStream out=Files.newOutputStream(hostDst,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            copied=ExchangeProtocol.copyExactly(in,out,bytes,job);
            out.flush();
            completed=true;
        }finally{
            if(!completed)try{Files.deleteIfExists(hostDst);}catch(IOException ignored){}
        }
        String dstVerifySha=sha256File(hostDst,bytes,job);
        if(!copied.sha256.equals(dstVerifySha)){
            try{Files.deleteIfExists(hostDst);}catch(IOException ignored){}
            throw new Failure(500,"VERIFY_FAILED","宿主机导出文件校验不一致");
        }
        Os.lchown(hostDst.toString(),1023,1079);
        Os.chmod(hostDst.toString(),0664);
        try{HostAppImporter.runHost(10,"/system/bin/restorecon",hostDst.toString());}catch(Exception ignored){}
        String savedName=hostDst.getFileName().toString();
        String hostPublicPath="/sdcard/Download/IrisBoxExport/"+savedName;
        HostAppImporter.scanMediaFileHost(hostPublicPath);
        return new JSONObject().put("id",id).put("session",expected).put("dir",dir)
            .put("name",name).put("savedName",savedName).put("hostPath",hostPublicPath)
            .put("bytes",copied.bytes).put("sha256",copied.sha256);
    }

    /** 停止Guest之前先打断本会话的传输并等待Helper回收，不能只关UI留后台继续写。
     */
    void cancelAndWait(int id,String expected){
        Job job;
        synchronized(lock){job=active;if(job==null||job.id!=id||!job.session.equals(expected))return;}
        job.cancel("SESSION_STOPPING","实例正在停止，文件操作已取消");
        long until=SystemClock.elapsedRealtime()+5000;
        synchronized(lock){while(active==job&&SystemClock.elapsedRealtime()<until){try{lock.wait(200);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}}
        // Stop may proceed after this grace period; killing Guest init also ends its helper.
        // A pending journal is recovered explicitly in the next verified session.
    }
    static void respond(Socket socket,int status,JSONObject value)throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);OutputStream output=socket.getOutputStream();
        output.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
    }
    public void close(){closed=true;Job job;synchronized(lock){job=active;}if(job!=null)job.cancel("MANAGER_STOPPED","文件管理器已停止");deadlines.shutdownNow();}
}
