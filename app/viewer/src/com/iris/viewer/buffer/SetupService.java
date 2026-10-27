package com.irisbox.viewer;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.IBinder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 导入期间使用前台服务，离开页面不取消写入；不持有 Activity。 */
public final class SetupService extends Service {
    @Override protected void attachBaseContext(android.content.Context base) {super.attachBaseContext(I18n.wrap(base));}

    static final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    static void message(Context c, String text) { c.getSharedPreferences("setup", 0).edit().putString("message", text).apply(); }
    private void progress(String text) {
        text=I18n.diagnostic(text); message(this, text);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(10, notification(text));
    }
    private Notification notification(String text) {
        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "setup").setContentTitle(I18n.s(R.string.ui_84f938378054))
            .setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(intent).setOngoing(true).build();
    }
    @Override public void onCreate() {
        super.onCreate(); I18n.init(this);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("setup", I18n.s(R.string.ui_e49153879bdd), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(10, notification(I18n.s(R.string.ui_b1d5c9a2bcc4)));
        if (intent == null || !busy.compareAndSet(false, true)) return START_NOT_STICKY;
        final String action = intent.getStringExtra("action"), uri = intent.getStringExtra("uri");
        worker.execute(() -> {
            try {
                if ("install".equals(action)) {
                    EngineInstaller.install(this, this::progress);
                    message(this, I18n.s(R.string.ui_005bf385d786));
                } else if ("import".equals(action)) {
                    Uri source = Uri.parse(uri == null ? "" : uri);
                    if (!"content".equals(source.getScheme())) throw new java.io.IOException(I18n.s(R.string.ui_fca4b0aaeb83));
                    message(this, EngineInstaller.importRom(this, source, this::progress));
                } else if ("swap".equals(action)) {
                    progress(I18n.known("正在设置交换空间，请等待完成…"));
                    try {
                        HostSwap.run("configure",intent.getIntExtra("mib",-1),intent.getBooleanExtra("automatic",false));
                        getSharedPreferences("swap",0).edit().putString("result","OK").apply();
                        message(this,I18n.known("交换空间设置已应用"));
                    } catch(Exception failure) {
                        getSharedPreferences("swap",0).edit().putString("result",failure.getMessage()).apply();throw failure;
                    }
                } else throw new java.io.IOException(I18n.s(R.string.ui_831cdf82e2aa));
            } catch (Exception failure) {
                message(this, I18n.s(R.string.ui_f5064e36fb9d) + I18n.diagnostic(failure.getMessage()) + I18n.s(R.string.ui_7f293b95fed9));
            } finally {
                busy.set(false); stopForeground(true); stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
