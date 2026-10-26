package com.irisbox.viewer;

import java.io.IOException;

/** Bounded retry for automatic directory GETs only; never used for an upload. */
final class DirectoryRefresh {
    static final long WINDOW_MS=5000, BACKOFF_MS=150;
    interface Clock { long now(); }
    interface Guard { void check()throws IOException; }
    interface Sleeper { void pause(long milliseconds)throws InterruptedException; }
    interface Get<T> { T get(long deadline)throws IOException; }
    static final class Failure extends IOException {
        final int status;final String code;
        Failure(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
    static <T>T get(Get<T> request,Guard guard,Clock clock,Sleeper sleeper,Runnable waiting)throws IOException {
        long deadline=0;
        while(true){
            guard.check();
            if(deadline!=0&&clock.now()>=deadline)throw new IOException("上一项传输尚未结束，请稍后刷新目录");
            try{
                T result=request.get(deadline);guard.check();
                if(deadline!=0&&clock.now()>deadline)throw new IOException("等待目录收尾已超过五秒，请稍后刷新");
                return result;
            }catch(Failure error){
                if(error.status!=423||!"TRANSFER_BUSY".equals(error.code))throw error;
                if(deadline==0){deadline=clock.now()+WINDOW_MS;waiting.run();}
                long remaining=deadline-clock.now();
                if(remaining<=0)throw error;
                try{sleeper.pause(Math.min(BACKOFF_MS,remaining));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("目录刷新已取消",interrupted);}
            }
        }
    }
}
