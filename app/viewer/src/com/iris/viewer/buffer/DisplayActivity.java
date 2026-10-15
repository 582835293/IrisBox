package com.rootvmos.viewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host独立:display进程，每次进入固定Guest会话，用新进程隔开图形导入缓存。
 * Java管Surface生命周期/键鼠，native buffer_viewer.cpp管导入、事务、fence和ACK。
 * 先从gateway接两FD并启动输入心跳，再启动native；关闭先释放输入再退出显示进程。
 * 返回列表不发Guest stop，不重置账号/游戏；停止实例是另一条管理动作。
 * Each fixed Guest connection gets a fresh :display process and GPU cache.
 */
public final class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    @Override protected void attachBaseContext(android.content.Context base) {super.attachBaseContext(I18n.wrap(base));}

    static { System.loadLibrary("irisbufferviewer"); }
    private static native long nativeStart(Surface surface,int fd,int id,int pid,long tick,int seconds,int width,int height);
    private static native String nativeStats(long handle);
    private static native long[] nativeGeometry(long handle);
    private static native void nativeResize(long handle,int width,int height);
    private static native void nativeStop(long handle);
    static native boolean nativeInputTypeIsStream(int fd);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService hardDeadline=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closing=new AtomicBoolean();
    private volatile long handle;
    private volatile InputLink input;
    private volatile LocalSocket gateway;
    private volatile boolean resumed,surfaceReady;
    private boolean attempted,down,cancelled,geometryLogged;
    private String gatewayName="",gatewayToken="",session="";
    private int id,number,pid,seconds,inputWidth,inputHeight;
    private long tick,displayStartedAt;
    private long pointerWidth,pointerHeight,pointerRotation=-1;
    private SurfaceView surface;
    private TextView status;
    private Button guestHome,guestBack,pointerMode;
    private volatile boolean touchMode;
    private final HashMap<Integer,Integer> keyboardDevices=new HashMap<>();
    private InputManager devices;
    private final InputManager.InputDeviceListener deviceListener=new InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int device) { }
        public void onInputDeviceChanged(int device) {if(keyboardDevices.containsValue(device))cancelKeyboard();}
        public void onInputDeviceRemoved(int device) {if(keyboardDevices.containsValue(device))cancelKeyboard();}
    };
    private final Runnable update=new Runnable() { public void run() {
        if(!resumed||closing.get())return;
        long current=handle;InputLink link=input;
        if(current!=0) {
            nativeStats(current); // Preserve five-second diagnostic logs without cluttering the UI.
            long[] g=nativeGeometry(current);
            String problem=PointerGeometry.rejection(g,surface.getWidth(),surface.getHeight(),inputWidth,inputHeight);
            boolean ready=problem.isEmpty()&&link!=null&&link.healthy();
            if(ready)followGeometry(g);else if(down)cancelPointer();
            guestHome.setEnabled(ready);guestBack.setEnabled(ready);
            pointerMode.setEnabled(ready);
            long left=Math.max(0,seconds-(SystemClock.elapsedRealtime()-displayStartedAt)/1000);
            String remaining=String.format(java.util.Locale.US,"%d:%02d",left/60,left%60);
            status.setText(InstanceNames.title(number)+" · "+(ready?(touchMode?I18n.s(R.string.ui_3d4cf10d60ad):I18n.s(R.string.ui_8bf7092bfac6)):problem.isEmpty()?I18n.s(R.string.ui_7570be66f8ac):I18n.known(problem))+(seconds>0?I18n.s(R.string.ui_a005d2b0e33d)+remaining+(left<=20?I18n.s(R.string.ui_43d8ec69b3ed):""):""));
            if(!geometryLogged&&ready) {
                int[] position=new int[2];surface.getLocationOnScreen(position);
                Log.i("IrisBufferViewer","IRIS_BUFFER_VISIBLE_GEOMETRY id="+id+" session="+session+" surfaceScreen="+Arrays.toString(position)+" geometry="+Arrays.toString(g));
                geometryLogged=true;
            }
        }
        ui.postDelayed(this,250);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); I18n.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        LinearLayout tools=new LinearLayout(this);tools.setBackgroundColor(Color.rgb(24,28,34));
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(14);status.setGravity(17);
        tools.addView(status,new LinearLayout.LayoutParams(0,-1,1));
        pointerMode=button(I18n.s(R.string.ui_eaa5b90e3aa4),"pointer-mode",this::togglePointerMode);tools.addView(pointerMode);pointerMode.setEnabled(false);
        guestHome=button(I18n.s(R.string.ui_428cbfb8a4e1),"guest-home",()->key("HOME"));tools.addView(guestHome);
        guestBack=button(I18n.s(R.string.ui_870379d0d76d),"guest-back",()->key("BACK"));tools.addView(guestBack);
        tools.addView(button(I18n.s(R.string.ui_53505b1fcd1a),"return-list",this::returnToList));
        guestHome.setEnabled(false);guestBack.setEnabled(false);
        root.addView(tools,new LinearLayout.LayoutParams(-1,dp(48)));
        surface=new SurfaceView(this);surface.setContentDescription("guest-display");surface.getHolder().addCallback(this);
        surface.setFocusable(true);surface.setFocusableInTouchMode(true);
        surface.setOnTouchListener((view,event)->pointer(event));surface.setOnGenericMotionListener((view,event)->pointer(event));
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        surface.requestFocus();devices=(InputManager)getSystemService(INPUT_SERVICE);devices.registerInputDeviceListener(deviceListener,ui);
        load(getIntent());
    }
    private Button button(String label,String description,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setContentDescription(description);b.setOnClickListener(v->action.run());return b;
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void load(Intent intent) {
        gatewayName=intent.getStringExtra("gatewayName");gatewayToken=intent.getStringExtra("gatewayToken");session=intent.getStringExtra("session");
        // Normal entry has no expiry; explicit diagnostic sessions remain bounded.
        id=intent.getIntExtra("instanceId",0);number=intent.getIntExtra("instanceNumber",0);seconds=intent.getIntExtra("seconds",0);
        touchMode=getSharedPreferences("pointer_modes",MODE_PRIVATE).getBoolean("touch_"+id,false);
        pointerMode.setText(touchMode?I18n.s(R.string.ui_b69bd19456b6):I18n.s(R.string.ui_eaa5b90e3aa4));
        inputWidth=intent.getIntExtra("inputWidth",1920);inputHeight=intent.getIntExtra("inputHeight",1080);
        if(gatewayName==null||!gatewayName.matches("[A-Za-z0-9_.-]{1,90}")||gatewayToken==null||!gatewayToken.matches("[a-f0-9]{64}")||session==null||!session.matches("[1-9][0-9]*:[1-9][0-9]*")||(id<7||id>65535)||!InstanceNames.valid(number)||seconds<0||seconds>900||inputWidth<1||inputWidth>4096||inputHeight<1||inputHeight>4096) {
            gatewayName="";status.setText(I18n.s(R.string.ui_28c63e19176b));return;
        }
        try { String[] identity=session.split(":");pid=Integer.parseInt(identity[0]);tick=Long.parseLong(identity[1]); }
        catch(Exception error){gatewayName="";status.setText(I18n.s(R.string.ui_e4377db610c0));return;}
        status.setText(InstanceNames.title(number)+I18n.s(R.string.ui_637d53cd9da4));
        surface.setContentDescription(InstanceNames.description("guest-display",number));
        guestHome.setContentDescription(InstanceNames.description("guest-home",number));
        guestBack.setContentDescription(InstanceNames.description("guest-back",number));
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent);stopDisplay(); }
    @Override protected void onResume(){super.onResume();resumed=true;ui.post(update);scheduleConnect();}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(update);stopDisplay();super.onPause();}
    @Override protected void onDestroy(){if(devices!=null)devices.unregisterInputDeviceListener(deviceListener);stopDisplay();super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);if(!focused){cancelKeyboard();if(down)cancelPointer();}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code=event.getKeyCode(),action=event.getAction();
        if(!event.isFromSource(InputDevice.SOURCE_KEYBOARD)||!keyboardCode(code))return super.dispatchKeyEvent(event);
        // Consume typing before focused toolbar Buttons can interpret Space/Enter.
        if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return true;
        InputLink link=input;
        if(!resumed||!hasWindowFocus()||closing.get()||link==null||!link.healthy()) {cancelKeyboard();return true;}
        Integer owner=keyboardDevices.get(code);
        if(action==KeyEvent.ACTION_DOWN) {
            if(owner==null) {
                if(event.getRepeatCount()!=0)return true;
                keyboardDevices.put(code,event.getDeviceId());
            }else if(owner!=event.getDeviceId()||event.getRepeatCount()==0)return true;
        }else if(owner==null||owner!=event.getDeviceId())return true;
        int repeat=action==KeyEvent.ACTION_UP?0:Math.min(65535,event.getRepeatCount());
        int meta=KeyEvent.normalizeMetaState(event.getMetaState())&0x007770ff;
        if(!link.offer(InputQueue.Command.keyboard(action,code,repeat,meta)))keyboardDevices.clear();
        else if(action==KeyEvent.ACTION_UP)keyboardDevices.remove(code);
        return true;
    }
    private static boolean keyboardCode(int code) {
        return code==4 || (code>=7&&code<=23) || (code>=29&&code<=63) || (code>=66&&code<=77) ||
                code==81||code==82||code==92||code==93||(code>=111&&code<=124)||
                (code>=131&&code<=163)||(code>=211&&code<=218)||code==259||code==260;
    }
    private void cancelKeyboard() {
        keyboardDevices.clear();InputLink link=input;if(link!=null)link.offer(InputQueue.Command.simple('R'));
    }
    @Override public void onBackPressed(){returnToList();}
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;Canvas c=null;try{c=holder.lockCanvas();if(c!=null)c.drawColor(Color.BLACK);}catch(Exception ignored){}finally{if(c!=null)try{holder.unlockCanvasAndPost(c);}catch(Exception ignored){}}scheduleConnect();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){surfaceReady=true;long current=handle;if(current!=0){cancelPointer();nativeResize(current,width,height);}scheduleConnect();}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;stopDisplay();}
    private void scheduleConnect(){surface.postDelayed(()->{if(resumed&&surfaceReady&&surface.getWidth()>0&&surface.getHeight()>0)connect();},160);}
    /** 只在Surface就绪且页面前台时接一次固定会话；握手同时核id/session/token及两FD数量。
     */
    private void connect() {
        if(attempted||closing.get()||!resumed||!surfaceReady||gatewayName.isEmpty())return;
        attempted=true;displayStartedAt=SystemClock.elapsedRealtime();final Surface target=surface.getHolder().getSurface();final int width=surface.getWidth(),height=surface.getHeight();
        if(seconds>0){
            hardDeadline.schedule(()->{Log.e("IrisBufferViewer","IRIS_BUFFER_ERROR independent process deadline id="+id);android.os.Process.killProcess(android.os.Process.myPid());},seconds+7L,TimeUnit.SECONDS);
            Log.i("IrisBufferViewer","IRIS_BUFFER_DEADLINE_ARMED id="+id+" session="+session+" seconds="+(seconds+7));
        }else Log.i("IrisBufferViewer","IRIS_BUFFER_NO_EXPIRY id="+id+" session="+session);
        control.execute(()->{
            ArrayList<FileDescriptor> passed=new ArrayList<>();
            try(LocalSocket link=new LocalSocket()) {
                if(closing.get())return;
                gateway=link;link.connect(new LocalSocketAddress(gatewayName,LocalSocketAddress.Namespace.ABSTRACT));link.setSoTimeout(3000);
                link.getOutputStream().write(("HELLO 2 "+id+" "+session+" "+gatewayToken+"\n").getBytes(StandardCharsets.US_ASCII));link.getOutputStream().flush();
                if(!line(link,passed).equals("OK 2 "+id+" "+session)||passed.size()!=2)throw new IOException("Gateway identity or two-FD response mismatch");
                if(closing.get())throw new IOException("Display cancelled");
                // Start heartbeat immediately, before nativeStart can block waiting for SurfaceFlinger.
                input=new InputLink(passed.get(1),()->ui.post(this::returnToList));
                if(!input.offer(InputQueue.Command.touchMode(touchMode)))throw new IOException("Pointer mode queue rejected");
                try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(passed.get(0))) {handle=nativeStart(target,copy.getFd(),id,pid,tick,seconds,width,height);}
                if(handle==0)throw new IOException("Native display failed");
                link.getOutputStream().write("TAKEN\n".getBytes(StandardCharsets.US_ASCII));link.getOutputStream().flush();
                Log.i("IrisBufferViewer","IRIS_BUFFER_CONNECTED id="+id+" session="+session+" hostPid="+android.os.Process.myPid());
            } catch(Exception error) {
                Log.e("IrisBufferViewer","IRIS_BUFFER_ERROR connection "+error.getClass().getSimpleName());
                ui.post(()->{status.setText(I18n.s(R.string.ui_2c23c90e1f3a));returnToList();});
            } finally {
                gateway=null;
                for(FileDescriptor fd:passed)try{android.system.Os.close(fd);}catch(Exception ignored){}
            }
        });
    }
    private void returnToList() {
        if(!closing.get())startActivity(new Intent(this,ListActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        stopDisplay();finish();
    }
    /** 先让InputLink释放键鼠，再nativeStop；native会退出本display进程，顺序颠倒会留按下状态。
     */
    private void stopDisplay() {
        if(!closing.compareAndSet(false,true))return;
        ui.removeCallbacks(update);down=false;cancelled=true;keyboardDevices.clear();
        LocalSocket pending=gateway;if(pending!=null)try{pending.close();}catch(Exception ignored){}
        hardDeadline.schedule(()->android.os.Process.killProcess(android.os.Process.myPid()),6,TimeUnit.SECONDS);
        control.execute(()->{
            InputLink link=input;input=null;if(link!=null)link.closeAndRelease();
            Log.i("IrisBufferViewer","IRIS_INPUT_RELEASE_BEFORE_NATIVE_STOP id="+id+" session="+session);
            long current=handle;handle=0;if(current!=0)nativeStop(current);
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
    private long[] geometry() { long current=handle;return current==0?null:nativeGeometry(current); }
    private boolean inputReady(long[] g) { return !closing.get()&&input!=null&&input.healthy()&&PointerGeometry.rejection(g,surface.getWidth(),surface.getHeight(),inputWidth,inputHeight).isEmpty(); }
    private void followGeometry(long[] g) {
        if(pointerWidth!=g[10]||pointerHeight!=g[11]||pointerRotation!=g[18]) {
            if(pointerRotation>=0)cancelPointer();
            pointerWidth=g[10];pointerHeight=g[11];pointerRotation=g[18];geometryLogged=false;
        }
    }
    private void togglePointerMode() {
        InputLink link=input;if(!inputReady(geometry())||link==null)return;
        boolean next=!touchMode;
        if(!link.offer(InputQueue.Command.touchMode(next))) {cancelPointer();return;}
        down=false;cancelled=true;touchMode=next;
        getSharedPreferences("pointer_modes",MODE_PRIVATE).edit().putBoolean("touch_"+id,next).apply();
        pointerMode.setText(next?I18n.s(R.string.ui_b69bd19456b6):I18n.s(R.string.ui_eaa5b90e3aa4));surface.requestFocus();
    }
    private void key(String name) {
        if(!inputReady(geometry()))return;down=false;cancelled=true;
        keyboardDevices.clear();
        input.offer(new InputQueue.Command('K',0,0,0,0,0,name));
    }
    private void cancelPointer() { down=false;cancelled=true;InputLink link=input;if(link!=null)link.offer(InputQueue.Command.simple('U')); }
    /** 使用已完成画面的矩形/逻辑尺寸，拒绝黑边；命令携带同帧几何，Guest再核旋转。
     */
    private boolean pointer(MotionEvent event) {
        int action=event.getActionMasked();boolean mouse=event.isFromSource(InputDevice.SOURCE_MOUSE);
        if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_OUTSIDE||action==MotionEvent.ACTION_HOVER_EXIT){if(down)cancelPointer();return true;}
        long[] g=geometry();if(!inputReady(g)){if(down)cancelPointer();return true;}
        followGeometry(g);
        float[] point=PointerGeometry.map(g,surface.getWidth(),surface.getHeight(),inputWidth,inputHeight,event.getX(),event.getY());
        if(point==null){if(down)cancelPointer();return true;}
        if(mouse&&action==MotionEvent.ACTION_BUTTON_PRESS&&event.getActionButton()==MotionEvent.BUTTON_SECONDARY){key("BACK");return true;}
        if(action==MotionEvent.ACTION_DOWN||(action==MotionEvent.ACTION_BUTTON_PRESS&&event.getActionButton()==MotionEvent.BUTTON_PRIMARY))cancelled=false;
        int buttons=mouse?(event.getButtonState()&MotionEvent.BUTTON_PRIMARY):(action==MotionEvent.ACTION_UP?0:1);
        if(action==MotionEvent.ACTION_UP)buttons=0;
        if(cancelled&&buttons!=0)return true;
        if(buttons==0)cancelled=false;
        InputQueue.Command command;
        if(action==MotionEvent.ACTION_SCROLL)command=new InputQueue.Command('W',point[0],point[1],0,clamp(event.getAxisValue(MotionEvent.AXIS_HSCROLL)),clamp(event.getAxisValue(MotionEvent.AXIS_VSCROLL)),null);
        else if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_HOVER_MOVE||action==MotionEvent.ACTION_HOVER_ENTER||action==MotionEvent.ACTION_BUTTON_PRESS||action==MotionEvent.ACTION_BUTTON_RELEASE)command=new InputQueue.Command('M',point[0],point[1],buttons,0,0,null,action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_HOVER_MOVE);
        else return true;
        command.frameWidth=(int)g[10];command.frameHeight=(int)g[11];command.rotation=(int)g[18];
        down=buttons!=0;if(!input.offer(command)){down=false;cancelled=true;keyboardDevices.clear();}return true;
    }
    private static float clamp(float value){return Math.max(-32,Math.min(32,value));}
    private static String line(LocalSocket socket,ArrayList<FileDescriptor> passed)throws IOException {
        InputStream input=socket.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int n=0;n<160;n++) {
            int value=input.read();FileDescriptor[] descriptors=socket.getAncillaryFileDescriptors();if(descriptors!=null)for(FileDescriptor fd:descriptors)passed.add(fd);
            if(passed.size()>2)throw new IOException("Gateway passed excess descriptors");
            if(value<0)throw new IOException("Gateway EOF");if(value=='\n')return new String(out.toByteArray(),StandardCharsets.US_ASCII);if(value<32||value>126)throw new IOException("Gateway reply character");out.write(value);
        }throw new IOException("Gateway reply too long");
    }
}
