package com.irisbox.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 方案二：App 内一键彻底清理宿主残余并拉起系统标准卸载。
 * 逻辑从 APK assets 读取并在内存管道递交 RootShell 执行，不依赖 /data/irisbox/runtime 本身。
 */
final class HostCleanup {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static String loadScript(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("uninstall_cleanup.sh")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String runCleanupScript(Context c) throws Exception {
        String script = loadScript(c);
        String wrapped = "/system/bin/sh -s confirm <<'IRIS_CLEANUP_SCRIPT_EOF'\n" + script + "\nIRIS_CLEANUP_SCRIPT_EOF";
        return RootShell.run(wrapped, 180);
    }

    static void openConfirmDialog(Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle(I18n.known("彻底清理并卸载虹膜空间"))
            .setMessage(I18n.known("本操作将不可逆地彻底删除 /data/irisbox 下的容器引擎、全部 ROM、所有虚拟实例及应用数据、宿主自管 Swap 文件与开机恢复脚本，随后拉起系统卸载界面。\n\n• 操作前请务必先手动停止全部运行中的容器实例。\n• 不会删除宿主机共享系统网络分派器与系统自带 zRAM。"))
            .setNegativeButton(I18n.known("取消"), null)
            .setPositiveButton(I18n.known("确认清理并卸载"), (d, w) -> startCleanup(activity))
            .show();
    }

    private static void startCleanup(Activity activity) {
        ProgressBar bar = new ProgressBar(activity);
        AlertDialog waiting = new AlertDialog.Builder(activity)
            .setTitle(I18n.known("正在彻底清理运行环境…"))
            .setView(bar)
            .setCancelable(false)
            .show();

        WORKER.execute(() -> {
            try {
                String out = runCleanupScript(activity);
                if (!out.contains("IRIS_CLEANUP_DONE")) {
                    throw new IOException("Cleanup did not finish cleanly");
                }
                boolean ssdDetached = out.contains("IRIS_CLEANUP_SSD_DETACHED=1");
                clearAppLocalState(activity);
                UI.post(() -> {
                    if (activity.isDestroyed()) return;
                    if (waiting.isShowing()) waiting.dismiss();
                    if (ssdDetached) {
                        new AlertDialog.Builder(activity)
                            .setTitle(I18n.known("清理完成"))
                            .setMessage(I18n.known("机内环境、开机脚本与配置已全部清空。\n\n检测到此前绑定过外置 SSD 但当前未插入/挂载，外置硬盘内旧文件无法代为清除；请在下方点击确定继续卸载应用，外置盘可后续按需清理。"))
                            .setCancelable(false)
                            .setPositiveButton(I18n.known("卸载应用"), (d, w) -> launchSystemUninstall(activity))
                            .show();
                    } else {
                        Toast.makeText(activity, I18n.known("宿主环境与残余已彻底清空，正在拉起系统卸载…"), Toast.LENGTH_LONG).show();
                        launchSystemUninstall(activity);
                    }
                });
            } catch (Exception e) {
                String errMsg = translateError(e.getMessage());
                UI.post(() -> {
                    if (activity.isDestroyed()) return;
                    if (waiting.isShowing()) waiting.dismiss();
                    new AlertDialog.Builder(activity)
                        .setTitle(I18n.known("未能执行彻底清理"))
                        .setMessage(errMsg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            }
        });
    }

    private static void launchSystemUninstall(Activity activity) {
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(uninstall);
    }

    private static void clearAppLocalState(Context c) {
        for (String name : new String[]{"setup", "connection", "language", "swap"}) {
            c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
        File filesDir = c.getFilesDir();
        if (filesDir != null) {
            File[] children = filesDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    try { PackageArchive.erase(f); } catch (Exception ignored) {}
                }
            }
        }
    }

    static String translateError(String text) {
        String value = text == null ? "" : text;
        String[] codes = {
            "STOP_INSTANCES", "INSTANCE_BUSY", "SUBMOUNT_ACTIVE", "RECLAIM_MEMORY",
            "SWAPOFF_FAILED", "FOREIGN_BOOT_HOOK", "UNSAFE_BOOT_HOOK", "UNMOUNT_FAILED",
            "ENGINE_MAINTENANCE", "UNSAFE_PATH", "ROOT_REQUIRED", "HOST_NAMESPACE_REQUIRED",
            "CLEANUP_FAILED"
        };
        String[] msgs = {
            "检测到仍有容器实例正在运行、网络或全屏会话未关闭，请先手动停止全部实例后再清理卸载",
            "检测到实例正处于初始化或异步重置/删除中，请等待任务完成后重试",
            "检测到 /data/irisbox 内部仍残留未卸载的挂载点，已拒绝删档防止误伤挂载底层，请先重启设备或检查挂载",
            "宿主当前可用内存不足以安全回收激活的 Swap（需预留 512 MiB 余量），请先关闭高占内存应用后再试",
            "停用自管交换空间失败，请检查宿主内存与磁盘状态",
            "发现 /data/adb/service.d/ 下的恢复脚本已被外部修改占用，未擅自删除，请人工确认",
            "开机恢复脚本路径包含符号链接，出于安全考虑已停止清理",
            "外置 SSD (/data/irisbox) 卸载失败，可能仍有其它进程占用，请先停止占用后重试",
            "引擎升级维护事务尚未结束，请先完成或恢复升级后再清理",
            "目标清理路径包含非预期符号链接，出于安全考虑已停止操作",
            "需要允许宿主机 Root (Magisk) 授权才能清理受保护的底层目录",
            "当前执行环境不在宿主根命名空间内，已阻止清理操作",
            "清理部分底层目录失败，请确认磁盘可写或无其它进程锁定"
        };
        for (int i = 0; i < codes.length; i++) {
            if (value.contains("IRIS_CLEANUP_ERROR=" + codes[i]) || value.contains(codes[i])) {
                return I18n.known(msgs[i]);
            }
        }
        return I18n.known("彻底清理失败，请先确认所有实例已停止并授予 Root 权限") + "\n" + I18n.diagnostic(value);
    }
}
