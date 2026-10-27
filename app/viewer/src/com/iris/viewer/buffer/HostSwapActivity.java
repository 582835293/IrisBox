package com.irisbox.viewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Host-wide settings, separate from each guest's RAM ceiling. */
public final class HostSwapActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(I18n.wrap(base));}
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status,result;
    private Spinner size;
    private CheckBox automatic;
    private Button apply;
    private boolean resumed,reading,loaded,wasBusy,needRead=true;
    private final Runnable refresh=new Runnable(){public void run(){if(!resumed)return;read();ui.postDelayed(this,2000);}};
    private TextView text(String value,int sp){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(Color.WHITE);t.setPadding(0,8,0,8);return t;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);I18n.init(this);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(28,12,28,12);body.setBackgroundColor(Color.rgb(20,24,30));
        body.addView(text(I18n.known("交换空间"),24));
        body.addView(text(I18n.known("用磁盘缓解内存不足，速度可能变慢。更改大小或关闭前，请先停止所有虹膜空间实例。"),15));
        status=text(I18n.known("正在读取交换空间状态…"),15);body.addView(status);
        LinearLayout controls=new LinearLayout(this);body.addView(controls);
        size=new Spinner(this);size.setContentDescription("host-swap-size");
        ArrayAdapter<String> options=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{I18n.known("关闭交换空间"),"1 GiB","2 GiB","4 GiB"});size.setAdapter(options);controls.addView(size,new LinearLayout.LayoutParams(0,-2,1));
        automatic=new CheckBox(this);automatic.setText(I18n.known("重启后自动恢复（宿主Magisk）"));automatic.setTextColor(Color.WHITE);automatic.setContentDescription("host-swap-auto");controls.addView(automatic,new LinearLayout.LayoutParams(0,-2,2));
        apply=new Button(this);apply.setText(I18n.known("应用交换空间设置"));apply.setContentDescription("host-swap-apply");apply.setEnabled(false);controls.addView(apply,new LinearLayout.LayoutParams(0,-2,1));
        apply.setOnClickListener(v->{
            int index=size.getSelectedItemPosition();if(index<0||index>=HostSwap.SIZES.length)return;
            loaded=false;needRead=true;apply.setEnabled(false);
            startForegroundService(new Intent(this,SetupService.class).putExtra("action","swap").putExtra("mib",HostSwap.SIZES[index]).putExtra("automatic",automatic.isChecked()));
            result.setText(I18n.known("正在设置交换空间，请等待完成…"));
        });
        body.addView(text(I18n.known("交换空间由所有实例共享，不会增加物理内存。开机恢复仅启用交换空间，不会启动实例。关闭后文件保留；切换大小可能占用额外磁盘。"),14));
        result=text("",14);result.setTextIsSelectable(true);body.addView(result);
        LinearLayout footer=new LinearLayout(this);body.addView(footer);
        Button update=new Button(this);update.setText(I18n.known("刷新状态"));update.setOnClickListener(v->{needRead=true;read();});footer.addView(update,new LinearLayout.LayoutParams(0,-2,1));
        Button back=new Button(this);back.setText(I18n.known("返回"));back.setOnClickListener(v->finish());footer.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
    }
    private void read(){
        boolean busy=SetupService.busy.get();
        if(wasBusy&&!busy){loaded=false;needRead=true;}wasBusy=busy;
        apply.setEnabled(loaded&&!busy);size.setEnabled(!busy);automatic.setEnabled(!busy);
        String outcome=getSharedPreferences("swap",0).getString("result","");
        if(busy)result.setText(I18n.known("正在设置交换空间，请等待完成…"));
        else if("OK".equals(outcome))result.setText(I18n.known("交换空间设置已应用"));
        else if(!outcome.isEmpty())result.setText(HostSwap.error(outcome));
        if(reading||busy||!needRead)return;reading=true;needRead=false;
        worker.execute(()->{
            try{
                JSONObject s=HostSwap.run("status",0,false);
                ui.post(()->{
                    if(!resumed)return;
                    status.setText(String.format(Locale.getDefault(),I18n.known("已设置：%d MiB　已启用：%d MiB　已用：%d MiB\n宿主可用内存：%d MiB　磁盘可用：%d MiB　文件占用：%d MiB"),s.optInt("configuredMiB"),s.optInt("activeMiB"),s.optLong("usedKiB")/1024,s.optLong("memAvailableKiB")/1024,s.optLong("freeDiskKiB")/1024,s.optInt("allocatedMiB")));
                    if(!loaded){int saved=s.optInt("configuredMiB");for(int i=0;i<HostSwap.SIZES.length;i++)if(HostSwap.SIZES[i]==saved)size.setSelection(i);automatic.setChecked(s.optBoolean("autoRestore"));loaded=true;}
                    apply.setEnabled(!SetupService.busy.get());
                });
            }catch(Exception e){ui.post(()->{if(resumed){status.setText(HostSwap.error(e.getMessage()));apply.setEnabled(false);}});}
            finally{ui.post(()->reading=false);}
        });
    }
    @Override protected void onResume(){super.onResume();resumed=true;needRead=true;ui.post(refresh);}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
