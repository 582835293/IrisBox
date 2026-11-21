package com.irisbox.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外置 ext4 SSD（/data/irisbox）开机检测、写前拦截与挂载恢复控制。
 * 逻辑在 /data/irisbox 挂载前执行，因此脚本从 APK assets 读取并在内存管道传给 RootShell，不依赖 /data/irisbox/runtime。
 */
final class HostStorage {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static String loadScript(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("storage_ssd.sh")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String runScript(Context c, String action, String arg2, int timeoutSeconds) throws Exception {
        String script = loadScript(c);
        String wrapped = "/system/bin/sh -s " + RootShell.quote(action) + " " + RootShell.quote(arg2) +
            " <<'IRIS_STORAGE_SCRIPT_EOF'\n" + script + "\nIRIS_STORAGE_SCRIPT_EOF";
        return RootShell.run(wrapped, timeoutSeconds);
    }

    static void ensureMountedGuard(Context c) throws Exception {
        boolean required = BoardSpec.isExternalMountRequired(c);
        try {
            runScript(c, "verify-guard", required ? "1" : "0", 15);
        } catch (Exception e) {
            throw new IOException(translateError(e.getMessage()), e);
        }
    }

    static JSONObject status(Context c) throws Exception {
        boolean required = BoardSpec.isExternalMountRequired(c);
        String out;
        try {
            out = runScript(c, "status", required ? "1" : "0", 20);
        } catch (Exception e) {
            throw new IOException(translateError(e.getMessage()), e);
        }
        for (String line : out.split("\\r?\\n")) {
            if (line.startsWith("IRIS_SSD_JSON=")) return new JSONObject(line.substring(14));
        }
        throw new IOException(I18n.known("未收到外置存储状态，请刷新检查"));
    }

    static JSONObject mountAndBind(Context c, String uuid) throws Exception {
        if (uuid == null || !uuid.matches("[0-9a-fA-F-]{8,64}")) {
            throw new IOException(I18n.known("无效的外置 SSD UUID"));
        }
        String out;
        try {
            out = runScript(c, "mount-bind", uuid, 45);
        } catch (Exception e) {
            throw new IOException(translateError(e.getMessage()), e);
        }
        for (String line : out.split("\\r?\\n")) {
            if (line.startsWith("IRIS_SSD_JSON=")) return new JSONObject(line.substring(14));
        }
        throw new IOException(I18n.known("外置 SSD 挂载未返回状态，请刷新确认"));
    }

    static JSONObject unmount(Context c) throws Exception {
        boolean required = BoardSpec.isExternalMountRequired(c);
        String out;
        try {
            out = runScript(c, "unmount", required ? "1" : "0", 20);
        } catch (Exception e) {
            throw new IOException(translateError(e.getMessage()), e);
        }
        for (String line : out.split("\\r?\\n")) {
            if (line.startsWith("IRIS_SSD_JSON=")) return new JSONObject(line.substring(14));
        }
        throw new IOException(I18n.known("外置 SSD 卸载未返回状态"));
    }

    static String translateError(String text) {
        String value = text == null ? "" : text;
        String[] codes = {
            "SSD_UNBOUND", "SSD_NOT_MOUNTED", "SSD_MARKER_MISMATCH", "SSD_NOT_FOUND",
            "MOUNTPOINT_NOT_EMPTY", "MOUNT_FAILED", "UNMOUNT_FAILED", "STOP_INSTANCES",
            "ALREADY_BOUND_OTHER", "CONF_INVALID", "INVALID_UUID", "UNSAFE_PATH",
            "UNSAFE_BOOT_HOOK", "FOREIGN_BOOT_HOOK"
        };
        String[] msgs = {
            "当前板卡要求外置 ext4 SSD，请先在首页“外置存储”绑定并挂载",
            "外置 SSD 未挂载至 /data/irisbox，已拦截写入内部存储，请点“外置存储”恢复挂载",
            "外置 SSD 身份标记与绑定不匹配，已拦截以防错盘覆盖",
            "未检测到匹配该 UUID 的外置 ext4 分区，请确认 USB SSD 已插好并已格式化为 ext4",
            "/data/irisbox 目录非空且未挂载，为防覆盖已有数据已拒绝挂载，请先人工核对清理",
            "挂载外置 ext4 SSD 失败，请检查内核日志与磁盘健康状态",
            "卸载外置 SSD 失败，可能仍有进程占用 /data/irisbox",
            "请先停止所有虹膜空间实例，再卸载外置存储",
            "当前已挂载并绑定另一块外置 SSD，禁止直接覆盖绑定",
            "外置存储配置文件损坏，请核对 /data/adb/irisbox_storage.conf",
            "外置存储 UUID 格式无效",
            "外置存储路径不安全（发现符号链接），已停止操作",
            "开机挂载脚本路径不安全，已停止操作",
            "开机挂载脚本名称已被其他内容占用，未覆盖"
        };
        for (int i = 0; i < codes.length; i++) {
            if (value.contains("IRIS_SSD_ERROR=" + codes[i]) || value.contains(codes[i])) {
                return I18n.known(msgs[i]);
            }
        }
        return I18n.diagnostic(value);
    }

    static void openDialog(Activity activity, Runnable onChanged) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 12);

        TextView summary = new TextView(activity);
        summary.setTextColor(Color.WHITE);
        summary.setTextSize(14);
        summary.setText(I18n.known("正在检测外置 ext4 SSD 状态…"));
        root.addView(summary);

        RadioGroup choices = new RadioGroup(activity);
        choices.setPadding(0, 16, 0, 8);
        root.addView(choices);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(I18n.known("外置存储（/data/irisbox）"))
            .setView(scroll)
            .setPositiveButton(I18n.known("挂载并启用开机恢复"), null)
            .setNeutralButton(I18n.known("安全卸载"), null)
            .setNegativeButton(I18n.known("关闭"), null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            refreshDialog(activity, dialog, summary, choices, onChanged);
        });
        dialog.show();
    }

    private static void refreshDialog(Activity activity, AlertDialog dialog, TextView summary, RadioGroup choices, Runnable onChanged) {
        WORKER.execute(() -> {
            try {
                JSONObject st = status(activity);
                UI.post(() -> {
                    if (!dialog.isShowing()) return;
                    renderState(activity, dialog, summary, choices, st, onChanged);
                });
            } catch (Exception e) {
                UI.post(() -> {
                    if (!dialog.isShowing()) return;
                    summary.setText(translateError(e.getMessage()));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                });
            }
        });
    }

    private static void renderState(Activity activity, AlertDialog dialog, TextView summary, RadioGroup choices, JSONObject st, Runnable onChanged) {
        boolean req = st.optBoolean("requireExternal", false);
        String bound = st.optString("boundUuid", "");
        boolean mounted = st.optBoolean("mounted", false);
        boolean markerOk = st.optBoolean("markerOk", false);
        boolean hook = st.optBoolean("hookInstalled", false);
        boolean nonEmpty = st.optBoolean("mountPointNonEmpty", false);
        String dev = st.optString("device", "");
        long freeMiB = st.optLong("freeMiB", 0);
        JSONArray cands = st.optJSONArray("candidates");

        StringBuilder sb = new StringBuilder();
        sb.append(req ? I18n.known("当前板卡策略：强制外置 ext4 SSD\n") : I18n.known("当前板卡策略：可选外置 ext4 SSD\n"));
        sb.append(mounted
            ? String.format(Locale.getDefault(), I18n.known("挂载状态：已挂载（%1$s · 可用 %2$d MiB）\n"), dev, freeMiB)
            : I18n.known("挂载状态：未挂载\n"));
        sb.append(bound.isEmpty()
            ? I18n.known("绑定 UUID：尚未绑定\n")
            : String.format(Locale.getDefault(), I18n.known("绑定 UUID：%1$s\n"), bound));
        sb.append(String.format(Locale.getDefault(), I18n.known("所有权标记：%1$s · 开机恢复脚本：%2$s"),
            markerOk ? I18n.known("正常") : I18n.known("待核验"),
            hook ? I18n.known("已安装") : I18n.known("未安装")));
        if (!mounted && nonEmpty) {
            sb.append("\n").append(I18n.known("警告：/data/irisbox 未挂载且目录非空，请核对是否误写入内部 /data。"));
        }
        summary.setText(sb.toString());

        choices.removeAllViews();
        int count = cands == null ? 0 : cands.length();
        String firstUuid = "";
        int checkedId = -1;
        for (int i = 0; i < count; i++) {
            JSONObject item = cands.optJSONObject(i);
            if (item == null) continue;
            String cDev = item.optString("dev", "");
            String cUuid = item.optString("uuid", "");
            String cLabel = item.optString("label", "");
            long sizeMiB = item.optLong("sizeMiB", 0);
            RadioButton rb = new RadioButton(activity);
            rb.setId(1000 + i);
            rb.setTextColor(Color.WHITE);
            String labelText = cLabel.isEmpty() ? "" : ("[" + cLabel + "] ");
            rb.setText(String.format(Locale.getDefault(), "%s %s(%d GiB)\nUUID: %s", cDev, labelText, sizeMiB / 1024, cUuid));
            rb.setTag(cUuid);
            choices.addView(rb);
            if (firstUuid.isEmpty()) firstUuid = cUuid;
            if ((!bound.isEmpty() && bound.equals(cUuid)) || (bound.isEmpty() && checkedId == -1)) {
                checkedId = rb.getId();
            }
        }
        if (checkedId != -1) choices.check(checkedId);
        if (count == 0) {
            TextView empty = new TextView(activity);
            empty.setTextColor(Color.LTGRAY);
            empty.setText(I18n.known("未发现外置 ext4 分区（请先插入 USB SSD 并确保已格式化为 ext4）。"));
            choices.addView(empty);
        }

        final String fallbackUuid = bound.isEmpty() ? firstUuid : bound;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!fallbackUuid.isEmpty());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(mounted);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String targetUuid = fallbackUuid;
            int sel = choices.getCheckedRadioButtonId();
            if (sel != -1) {
                RadioButton selected = choices.findViewById(sel);
                if (selected != null && selected.getTag() instanceof String) {
                    targetUuid = (String) selected.getTag();
                }
            }
            final String uuidToUse = targetUuid;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            summary.setText(I18n.known("正在核验并挂载外置 SSD…"));
            WORKER.execute(() -> {
                try {
                    JSONObject updated = mountAndBind(activity, uuidToUse);
                    UI.post(() -> {
                        if (onChanged != null) onChanged.run();
                        if (dialog.isShowing()) renderState(activity, dialog, summary, choices, updated, onChanged);
                    });
                } catch (Exception e) {
                    UI.post(() -> {
                        if (dialog.isShowing()) {
                            summary.setText(translateError(e.getMessage()));
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        }
                    });
                }
            });
        });

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            summary.setText(I18n.known("正在安全卸载外置 SSD…"));
            WORKER.execute(() -> {
                try {
                    JSONObject updated = unmount(activity);
                    UI.post(() -> {
                        if (onChanged != null) onChanged.run();
                        if (dialog.isShowing()) renderState(activity, dialog, summary, choices, updated, onChanged);
                    });
                } catch (Exception e) {
                    UI.post(() -> {
                        if (dialog.isShowing()) {
                            summary.setText(translateError(e.getMessage()));
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                        }
                    });
                }
            });
        });
    }
}
