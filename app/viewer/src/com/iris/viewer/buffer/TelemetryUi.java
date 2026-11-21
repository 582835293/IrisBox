package com.irisbox.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 宿主机/实例 Linux 内核参数、实时性能与全屏帧率格式化与展示工具 */
final class TelemetryUi {
    private TelemetryUi() {}

    static String formatBytes(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static String formatRate(long bps) {
        if (bps < 0) return "--";
        if (bps < 1024) return bps + "B/s";
        if (bps < 1024L * 1024) return String.format(Locale.US, "%.1fKB/s", bps / 1024.0);
        return String.format(Locale.US, "%.2fMB/s", bps / (1024.0 * 1024.0));
    }

    static String formatTemp(int milliC) {
        if (milliC <= 0) return "--";
        return String.format(Locale.US, "%.1f°C", milliC / 1000.0);
    }

    static String formatPctX10(int pctX10) {
        if (pctX10 < 0) return "--";
        return String.format(Locale.US, "%.1f%%", pctX10 / 10.0);
    }

    static String formatDuration(long sec) {
        if (sec < 0) return "--";
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%dh%02dm%02ds", h, m, s);
        if (m > 0) return String.format(Locale.US, "%dm%02ds", m, s);
        return s + "s";
    }

    static String hostBarText(JSONObject host) {
        if (host == null) return "";
        String cpu = formatPctX10(host.optInt("cpuPctX10", -1));
        JSONArray freqs = host.optJSONArray("cpuFreqMHz");
        StringBuilder freqStr = new StringBuilder();
        if (freqs != null && freqs.length() > 0) {
            for (int i = 0; i < freqs.length(); i++) {
                if (i > 0) freqStr.append('/');
                freqStr.append(String.format(Locale.US, "%.1fG", freqs.optLong(i, 0) / 1000.0));
            }
        }
        int gpuLoad = host.optInt("gpuLoadPct", -1);
        long gpuFreq = host.optLong("gpuFreqMHz", -1);
        String gpuStr = gpuLoad >= 0 ? (gpuLoad + "%" + (gpuFreq > 0 ? "@" + gpuFreq + "MHz" : "")) : "--";
        String tempStr = formatTemp(host.optInt("maxTempMilliC", -1));

        long memTotal = host.optLong("memTotalBytes", -1);
        long memAvail = host.optLong("memAvailBytes", -1);
        long memUsed = (memTotal > 0 && memAvail >= 0) ? Math.max(0L, memTotal - memAvail) : -1;
        long swapTotal = host.optLong("swapTotalBytes", 0);
        long swapFree = host.optLong("swapFreeBytes", 0);
        long swapUsed = Math.max(0L, swapTotal - swapFree);
        long irisAvail = host.optLong("irisAvailBytes", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("宿主 CPU ").append(cpu);
        if (freqStr.length() > 0) sb.append(" (").append(freqStr).append(')');
        sb.append(" · GPU ").append(gpuStr);
        sb.append(" · 温度 ").append(tempStr);
        sb.append(" · 内存 ").append(formatBytes(memUsed)).append('/').append(formatBytes(memTotal));
        if (swapTotal > 0) sb.append(" · Swap ").append(formatBytes(swapUsed)).append('/').append(formatBytes(swapTotal));
        if (irisAvail >= 0) sb.append(" · 盘余 ").append(formatBytes(irisAvail));
        return sb.toString();
    }

    static String instanceBriefText(JSONObject perf) {
        if (perf == null || !perf.optBoolean("running")) return I18n.known("性能：实例未运行");
        String cpu = formatPctX10(perf.optInt("cpuPctX10", -1));
        long memUsage = perf.optLong("memUsageBytes", -1);
        long memLimit = perf.optLong("memLimitBytes", -1);
        long pids = perf.optLong("pidsCurrent", -1);
        long rxRate = perf.optLong("netRxBps", -1);
        long txRate = perf.optLong("netTxBps", -1);
        long fail = perf.optLong("memFailcnt", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("性能：CPU ").append(cpu);
        sb.append(" · 内存 ").append(formatBytes(memUsage));
        if (memLimit > 0) sb.append('/').append(formatBytes(memLimit));
        if (pids >= 0) sb.append(" · 线程 ").append(pids);
        sb.append(" · ↓").append(formatRate(rxRate)).append(" ↑").append(formatRate(txRate));
        if (fail > 0) sb.append(" · fail=").append(fail);
        return sb.toString();
    }

    static String fullscreenHudText(long[] metrics, JSONObject host, JSONObject inst, boolean hostLoading) {
        StringBuilder sb = new StringBuilder();
        if (metrics != null && metrics.length >= 7) {
            double rxFps = metrics[5] / 10.0;
            double txFps = metrics[6] / 10.0;
            long delayMs = metrics[4];
            long received = metrics[0];
            long withFence = metrics[3];
            int fencePct = received > 0 ? (int) Math.min(100L, (withFence * 100L) / received) : 100;
            sb.append(String.format(Locale.US, "FPS %.1f (合成 %.1f) · 延迟 %dms · Fence %d%% · 帧#%d",
                rxFps, txFps, delayMs, fencePct, received));
        } else {
            sb.append("FPS -- · 等待首帧…");
        }
        if (inst != null && inst.optBoolean("running")) {
            sb.append('\n');
            sb.append("CPU ").append(formatPctX10(inst.optInt("cpuPctX10", -1)));
            sb.append(" · 内存 ").append(formatBytes(inst.optLong("memUsageBytes", -1)));
            long lim = inst.optLong("memLimitBytes", -1);
            if (lim > 0) sb.append('/').append(formatBytes(lim));
            long swap = inst.optLong("memSwapBytes", -1);
            if (swap > 0) sb.append(" (Swap ").append(formatBytes(swap)).append(')');
            long pids = inst.optLong("pidsCurrent", -1);
            if (pids >= 0) sb.append(" · 线程 ").append(pids);
            sb.append('\n');
            sb.append("网络 ↓").append(formatRate(inst.optLong("netRxBps", 0))).append(" ↑").append(formatRate(inst.optLong("netTxBps", 0)));
        } else if (hostLoading) {
            sb.append("\n正在同步实例/宿主 Linux 指标…");
        }
        if (host != null) {
            if (inst != null && inst.optBoolean("running")) sb.append(" · ");
            else sb.append('\n');
            int gpuLoad = host.optInt("gpuLoadPct", -1);
            long gpuFreq = host.optLong("gpuFreqMHz", -1);
            sb.append("GPU ").append(gpuLoad >= 0 ? (gpuLoad + "%" + (gpuFreq > 0 ? "@" + gpuFreq + "M" : "")) : "--");
            sb.append(" · 温 ").append(formatTemp(host.optInt("maxTempMilliC", -1)));
            long irisAvail = host.optLong("irisAvailBytes", -1);
            if (irisAvail >= 0) sb.append(" · 盘余 ").append(formatBytes(irisAvail));
        }
        return sb.toString();
    }

    static String formatLinuxDetailText(int id, int number, String stage, JSONObject payload) {
        StringBuilder sb = new StringBuilder();
        JSONObject host = payload.optJSONObject("host");
        JSONObject inst = payload.optJSONObject("instance");

        sb.append("=== 实例 ").append(number).append("（ID ").append(id).append("）===\n");
        sb.append("当前阶段：").append(stage == null ? "--" : stage).append('\n');
        if (inst != null) {
            boolean running = inst.optBoolean("running");
            sb.append("运行会话：").append(running ? inst.optString("session", "--") : "已停止")
              .append(running ? " · 时长 " + formatDuration(inst.optLong("uptimeSec", -1)) : "").append('\n');
            if (running) {
                sb.append("CPU 占用：").append(formatPctX10(inst.optInt("cpuPctX10", -1)))
                  .append(" · 累计 ").append(String.format(Locale.US, "%.2fs", inst.optLong("cpuUsageUsec", 0) / 1e6))
                  .append(" (user ").append(String.format(Locale.US, "%.2fs", inst.optLong("cpuUserUsec", 0) / 1e6))
                  .append(" / sys ").append(String.format(Locale.US, "%.2fs", inst.optLong("cpuSystemUsec", 0) / 1e6)).append(")\n");
                sb.append("CPU 节流：nr_throttled=").append(inst.optLong("cpuThrottledNr", 0))
                  .append(" · throttled=").append(String.format(Locale.US, "%.2fs", inst.optLong("cpuThrottledUsec", 0) / 1e6)).append('\n');
                sb.append("Cgroup 内存：").append(formatBytes(inst.optLong("memUsageBytes", -1)))
                  .append(" / 上限 ").append(formatBytes(inst.optLong("memLimitBytes", -1)))
                  .append("（峰值 ").append(formatBytes(inst.optLong("memMaxBytes", -1))).append("）\n");
                sb.append("内存明细：RSS ").append(formatBytes(inst.optLong("memRssBytes", -1)))
                  .append(" · Cache ").append(formatBytes(inst.optLong("memCacheBytes", -1)))
                  .append(" · Swap ").append(formatBytes(inst.optLong("memSwapBytes", -1)))
                  .append(" · failcnt=").append(inst.optLong("memFailcnt", 0))
                  .append(" · majfault=").append(inst.optLong("memPgmajfault", -1)).append('\n');
                sb.append("线程数 (pids.current)：").append(inst.optLong("pidsCurrent", -1))
                  .append("（上限 ").append(inst.optLong("pidsMax", -1) < 0 ? "max" : Long.toString(inst.optLong("pidsMax"))).append("）\n");
                sb.append("虚拟网卡：").append(inst.optString("netIface", "--"))
                  .append(" · Guest IP ").append(inst.optString("guestIp", "--"))
                  .append("\n  实时速率：↓").append(formatRate(inst.optLong("netRxBps", 0)))
                  .append(" / ↑").append(formatRate(inst.optLong("netTxBps", 0)))
                  .append(" · 累计 ↓").append(formatBytes(inst.optLong("netRxBytes", -1)))
                  .append(" / ↑").append(formatBytes(inst.optLong("netTxBytes", -1))).append('\n');

                sb.append("\n=== Linux 内核隔离与压力 (PSI / UserNS / Cgroup) ===\n");
                sb.append("Cgroup v1：").append(inst.optString("cgroupV1Path", "--")).append('\n');
                sb.append("Cgroup v2：").append(inst.optString("cgroupV2Path", "--")).append('\n');
                sb.append("OverlayFS upper：").append(inst.optString("overlayUpperDir", "--")).append('\n');
                String ucfg = inst.optString("usernsConfigMap", "");
                String umap = inst.optString("procUidMap", "");
                sb.append("UserNS 配置：").append(ucfg.isEmpty() ? "--" : ucfg).append('\n');
                if (!umap.isEmpty()) sb.append("内核 /proc/").append(inst.optInt("initPid")).append("/uid_map：").append(umap).append('\n');
                String psiC = inst.optString("psiCpu", "").replace('\n', ' ');
                String psiM = inst.optString("psiMemory", "").replace('\n', ' ');
                String psiI = inst.optString("psiIo", "").replace('\n', ' ');
                if (!psiC.isEmpty()) sb.append("PSI CPU：").append(psiC).append('\n');
                if (!psiM.isEmpty()) sb.append("PSI MEM：").append(psiM).append('\n');
                if (!psiI.isEmpty()) sb.append("PSI IO ：").append(psiI).append('\n');

                JSONArray ns = inst.optJSONArray("namespaces");
                if (ns != null && ns.length() > 0) {
                    sb.append("7 大 Linux Namespace 隔离对比：\n");
                    for (int i = 0; i < ns.length(); i++) {
                        JSONObject item = ns.optJSONObject(i);
                        if (item == null) continue;
                        sb.append("  ").append(String.format(Locale.US, "%-7s", item.optString("name")))
                          .append(" -> ").append(item.optString("guest"))
                          .append(item.optBoolean("isolated") ? " [已隔离]\n" : " [共享]\n");
                    }
                }
            } else {
                sb.append("UserNS 配置：").append(inst.optString("usernsConfigMap", "--")).append('\n');
                sb.append("OverlayFS upper：").append(inst.optString("overlayUpperDir", "--")).append('\n');
            }
        }

        if (host != null) {
            sb.append("\n=== 宿主机硬件与系统参数 ===\n");
            String kr = host.optString("kernelRelease", "");
            String arch = host.optString("arch", "");
            if (!kr.isEmpty()) sb.append("Linux 内核：").append(kr).append(" (").append(arch).append(") · Uptime ").append(formatDuration(host.optLong("uptimeSec", -1))).append('\n');
            sb.append("CPU 总负载：").append(formatPctX10(host.optInt("cpuPctX10", -1)))
              .append("（").append(host.optInt("cpuCount", 0)).append(" 核） · LoadAvg ")
              .append(host.optString("loadAvg", "--")).append('\n');
            JSONArray freqs = host.optJSONArray("cpuFreqMHz");
            if (freqs != null && freqs.length() > 0) {
                sb.append("CPU 集群频率：");
                for (int i = 0; i < freqs.length(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(freqs.optLong(i)).append("MHz");
                }
                sb.append('\n');
            }
            sb.append("Mali GPU：负载 ").append(host.optInt("gpuLoadPct", -1) >= 0 ? host.optInt("gpuLoadPct") + "%" : "--")
              .append(" · 频率 ").append(host.optLong("gpuFreqMHz", -1) > 0 ? host.optLong("gpuFreqMHz") + "MHz" : "--").append('\n');
            long mTot = host.optLong("memTotalBytes", -1), mAvail = host.optLong("memAvailBytes", -1);
            long sTot = host.optLong("swapTotalBytes", 0), sFree = host.optLong("swapFreeBytes", 0);
            sb.append("宿主物理内存：已用 ").append(formatBytes(mTot > 0 && mAvail >= 0 ? mTot - mAvail : -1))
              .append(" / 总计 ").append(formatBytes(mTot))
              .append("（可用 ").append(formatBytes(mAvail)).append("）\n");
            sb.append("宿主 Swap：已用 ").append(formatBytes(Math.max(0L, sTot - sFree)))
              .append(" / 总计 ").append(formatBytes(sTot)).append('\n');
            sb.append("存储 /data/irisbox：可用 ").append(formatBytes(host.optLong("irisAvailBytes", -1)))
              .append(" / ").append(formatBytes(host.optLong("irisTotalBytes", -1))).append('\n');
            sb.append("存储 /data (TF)：可用 ").append(formatBytes(host.optLong("dataAvailBytes", -1)))
              .append(" / ").append(formatBytes(host.optLong("dataTotalBytes", -1))).append('\n');

            JSONArray zones = host.optJSONArray("thermalZones");
            if (zones != null && zones.length() > 0) {
                sb.append("板载温控传感器：\n  ");
                int printed = 0;
                for (int i = 0; i < zones.length(); i++) {
                    JSONObject z = zones.optJSONObject(i);
                    if (z == null) continue;
                    String type = z.optString("type");
                    if (type.contains("battery")) continue;
                    if (printed > 0) sb.append(printed % 3 == 0 ? "\n  " : " · ");
                    sb.append(type).append('=').append(formatTemp(z.optInt("tempMilliC", -1)));
                    printed++;
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static JSONObject fetchTelemetry(String token, int id, boolean brief) throws Exception {
        String path = "/telemetry?id=" + id + (brief ? "&brief=1" : "");
        return brokerRequest(token, "GET", path);
    }

    static void triggerRecents(String token, int id, String session) throws Exception {
        brokerRequest(token, "POST", "/recents?id=" + id + "&session=" + session);
    }

    private static JSONObject brokerRequest(String token, String method, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:19762" + path).openConnection();
        try {
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
            }
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("Broker HTTP " + code);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 512 * 1024) throw new IOException("Telemetry reply too large");
                    out.write(buf, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
    }

    static void showLinuxMonitorDialog(Activity activity, String token, int id, int number, String stageSnapshot) {
        if (token == null || !token.matches("[a-f0-9]{64}")) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.rgb(18, 22, 28));
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        TextView content = new TextView(activity);
        content.setTextColor(Color.rgb(220, 232, 242));
        content.setTextSize(12);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(pad, pad, pad, pad);
        content.setFocusable(false);
        content.setFocusableInTouchMode(false);
        content.setText(I18n.known("正在采集 Linux 与性能参数…"));
        scroll.addView(content);

        Handler ui = new Handler(Looper.getMainLooper());
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        final String[] latestText = {""};
        final boolean[] touching = {false};
        scroll.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) touching[0] = true;
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching[0] = false;
            return false;
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(InstanceNames.title(number) + I18n.known(" · Linux 性能与虚拟化监控"))
            .setView(scroll)
            .setPositiveButton(I18n.known("复制参数"), (d, w) -> {
                if (!latestText[0].isEmpty()) {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("irisbox-linux-telemetry", latestText[0]));
                        Toast.makeText(activity, I18n.known("已复制 Linux 参数详情"), Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton(android.R.string.ok, null)
            .create();

        dialog.setOnDismissListener(d -> worker.shutdownNow());
        worker.scheduleWithFixedDelay(() -> {
            if (!dialog.isShowing() && worker.isShutdown()) return;
            try {
                JSONObject data = fetchTelemetry(token, id, false);
                String formatted = formatLinuxDetailText(id, number, stageSnapshot, data);
                ui.post(() -> {
                    if (!dialog.isShowing() || touching[0] || formatted.equals(latestText[0])) return;
                    final int keepY = scroll.getScrollY();
                    latestText[0] = formatted;
                    content.setText(formatted);
                    if (keepY > 0) scroll.post(() -> scroll.scrollTo(0, keepY));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (dialog.isShowing() && latestText[0].isEmpty()) {
                        content.setText(I18n.known("参数读取失败：") + e.getMessage());
                    }
                });
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        dialog.show();
    }
}
