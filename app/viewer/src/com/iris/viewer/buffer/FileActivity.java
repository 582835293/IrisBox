package com.rootvmos.viewer;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host文件页：导入用ACTION_OPEN_DOCUMENT，导出用ACTION_CREATE_DOCUMENT取得本人授权的URI。
 * 只把名称/大小和字节流送Broker，不把Host URI当Guest路径；操作绑id/session和页面epoch。
 * 工作线程读写/校验，UI线程显示进度；失败保存与取消的结果要和“文件确实发布”分开。
 * User-granted documents only; the broker owns the fixed Guest exchange directory.
 */
public final class FileActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {super.attachBaseContext(I18n.wrap(base));}

    private static final int PICK_IMPORT=51,PICK_EXPORT=52;
    private static final long MAX_BYTES=TransferIO.MAX_BYTES,DEADLINE_MS=120000;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService closer=Executors.newCachedThreadPool();
    private final AtomicBoolean deleting=new AtomicBoolean();
    private final Set<HttpURLConnection> connections=Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection,Boolean>());
    private ScheduledExecutorService monitor;
    private volatile boolean resumed,destroyed,sessionValid;
    private volatile Operation operation;
    private int id,number;
    private volatile int epoch;
    private String session,token,base,selectedName="";
    private long selectedBytes=-1;
    private Uri pendingUri;
    private boolean pendingImport,loading;
    private LinearLayout fileRows;
    private TextView status,transfer;
    private ProgressBar progress;
    private Button importButton,refreshButton,cancelButton;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); I18n.init(this);Intent i=getIntent();id=i.getIntExtra("instanceId",0);number=i.getIntExtra("instanceNumber",0);
        session=i.getStringExtra("session");token=i.getStringExtra("brokerToken");
        if((id<7||id>65535)||!InstanceNames.valid(number)||session==null||!session.matches("[1-9][0-9]*:[1-9][0-9]*")||token==null||token.isEmpty()||i.getIntExtra("brokerPort",19762)!=19762){finish();return;}
        base="http://127.0.0.1:19762";
        if(saved!=null){selectedName=saved.getString("selectedName","");selectedBytes=saved.getLong("selectedBytes",-1);}
        makeUi();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("selectedName",selectedName);b.putLong("selectedBytes",selectedBytes);}
    @Override protected void onResume(){super.onResume();if(status==null)return;resumed=true;sessionValid=false;loading=false;int e=++epoch;monitor=Executors.newSingleThreadScheduledExecutor();monitor.scheduleWithFixedDelay(()->observe(e),0,1000,TimeUnit.MILLISECONDS);if(pendingUri!=null)runPicked();else refresh();}
    @Override protected void onPause(){resumed=false;loading=false;sessionValid=false;++epoch;if(monitor!=null)monitor.shutdownNow();monitor=null;cancel(I18n.s(R.string.ui_ad3b13c31706));for(HttpURLConnection c:connections)closeAsync(c::disconnect);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;cancel(I18n.s(R.string.ui_6c0c3a871ed5));worker.shutdownNow();closer.shutdown();super.onDestroy();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);return x;}
    private TextView label(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    private Button button(String title,String description,Runnable action){Button b=new Button(this);b.setText(title);b.setContentDescription(InstanceNames.description(description,number));b.setOnClickListener(v->action.run());return b;}
    private void makeUi(){
        LinearLayout root=column();root.setPadding(dp(18),dp(10),dp(18),dp(10));root.setBackgroundColor(Color.rgb(20,24,30));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(label(InstanceNames.title(number)+I18n.s(R.string.ui_21cf7ddb41e1),24),new LinearLayout.LayoutParams(0,-2,1));top.addView(button(I18n.s(R.string.ui_53505b1fcd1a),"files-back",this::finish));root.addView(top);
        root.addView(label(I18n.s(R.string.ui_6bac12d05195),15));
        status=label(I18n.s(R.string.ui_84cb10f62524),14);status.setContentDescription(InstanceNames.description("files-status",number));root.addView(status);
        LinearLayout actions=new LinearLayout(this);importButton=button(I18n.s(R.string.ui_8fb6f00ec5b5),"files-import",this::pickImport);refreshButton=button(I18n.s(R.string.ui_bd82e40e45a0),"files-refresh",this::refresh);actions.addView(importButton);actions.addView(refreshButton);root.addView(actions);
        transfer=label(I18n.s(R.string.ui_b6d520e0511f),14);transfer.setContentDescription(InstanceNames.description("files-progress",number));root.addView(transfer);
        LinearLayout meter=new LinearLayout(this);meter.setGravity(Gravity.CENTER_VERTICAL);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);meter.addView(progress,new LinearLayout.LayoutParams(0,dp(28),1));cancelButton=button(I18n.s(R.string.ui_965b34119adf),"files-cancel",()->cancel(I18n.s(R.string.ui_910383278517)));meter.addView(cancelButton);root.addView(meter);
        ScrollView scroll=new ScrollView(this);fileRows=column();scroll.addView(fileRows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);controls();
    }
    private void controls(){boolean free=operation==null&&!loading;importButton.setEnabled(sessionValid&&free);refreshButton.setEnabled(free);cancelButton.setEnabled(operation!=null);}
    private void observe(int e){
        try{
            JSONObject state=json("GET","/state",200,null);JSONArray rows=state.getJSONArray("instances");boolean found=false;
            for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);if(row.optInt("id")!=id)continue;found=true;boolean match=session.equals(row.optString("session"))&&row.optBoolean("running")&&row.optBoolean("ready");
                ui.post(()->{if(e!=epoch||!resumed)return;sessionValid=match;if(!match){cancel(I18n.s(R.string.ui_5fc688314cb0));status.setText(I18n.s(R.string.ui_fc03a16921a8));}controls();});break;}
            if(!found)throw new IOException(I18n.s(R.string.ui_42edfd06d964));
        }catch(Exception failure){ui.post(()->{if(e==epoch&&resumed){sessionValid=false;status.setText(I18n.s(R.string.ui_96c0ba63a840));controls();}});}
    }
    private void checkLive()throws Exception{checkLive(0);}
    private void checkLive(long deadline)throws Exception{
        JSONObject state=json("GET","/state",200,null,deadline);JSONArray rows=state.getJSONArray("instances");
        for(int n=0;n<rows.length();n++){JSONObject r=rows.getJSONObject(n);if(r.optInt("id")==id&&session.equals(r.optString("session"))&&r.optBoolean("running")&&r.optBoolean("ready"))return;}
        throw new IOException(I18n.s(R.string.ui_5fc688314cb0));
    }
    private String query(String action,String name)throws Exception{return "/files"+action+"?id="+id+"&session="+URLEncoder.encode(session,"UTF-8")+(name==null?"":"&name="+URLEncoder.encode(name,"UTF-8"));}
    private void refresh(){refresh(false);}
    private void refresh(boolean automaticAfterTransfer){
        if(!resumed||loading||operation!=null)return;loading=true;controls();final int e=epoch;
        worker.execute(()->{try{
            DirectoryRefresh.Guard guard=()->{if(destroyed||!resumed||e!=epoch||operation!=null)throw new IOException(I18n.s(R.string.ui_16ff9253f805));};
            DirectoryRefresh.Get<JSONObject> request=deadline->{try{guard.check();checkLive(deadline);guard.check();JSONObject value=json("GET",query("",null),200,null,deadline);requireIdentity(value);return value;}
                catch(IOException error){throw error;}catch(Exception error){throw new IOException(message(error),error);}};
            JSONObject result=automaticAfterTransfer?DirectoryRefresh.get(request,guard,SystemClock::elapsedRealtime,Thread::sleep,
                ()->ui.post(()->{if(e==epoch&&resumed){status.setText(I18n.s(R.string.ui_73685fac59f8));}})):request.get(0);
            guard.check();JSONArray files=result.getJSONArray("files");if(files.length()>128)throw new IOException(I18n.s(R.string.ui_6a061766d816));
            ui.post(()->{if(e!=epoch||!resumed)return;loading=false;sessionValid=true;status.setText(I18n.s(R.string.ui_9a15c8d8f000));renderFiles(files);controls();});
        }catch(Exception failure){ui.post(()->{if(e!=epoch||!resumed)return;loading=false;fileRows.removeAllViews();status.setText(I18n.s(R.string.ui_f636fbcd54c5)+message(failure));controls();});}});
    }
    private void renderFiles(JSONArray files){fileRows.removeAllViews();if(files.length()==0){fileRows.addView(label(I18n.s(R.string.ui_b9e4148e0282),17));return;}
        for(int n=0;n<files.length();n++){JSONObject file=files.optJSONObject(n);if(file==null)continue;String name=file.optString("name","");long bytes=file.optLong("bytes",-1);if(!validName(name)||bytes<0||bytes>MAX_BYTES)continue;
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView title=label(name+"\n"+size(bytes),17);title.setPadding(dp(8),dp(8),dp(8),dp(8));row.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button export=button(I18n.s(R.string.ui_188896795f1d),"files-export-"+n,()->pickExport(name,bytes));row.addView(export);fileRows.addView(row);}
    }
    private void pickImport(){if(operation!=null||!sessionValid)return;Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT);choose.addCategory(Intent.CATEGORY_OPENABLE);choose.setType("*/*");try{startActivityForResult(choose,PICK_IMPORT);}catch(Exception e){transfer.setText(I18n.s(R.string.ui_4fae6a2dba6f));}}
    private void pickExport(String name,long bytes){if(operation!=null||!sessionValid)return;selectedName=name;selectedBytes=bytes;Intent choose=new Intent(Intent.ACTION_CREATE_DOCUMENT);choose.addCategory(Intent.CATEGORY_OPENABLE);choose.setType("application/octet-stream");choose.putExtra(Intent.EXTRA_TITLE,name);try{startActivityForResult(choose,PICK_EXPORT);}catch(Exception e){transfer.setText(I18n.s(R.string.ui_d5a6a98e28f7));}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_IMPORT&&request!=PICK_EXPORT)return;if(result!=RESULT_OK||data==null||data.getData()==null){if(transfer!=null)transfer.setText(I18n.s(R.string.ui_0a522ea5c033));return;}pendingUri=data.getData();pendingImport=request==PICK_IMPORT;if(resumed)runPicked();}
    private void runPicked(){Uri uri=pendingUri;pendingUri=null;if(uri==null||operation!=null)return;Operation op=new Operation(uri,pendingImport,selectedName,selectedBytes);operation=op;loading=false;progress.setProgress(0);transfer.setText(op.upload?I18n.s(R.string.ui_cc8c3ff296c3):I18n.s(R.string.ui_91783c475b4b));controls();ui.postDelayed(op.timeout,DEADLINE_MS);worker.execute(()->execute(op));}
    /** 成功以身份/大小/SHA256回执为准；导入回执丢失可能已发布，先刷新而不是盲目重传。
     * 导出失败只尝试删除本次用户选择的未完成文档，不能扩展成任意目录清理。
     */
    private void execute(Operation op){
        boolean exported=false;String outcome;
        try{checkLive();op.check();if(op.upload){upload(op);outcome=I18n.s(R.string.ui_2295848aae55)+op.name+I18n.s(R.string.ui_785dd9ea5202);}else{download(op);exported=true;outcome=I18n.s(R.string.ui_f1dc2ddf285a)+op.name;}}
        catch(Exception e){outcome=(op.cancelled?op.reason:I18n.s(R.string.ui_d423be219c7c)+message(e));if(op.upload&&op.requestStarted)outcome+=I18n.s(R.string.ui_7e893fad9c38);}
        finally{ui.removeCallbacks(op.timeout);op.close();}
        if(!op.upload&&!exported&&!deleteIncomplete(op.uri))outcome+=I18n.s(R.string.ui_0aaab18dfb19);
        final String text=outcome;final boolean success=exported||op.completed;ui.post(()->{if(operation!=op)return;operation=null;if(destroyed)return;transfer.setText(text);progress.setProgress(success?1000:0);controls();if(resumed)refresh(true);});
    }
    private void upload(Operation op)throws Exception{
        String name=null;long total=-1;try(Cursor c=getContentResolver().query(op.uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null,op.signal)){if(c!=null&&c.moveToFirst()){int ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),si=c.getColumnIndex(OpenableColumns.SIZE);if(ni>=0)name=c.getString(ni);if(si>=0&&!c.isNull(si))total=c.getLong(si);}}
        if(!validName(name))throw new IOException(I18n.s(R.string.ui_826bfe2082bf));if(total<0)throw new IOException(I18n.s(R.string.ui_d32e536f4db2));if(total>MAX_BYTES)throw new IOException(I18n.s(R.string.ui_2a5e7a625b48));op.name=name;op.total=total;op.check();
        HttpURLConnection c=open("POST",query("/import",name),op);c.setDoOutput(true);c.setFixedLengthStreamingMode(total);c.setRequestProperty("Content-Type","application/octet-stream");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");AssetFileDescriptor descriptor=getContentResolver().openAssetFileDescriptor(op.uri,"r",op.signal);if(descriptor==null)throw new IOException(I18n.s(R.string.ui_63bda37a9075));op.provider=descriptor;op.check();op.input=descriptor.createInputStream();op.requestStarted=true;op.output=c.getOutputStream();copy(op,op.input,op.output,total,digest);op.output.close();op.output=null;op.check();
        int code=c.getResponseCode();if(code!=201)throw rejected(c,code);JSONObject receipt=readJson(c);requireIdentity(receipt);if(!name.equals(receipt.optString("name"))||receipt.optLong("bytes",-1)!=total||!hex(digest.digest()).equalsIgnoreCase(receipt.optString("sha256")))throw new IOException(I18n.s(R.string.ui_32e568babef7));op.check();checkLive();op.check();op.completed=true;
    }
    private void download(Operation op)throws Exception{
        if(!validName(op.name)||op.total<0||op.total>MAX_BYTES)throw new IOException(I18n.s(R.string.ui_7d5bec2673bb));
        HttpURLConnection c=open("GET",query("/export",op.name),op);int code=c.getResponseCode();if(code!=200)throw rejected(c,code);if(!session.equals(c.getHeaderField("X-Iris-Session")))throw new IOException(I18n.s(R.string.ui_c01087274984));long bytes=c.getContentLengthLong();if(bytes!=op.total||bytes<0||bytes>MAX_BYTES)throw new IOException(I18n.s(R.string.ui_2450efc254e9));
        String sha=c.getHeaderField("X-Iris-SHA256");if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IOException(I18n.s(R.string.ui_b22e5edeb5a7));
        op.check();op.input=c.getInputStream();ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(op.uri,"wt",op.signal);if(descriptor==null)throw new IOException(I18n.s(R.string.ui_8c9d5291cf02));op.provider=descriptor;op.check();op.output=new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);MessageDigest digest=MessageDigest.getInstance("SHA-256");copy(op,op.input,op.output,bytes,digest);op.output.flush();op.output.close();op.output=null;if(!sha.equals(hex(digest.digest())))throw new IOException(I18n.s(R.string.ui_fbc96d57a5de));op.check();checkLive();op.check();op.completed=true;
    }
    private void copy(Operation op,InputStream input,OutputStream output,long total,MessageDigest digest)throws Exception{
        final long[] next={0};
        TransferIO.copyExact(input,output,total,digest,op::check,(count,bytes)->{long now=SystemClock.elapsedRealtime();if(now>=next[0]||count==bytes){publish(op,count,bytes);next[0]=now+150;}});
    }
    private void publish(Operation op,long count,long total){ui.post(()->{if(operation!=op||destroyed||op.cancelled)return;transfer.setText((op.upload?I18n.s(R.string.ui_75557073ffee):I18n.s(R.string.ui_cb04ad9ba1b5))+op.name+"  "+size(count)+" / "+size(total));progress.setProgress(total==0?1000:(int)(count*1000/total));});}
    private void closeAsync(Runnable close){try{closer.execute(close);}catch(java.util.concurrent.RejectedExecutionException ignored){}}
    // A document provider can ignore interruption. Wait briefly, and never
    // accumulate more delete calls if one provider remains stuck.
    private boolean deleteIncomplete(Uri uri){
        if(!deleting.compareAndSet(false,true))return false;
        Future<Boolean> result;
        try{result=closer.submit(()->{try{return DocumentsContract.deleteDocument(getContentResolver(),uri);}finally{deleting.set(false);}});}
        catch(Exception failure){deleting.set(false);return false;}
        try{return result.get(1500,TimeUnit.MILLISECONDS);}catch(Exception failure){result.cancel(true);return false;}
    }
    private void cancel(String why){Operation op=operation;if(op==null||op.completed||op.cancelled)return;op.reason=why;op.cancelled=true;closeAsync(op.signal::cancel);closeAsync(op::close);ui.post(()->{if(operation==op&&!destroyed){transfer.setText(why+I18n.s(R.string.ui_5d8e922bf35b));cancelButton.setEnabled(false);}});}
    private final class Operation {
        final Uri uri;final boolean upload;final long started=SystemClock.elapsedRealtime();final CancellationSignal signal=new CancellationSignal();
        final Runnable timeout=()->{if(operation==this)cancel(I18n.s(R.string.ui_7a920ae53c2a));};
        volatile boolean cancelled,completed,requestStarted;volatile String reason=I18n.s(R.string.ui_c2013c9c102f);String name="";long total;volatile HttpURLConnection connection;volatile InputStream input;volatile OutputStream output;volatile Closeable provider;
        Operation(Uri uri,boolean upload,String name,long bytes){this.uri=uri;this.upload=upload;this.name=name;this.total=bytes;}
        void check()throws IOException{if(cancelled||destroyed||!resumed)throw new IOException(reason);if(SystemClock.elapsedRealtime()-started>=DEADLINE_MS)throw new IOException(I18n.s(R.string.ui_ba74fe0a6d30));}
        void close(){HttpURLConnection c=connection;if(c!=null){c.disconnect();connections.remove(c);}closeQuietly(provider);closeQuietly(input);closeQuietly(output);}
    }
    private HttpURLConnection open(String method,String path,Operation op)throws Exception{int openedEpoch=epoch;HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();connections.add(c);try{if(destroyed||!resumed||openedEpoch!=epoch)throw new IOException(I18n.s(R.string.ui_dca586ba4d8b));if(op!=null){op.connection=c;op.check();}c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Authorization","Bearer "+token);return c;}catch(Exception failure){c.disconnect();connections.remove(c);throw failure;}}
    private JSONObject json(String method,String path,int expected,Operation op)throws Exception{return json(method,path,expected,op,0);}
    private JSONObject json(String method,String path,int expected,Operation op,long deadline)throws Exception{
        HttpURLConnection c=open(method,path,op);Runnable expiry=()->closeAsync(c::disconnect);
        try{
            if(deadline!=0){long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)throw new IOException(I18n.s(R.string.ui_652033f9b4dc));
                c.setConnectTimeout((int)Math.min(5000,remaining));c.setReadTimeout((int)Math.min(15000,remaining));ui.postDelayed(expiry,remaining);}
            int code=c.getResponseCode();if(code!=expected)throw rejected(c,code);JSONObject result=readJson(c);
            if(deadline!=0&&SystemClock.elapsedRealtime()>deadline)throw new IOException(I18n.s(R.string.ui_652033f9b4dc));return result;
        }finally{ui.removeCallbacks(expiry);c.disconnect();connections.remove(c);}
    }
    private JSONObject readJson(HttpURLConnection c)throws Exception{try(InputStream input=c.getInputStream()){return new JSONObject(new String(bounded(input),"UTF-8"));}}
    private static byte[] bounded(InputStream input)throws IOException{if(input==null)return new byte[0];try(ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] data=new byte[4096];int n;while((n=input.read(data))!=-1){if(output.size()+n>65536)throw new IOException(I18n.s(R.string.ui_a3415b5adf19));output.write(data,0,n);}return output.toByteArray();}}
    private IOException rejected(HttpURLConnection c,int code){String detail="",machineCode="";try(InputStream input=c.getErrorStream()){JSONObject j=new JSONObject(new String(bounded(input),"UTF-8"));detail=j.optString("error","");machineCode=j.optString("code","");}catch(Exception ignored){}if(detail.length()>220)detail=detail.substring(0,220);String hint=code==409?I18n.s(R.string.ui_7637fa012880):code==423?I18n.s(R.string.ui_a43c7b66fb3a):code==503?I18n.s(R.string.ui_3b229178f89f):code==413?I18n.s(R.string.ui_2a5e7a625b48):code==401?I18n.s(R.string.ui_34cbe1449ed7):I18n.s(R.string.ui_4216aa6e21bd)+code+"）";return new DirectoryRefresh.Failure(code,machineCode,hint+(detail.isEmpty()?"":"："+detail));}
    private void requireIdentity(JSONObject j)throws IOException{if(j.optInt("id")!=id||!session.equals(j.optString("session")))throw new IOException(I18n.s(R.string.ui_8551a052e534));}
    private static boolean validName(String name){if(name==null||name.isEmpty()||name.startsWith(".")||name.indexOf('/')>=0||name.indexOf('\\')>=0)return false;for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return false;try{return name.getBytes("UTF-8").length<=128;}catch(Exception e){return false;}}
    private static String size(long value){return value>=1048576?String.format(Locale.ROOT,"%.1f MiB",value/1048576.0):value>=1024?String.format(Locale.ROOT,"%.1f KiB",value/1024.0):value+" B";}
    private static String message(Exception error){String m=I18n.diagnostic(error.getMessage());return m==null||m.isEmpty()?I18n.s(R.string.ui_c99f4f63f0d4):m.length()>220?m.substring(0,220):m;}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private static void closeQuietly(Closeable c){try{if(c!=null)c.close();}catch(Exception ignored){}}
}
