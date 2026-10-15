package com.rootvmos.buffer.viewer.broker;

import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.rootvmos.buffer.viewer.broker.ExchangeProtocol.Failure;

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
    private static final String BASE="/data/rootvmos/runtime", BB="/data/rootvmos/host/bin/busybox";
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
            if(SystemClock.elapsedRealtime()>=deadline)throw new Failure(408,"TRANSFER_TIMEOUT","文件操作超过 120 秒，请重试");
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
            ArrayList<String> command=new ArrayList<>(Arrays.asList(BB,"timeout","-s","KILL","130","/system/bin/sh",BASE+"/ctl.sh","exec",Integer.toString(job.id),"/system/xbin/su","2000","/system/bin/busybox","env",
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
                .put("bootId",bootId()).put("recoverAfterElapsedMs",job.deadline+145000).put("helperExited",false));
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
        boolean importing=path.equals("/files/import")&&method.equals("POST"),exporting=path.equals("/files/export")&&method.equals("GET"),listing=path.equals("/files")&&method.equals("GET");
        if(!importing&&!exporting&&!listing)throw new Failure(404,"UNKNOWN_ENDPOINT","没有这个文件操作");
        Set<String> allowed=new HashSet<>(Arrays.asList("id","session"));if(!listing)allowed.add("name");
        if(!allowed.equals(query.keySet()))throw new Failure(400,"INVALID_QUERY","文件操作参数不正确");
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
            recover(job);
            if(listing){
                JSONObject result;
                try(Helper helper=new Helper(job,"list")){result=helper.phase("listed");helper.finish();}
                job.check();result.remove("phase");result.put("id",id).put("session",expected).put("directory",ExchangeProtocol.DIRECTORY).put("maxFileBytes",ExchangeProtocol.MAX_FILE_BYTES);
                respond(socket,200,result);
            }else if(importing){
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
            }else{
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
