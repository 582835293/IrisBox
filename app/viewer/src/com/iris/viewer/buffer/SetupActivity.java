package com.irisbox.viewer;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import java.util.concurrent.*;

/** 正常桌面入口：root → 宿主检查/引擎 → 系统文件选择 → 原有实例管理。 */
public final class SetupActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {super.attachBaseContext(I18n.wrap(base));}

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView output, info;
    private Button check, install, choose, open, language;
    private boolean checking;
    private boolean displayedBusy;
    private final Runnable refresh = new Runnable() {
        public void run() {
            String message=getSharedPreferences("setup", 0).getString("message", I18n.s(R.string.ui_7403656d2fd1));
            message=I18n.status(message);
            if (!message.contentEquals(output.getText())) output.setText(message);
            boolean running = SetupService.busy.get();
            language.setEnabled(!running && !checking); check.setEnabled(!running && !checking); install.setEnabled(!running && !checking); choose.setEnabled(!running && !checking); open.setEnabled(!running && !checking);
            if (displayedBusy && !running) readStatus(); displayedBusy = running;
            ui.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); I18n.init(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(1); layout.setPadding(28,16,28,16); layout.setBackgroundColor(Color.rgb(20,24,30));
        TextView title = label(I18n.s(R.string.ui_1e14f55edbd2), 25); layout.addView(title);
        layout.addView(label(I18n.s(R.string.ui_735e5a8789a6),16));
        LinearLayout settings=new LinearLayout(this);layout.addView(settings);
        language=new Button(this);language.setText("EN / 中文");language.setOnClickListener(v->I18n.choose(this));settings.addView(language,new LinearLayout.LayoutParams(0,-2,1));
        button(settings,I18n.known("交换空间"),()->startActivity(new Intent(this,HostSwapActivity.class)));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); layout.addView(row);
        check = button(row,I18n.s(R.string.ui_719fc1bb80cb), () -> inspect());
        install = button(row,I18n.s(R.string.ui_5f6b5ee42ec1), () -> task("install",null));
        choose = button(row,I18n.s(R.string.ui_ed63f2ba7598), () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,51);
        });
        open = button(row,I18n.s(R.string.ui_697394547d6e), () -> {
            checking=true;
            worker.execute(() -> {
                try {
                    RootShell.run("test -s /data/irisbox/runtime/config/default_rom",15);
                    ui.post(() -> startActivity(new Intent(this,ListActivity.class)));
                } catch (Exception e) { SetupService.message(this,I18n.s(R.string.ui_e9813c6d4b9f)+e.getMessage()); }
                finally { ui.post(() -> checking=false); }
            });
        });
        info = label(I18n.s(R.string.ui_8dec65600663),14); layout.addView(info);
        ScrollView scroll = new ScrollView(this); output=label("",15); output.setTextIsSelectable(true); scroll.addView(output);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(layout);
        readStatus();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 52);
    }
    private TextView label(String s,int size) { TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(0,5,0,5);return t; }
    private Button button(LinearLayout row,String text,Runnable action) { Button b=new Button(this);b.setText(text);b.setTextSize(13);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b; }
    private void task(String action,Uri uri) {
        Intent i=new Intent(this,SetupService.class).putExtra("action",action);
        if(uri!=null)i.putExtra("uri",uri.toString());
        startForegroundService(i);
    }
    private void inspect() {
        checking=true; SetupService.message(this,I18n.s(R.string.ui_81d95e0dc5fe));
        worker.execute(() -> {
            try { SetupService.message(this, EngineInstaller.preflight(this)); getSharedPreferences("setup",0).edit().putBoolean("rootChecked",true).apply(); readStatus(); }
            catch(Exception e) { SetupService.message(this,e.getMessage()); }
            finally { ui.post(() -> checking=false); }
        });
    }
    private void readStatus() {
        if (!getSharedPreferences("setup",0).getBoolean("rootChecked",false)) return;
        worker.execute(() -> {
            try {
                String status=EngineInstaller.status();
                ui.post(() -> info.setText(I18n.status(status)));
            } catch(Exception e) { ui.post(() -> info.setText(I18n.s(R.string.ui_10b0999596e9))); }
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==51&&result==RESULT_OK&&data!=null&&data.getData()!=null) {
            Uri uri=data.getData();
            try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException ignored) {}
            new AlertDialog.Builder(this).setTitle(I18n.s(R.string.ui_233cfdb13ce4))
                .setMessage(I18n.s(R.string.ui_926bfbf0ee0b))
                .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787),null).setPositiveButton(I18n.s(R.string.ui_60e2bcad85ac),(d,w)->task("import",uri)).show();
        }
    }
    @Override protected void onResume() {super.onResume();ui.post(refresh);}
    @Override protected void onPause() {ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy() {worker.shutdownNow();super.onDestroy();}
}
