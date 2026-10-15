package com.rootvmos.viewer;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Host输入连接的唯一写入者；UI只入队，worker严格递增sequence并等同序号ACK。
 * 没有图形字节；与GuestInputMain/InputProtocol配套，空闲发P心跳，关闭发C取消按下状态。
 * ACK表示命令处理，不代表游戏响应耗时或网络游戏帧率；断连后停止接受新输入。
 * One authenticated input socket, one sequence owner, no graphics bytes.
 */
final class InputLink {
    private final ParcelFileDescriptor owner;
    private final FileDescriptor fd;
    private final InputQueue queue=new InputQueue();
    private final Thread worker;
    private volatile boolean closing,closed,healthy=true;
    private long sequence;
    private final Runnable failed;
    InputLink(FileDescriptor source,Runnable failed) throws Exception {
        this.failed=failed;owner=ParcelFileDescriptor.dup(source);fd=owner.getFileDescriptor();
        try {
            if(!DisplayActivity.nativeInputTypeIsStream(owner.getFd())) throw new IOException("Expected input STREAM socket");
            Os.setsockoptTimeval(fd,OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,StructTimeval.fromMillis(900));
            Os.setsockoptTimeval(fd,OsConstants.SOL_SOCKET,OsConstants.SO_SNDTIMEO,StructTimeval.fromMillis(900));
        } catch(Exception error) { owner.close();throw error; }
        worker=new Thread(this::run,"iris-buffer-input");worker.setDaemon(true);worker.start();
    }
    boolean healthy() { return healthy&&!closing&&!closed; }
    boolean offer(InputQueue.Command command) {
        synchronized(queue) { if(!healthy()) return false;boolean accepted=queue.offer(command);queue.notifyAll();return accepted; }
    }
    private void run() {
        try {
            exchange(InputQueue.Command.simple('C'));
            for(;;) {
                InputQueue.Command command;boolean finalCancel;
                synchronized(queue) {
                    if(!closing && queue.size()==0) queue.wait(800);
                    finalCancel=closing;
                    if(finalCancel) { queue.clear();command=InputQueue.Command.simple('C'); }
                    else { command=queue.poll();if(command==null) command=InputQueue.Command.simple('P'); }
                }
                exchange(command);
                if(finalCancel) break;
                Thread.sleep(8); // At most 125 requests/s, below Guest's 500/s bound.
            }
        } catch(Exception error) {
            healthy=false;
            if(!closing) { Log.e("IrisBufferViewer","IRIS_INPUT_ERROR "+error.getClass().getSimpleName());failed.run(); }
        } finally {
            healthy=false;closed=true;
            try { owner.close(); } catch(Exception ignored) { }
            Log.i("IrisBufferViewer","IRIS_INPUT_CLOSED sequence="+sequence);
        }
    }
    /** 序号由这一个worker分配；逐字节处理STREAM边界，ACK不可多尾字节或错序。
     * ACK超时视为连接失效，不能继续猜Guest收到了哪些按键。
     */
    private void exchange(InputQueue.Command command) throws Exception {
        long seq=++sequence,deadline=SystemClock.elapsedRealtime()+1400;
        byte[] request=command.line(seq).getBytes(StandardCharsets.US_ASCII);int offset=0;
        while(offset<request.length) { if(SystemClock.elapsedRealtime()>deadline) throw new IOException("Input send deadline");int n=Os.write(fd,request,offset,request.length-offset);if(n<=0)throw new IOException("Input closed");offset+=n; }
        byte[] response=new byte[128];int count=0;
        for(;;) {
            if(SystemClock.elapsedRealtime()>deadline || count==response.length) throw new IOException("Input ACK deadline or length");
            int n=Os.read(fd,response,count,response.length-count);if(n<=0)throw new IOException("Input EOF");
            int end=count+n;boolean done=false;
            for(int i=count;i<end;i++) {
                int value=response[i]&255;
                if(value=='\n') { if(i!=end-1)throw new IOException("Trailing input ACK bytes");done=true; }
                else if(value<32||value>126)throw new IOException("Input ACK character");
            }
            count=end;if(done) break;
        }
        String ack=new String(response,0,count-1,StandardCharsets.US_ASCII);
        if(!ack.matches("ACK "+seq+" [0-9]{1,16}")) throw new IOException("Input ACK sequence mismatch");
        if(command.type!='P' && command.type!='M') Log.i("IrisBufferViewer","IRIS_INPUT_ACK type="+command.type+" sequence="+seq);
    }
    /** Must finish BEFORE nativeStop(), which exits the entire display process. */
    void closeAndRelease() {
        synchronized(queue) { closing=true;queue.clear();queue.notifyAll(); }
        try { worker.join(1800); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if(worker.isAlive()) {
            try { Os.shutdown(fd,OsConstants.SHUT_RDWR); } catch(Exception ignored) { }
            try { owner.close(); } catch(Exception ignored) { }
            try { worker.join(200); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        healthy=false;
    }
}
