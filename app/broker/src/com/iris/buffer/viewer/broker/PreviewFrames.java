package com.rootvmos.buffer.viewer.broker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 由Broker按需调用的一次640×360 JPEG小窗采样，单独限时/限大小。
 * 不复用全屏buffer连接；约1秒小窗更新不代表游戏FPS，慢采样不应阻塞状态轮询。
 * One bounded list thumbnail. This is separate from the shared-buffer fullscreen path.
 */
final class PreviewFrames {
    private static final String BASE="/data/rootvmos/runtime",BB="/data/rootvmos/host/bin/busybox";
    static byte[] capture(int id,String session,ScheduledExecutorService deadlines)throws Exception {
        String path="/data/iris_buffer/"+session.replace(':','_')+"/output.sock";
        ProcessBuilder builder=new ProcessBuilder(Arrays.asList(BB,"timeout","8","sh",BASE+"/ctl.sh","exec",Integer.toString(id),"/system/bin/busybox","timeout","6",
            "/system/bin/iris_preview_capture",Integer.toString(id),session,path,"--mode","preview","--seconds","0","--width","640","--height","360"));
        builder.environment().remove("CLASSPATH");builder.environment().put("IRIS_EXPECT_SESSION",session);
        builder.redirectError(ProcessBuilder.Redirect.appendTo(new File(BASE+"/display/buffer_viewer/preview_"+id+".log")));
        Process process=builder.start();process.getOutputStream().close();
        ScheduledFuture<?> expiry=deadlines.schedule(()->{
            process.destroy();try{process.getInputStream().close();}catch(IOException ignored){}
        },9,TimeUnit.SECONDS);
        byte[] jpeg;
        try(InputStream in=process.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[16384];int n;
            while((n=in.read(block))!=-1) {if(out.size()+n>1024*1024)throw new IOException("Preview image exceeds limit");out.write(block,0,n);}
            if(!process.waitFor(1,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Preview capture did not finish");
            jpeg=out.toByteArray();
        }finally{expiry.cancel(false);process.destroy();}
        if(jpeg.length<4||jpeg[0]!=(byte)255||jpeg[1]!=(byte)216)throw new IOException("Expected JPEG preview");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,options);
        if(options.outWidth!=640||options.outHeight!=360)throw new IOException("Preview dimensions outside limit");
        return jpeg;
    }
}
