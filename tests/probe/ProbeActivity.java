package com.irisbox.probe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.opengl.GLES20;
import android.media.*;
import android.text.*;
import android.view.MotionEvent;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** 普通UID验收应用；只写自身文件，Root通过正常Magisk授权。 */
public final class ProbeActivity extends Activity {
    private SharedPreferences prefs;
    private TextView label;
    private GLSurfaceView gpu;
    private String instance,edit="";
    private volatile String network="checking",googleNetwork="checking",dns="checking",root="not requested",audio="not requested",renderer="starting",gpuCheck="starting";
    private volatile long frames;
    private String externalStorage="not checked";
    private int swipes,touches,maxPointers;
    private float firstX,firstY;
    private boolean alive=true;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);prefs=getSharedPreferences("proof",0);
        instance=getIntent().getStringExtra("instance");
        if(instance==null)instance=prefs.getString("instance","unknown");
        prefs.edit().putString("instance",instance).apply();
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(1);outer.setPadding(12,8,12,8);
        LinearLayout row=new LinearLayout(this);outer.addView(row,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=new LinearLayout(this);left.setOrientation(1);row.addView(left,new LinearLayout.LayoutParams(0,-1,1));
        label=new TextView(this);label.setTextSize(15);left.addView(label);
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Keyboard input test");input.setContentDescription("probe-keyboard");left.addView(input);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int before,int count){edit=s.toString();show();}public void afterTextChanged(Editable e){}});
        gpu=new GLSurfaceView(this);gpu.setContentDescription("probe-gesture-gpu");gpu.setEGLContextClientVersion(2);
        gpu.setRenderer(new GLSurfaceView.Renderer(){
            private int program,position,rotation;
            private FloatBuffer vertices;
            private long started,lastReport;
            private int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] status=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,status,0);if(status[0]!=1)throw new RuntimeException(GLES20.glGetShaderInfoLog(s));return s;}
            public void onSurfaceCreated(GL10 unused,EGLConfig config){
                renderer=GLES20.glGetString(GLES20.GL_RENDERER);
                int vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p;uniform float a;void main(){float c=cos(a),s=sin(a);gl_Position=vec4(c*p.x-s*p.y,s*p.x+c*p.y,0.,1.);}");
                int fs=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;void main(){gl_FragColor=vec4(.9,.2,.1,1.);}");
                program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
                int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]!=1)throw new RuntimeException("GL link failed");
                position=GLES20.glGetAttribLocation(program,"p");rotation=GLES20.glGetUniformLocation(program,"a");
                vertices=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{0,.75f,-.7f,-.55f,.7f,-.55f}).position(0);started=System.nanoTime();
            }
            public void onSurfaceChanged(GL10 unused,int w,int h){GLES20.glViewport(0,0,w,h);}
            public void onDrawFrame(GL10 unused){
                GLES20.glClearColor(.05f,.15f,.45f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glUseProgram(program);
                GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
                GLES20.glUniform1f(rotation,(System.nanoTime()-started)/1e9f);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);frames++;
                long now=System.nanoTime();
                if(now-lastReport>1000000000L){
                    ByteBuffer pixel=ByteBuffer.allocateDirect(4);GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                    int error=GLES20.glGetError();gpuCheck=error==0&&(pixel.get(2)&255)>80?"PASS pixels + draw":"FAIL GL="+error;
                    lastReport=now;runOnUiThread(ProbeActivity.this::show);
                }
            }
        });
        gpu.setOnTouchListener((v,e)->{maxPointers=Math.max(maxPointers,e.getPointerCount());if(e.getActionMasked()==MotionEvent.ACTION_DOWN){touches++;firstX=e.getX();firstY=e.getY();}if(e.getActionMasked()==MotionEvent.ACTION_UP){if(Math.hypot(e.getX()-firstX,e.getY()-firstY)>25)swipes++;show();}return true;});
        row.addView(gpu,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout buttons=new LinearLayout(this);outer.addView(buttons);
        button(buttons,"COUNT +1",()->{prefs.edit().putInt("counter",prefs.getInt("counter",0)+1).commit();show();});
        button(buttons,"REQUEST ROOT",this::requestRoot);button(buttons,"AUDIO TEST",this::testAudio);
        setContentView(outer);
        // Exercise Android's ordinary app-directory path through vold/FUSE.
        // This detects a missing User namespace project-ID map, not just DAC.
        try{
            File dir=getExternalFilesDir("irisbox-storage-check");
            if(dir==null)throw new IOException("external directory unavailable");
            File sample=File.createTempFile("roundtrip-",".tmp",dir);
            try{
                byte[] expected="IrisBox scoped storage".getBytes("UTF-8");
                try(FileOutputStream out=new FileOutputStream(sample)){out.write(expected);out.getFD().sync();}
                byte[] actual=new byte[expected.length];
                try(FileInputStream in=new FileInputStream(sample)){if(in.read(actual)!=actual.length||!java.util.Arrays.equals(expected,actual))throw new IOException("readback mismatch");}
                externalStorage="PASS write + read";
            }finally{if(!sample.delete())throw new IOException("temporary file cleanup failed");}
        }catch(Exception failure){externalStorage=failure.getClass().getSimpleName()+": "+failure.getMessage();}
        show();
        new Thread(()->{network=http("https://example.com/");googleNetwork=http("https://www.gstatic.com/generate_204");try{dns="PASS "+InetAddress.getAllByName("accounts.google.com").length+" addresses";}catch(Exception e){dns=e.getClass().getSimpleName();}runOnUiThread(this::show);},"ordinary-uid-network").start();
        if(getIntent().getBooleanExtra("root",false))requestRoot();
        if(getIntent().getBooleanExtra("audio",false))testAudio();
    }
    private String http(String address){
        HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);return "HTTP "+c.getResponseCode();}
        catch(Exception e){return e.getClass().getSimpleName()+": "+e.getMessage();}finally{if(c!=null)c.disconnect();}
    }
    private void button(LinearLayout row,String text,Runnable run){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setOnClickListener(v->run.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    private void requestRoot(){
        root="waiting for Magisk authorization";show();
        new Thread(()->{Process p=null;try{p=new ProcessBuilder("/sbin/su","-c","id").redirectErrorStream(true).start();if(!p.waitFor(45,TimeUnit.SECONDS)){p.destroy();root="authorization timeout";}else{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] data=new byte[1024];int n;while((n=p.getInputStream().read(data))!=-1&&out.size()<4096)out.write(data,0,n);root="exit="+p.exitValue()+" "+out.toString("UTF-8").trim();}}catch(Exception e){root=e.getClass().getSimpleName()+": "+e.getMessage();}finally{if(p!=null)p.destroy();runOnUiThread(this::show);}},"ordinary-app-su").start();
    }
    private void testAudio(){
        new Thread(()->{AudioTrack track=null;try{int rate=22050;short[] samples=new short[rate/3];for(int i=0;i<samples.length;i++)samples[i]=(short)(Math.sin(2*Math.PI*440*i/rate)*1800);track=new AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,samples.length*2,AudioTrack.MODE_STATIC);int n=track.write(samples,0,samples.length);track.play();Thread.sleep(600);audio="write="+n+" head="+track.getPlaybackHeadPosition();}catch(Exception e){audio=e.getClass().getSimpleName();}finally{if(track!=null)track.release();runOnUiThread(this::show);}},"audio-proof").start();
    }
    private void show(){
        if(!alive)return;int count=prefs.getInt("counter",0);
        label.setText("Instance "+instance+" | UID "+android.os.Process.myUid()+" | Counter "+count+"\nHTTPS "+network+"\nGoogle "+googleNetwork+" | DNS "+dns+"\nRoot "+root+"\nGPU "+renderer+"\nFrames "+frames+" "+gpuCheck+"\nTouches "+touches+" swipes "+swipes+" pointers "+maxPointers+"\nAudio "+audio+"\nStorage "+externalStorage);
        try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"proof.json"))){JSONObject j=new JSONObject().put("instance",instance).put("uid",android.os.Process.myUid()).put("counter",count).put("network",network).put("googleNetwork",googleNetwork).put("dns",dns).put("root",root).put("renderer",renderer).put("frames",frames).put("gpuCheck",gpuCheck).put("touches",touches).put("swipes",swipes).put("maxPointers",maxPointers).put("edit",edit).put("audio",audio).put("externalStorage",externalStorage);out.write(j.toString().getBytes("UTF-8"));out.getFD().sync();}catch(Exception e){throw new RuntimeException(e);}
    }
    @Override protected void onPause(){gpu.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();gpu.onResume();}
    @Override protected void onDestroy(){alive=false;super.onDestroy();}

    /** 本地生成H.264片段，再通过系统MediaPlayer/VideoView解码；无需外部下载。 */
    public static final class VideoActivity extends Activity {
        private VideoView view;private TextView info;private android.os.Handler ui=new android.os.Handler();
        private boolean rendered,closed;private String error="";private int duration,width,height;
        private final Runnable update=()->report();
        @Override public void onCreate(Bundle b){
            super.onCreate(b);LinearLayout root=new LinearLayout(this);root.setOrientation(1);
            info=new TextView(this);info.setText("Generating local H.264 clip...");root.addView(info);
            view=new VideoView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
            view.setOnPreparedListener(player->{duration=player.getDuration();width=player.getVideoWidth();height=player.getVideoHeight();player.setLooping(true);view.start();});
            view.setOnInfoListener((player,what,extra)->{if(what==3)rendered=true;return false;});
            view.setOnErrorListener((player,what,extra)->{error="decoder "+what+":"+extra;report();return true;});
            new Thread(()->{try{File clip=new File(getFilesDir(),"test-video.mp4");encode(clip);runOnUiThread(()->{try{view.setVideoURI(android.net.Uri.fromFile(clip));}catch(Exception failure){error=failure.toString();}ui.post(update);});}catch(Exception failure){error=failure.toString();runOnUiThread(this::report);}},"video-encode-proof").start();
        }
        private static void encode(File file)throws Exception{
            final int w=640,h=360,fps=24,total=72;
            MediaCodec codec=MediaCodec.createEncoderByType("video/avc");MediaMuxer muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean started=false;int track=-1,frame=0;boolean inputDone=false,outputDone=false;
            try{
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",w,h);format.setInteger(MediaFormat.KEY_COLOR_FORMAT,19);format.setInteger(MediaFormat.KEY_BIT_RATE,600000);format.setInteger(MediaFormat.KEY_FRAME_RATE,fps);format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
                MediaCodec.BufferInfo meta=new MediaCodec.BufferInfo();long end=android.os.SystemClock.elapsedRealtime()+20000;
                while(!outputDone&&android.os.SystemClock.elapsedRealtime()<end){
                    if(!inputDone){int slot=codec.dequeueInputBuffer(10000);if(slot>=0){if(frame==total){codec.queueInputBuffer(slot,0,0,frame*1000000L/fps,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{java.nio.ByteBuffer buffer=codec.getInputBuffer(slot);buffer.clear();for(int y=0;y<h;y++)for(int x=0;x<w;x++)buffer.put((byte)(16+(x+frame*7)%210));for(int n=0;n<w*h/4;n++)buffer.put((byte)(80+frame%40));for(int n=0;n<w*h/4;n++)buffer.put((byte)(180-frame%40));codec.queueInputBuffer(slot,0,w*h*3/2,frame*1000000L/fps,0);frame++;}}}
                    int slot=codec.dequeueOutputBuffer(meta,10000);
                    if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(codec.getOutputFormat());muxer.start();started=true;}
                    else if(slot>=0){java.nio.ByteBuffer encoded=codec.getOutputBuffer(slot);if((meta.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&meta.size>0){if(!started)throw new IOException("muxer not started");encoded.position(meta.offset);encoded.limit(meta.offset+meta.size);muxer.writeSampleData(track,encoded,meta);}outputDone=(meta.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(slot,false);}
                }
                if(!outputDone)throw new IOException("encoder timeout");
            }finally{try{codec.stop();}catch(Exception ignored){}codec.release();if(started)muxer.stop();muxer.release();}
        }
        private void report(){
            if(closed)return;
            int position=0;try{position=view.getCurrentPosition();}catch(Exception ignored){}
            info.setText("H.264 video: "+width+"x"+height+" duration="+duration+" position="+position+" rendered="+rendered+" "+error);
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"video-proof.json"))){out.write(new JSONObject().put("uid",android.os.Process.myUid()).put("width",width).put("height",height).put("durationMs",duration).put("positionMs",position).put("rendered",rendered).put("error",error).toString().getBytes("UTF-8"));}catch(Exception failure){throw new RuntimeException(failure);}
            ui.removeCallbacks(update);ui.postDelayed(update,1000);
        }
        @Override protected void onDestroy(){closed=true;ui.removeCallbacks(update);view.stopPlayback();super.onDestroy();}
    }
}
