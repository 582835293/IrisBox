package com.irisbox.buffer.viewer.broker;

import android.os.StatFs;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主与实例 Linux/Cgroup 遥测采集器（纯只读文件采样，零子进程 fork）。
 * 采集范围：
 * 1. 宿主机：CPU 增量利用率、集群频率、Mali GPU 频率/负载、SoC/核心温控、内存/Swap、LoadAvg、存储盘余量；
 * 2. 单实例轻量指标：Cgroup v1 内存/Swap/failcnt、Cgroup v2 线程数与 CPU 利用率%、虚拟网卡收发速率；
 * 3. 单实例深度细节（按需）：Linux PSI 压力、memory.stat、UserNS UID 映射、7 大 Namespace 隔离状态对照。
 */
final class LinuxTelemetry {
    private static final String BASE = "/data/irisbox/runtime";
    private static final int CPU_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final long CLK_TCK = Math.max(1L, Os.sysconf(OsConstants._SC_CLK_TCK));

    private long prevHostTotal = -1, prevHostIdle = -1;
    private int cachedHostCpuPctX10 = 0;
    private final Object hostCpuLock = new Object();

    private static final class InstanceSample {
        final String session;
        long wallNs;
        long cpuUsec;
        long rxBytes;
        long txBytes;
        int cpuPctX10;
        long rxBps;
        long txBps;

        InstanceSample(String s) { session = s; }
    }

    private final ConcurrentHashMap<Integer, InstanceSample> samples = new ConcurrentHashMap<>();

    private static String readSmall(Path path, int maxBytes) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) return "";
            byte[] all = Files.readAllBytes(path);
            if (all.length > maxBytes) return new String(all, 0, maxBytes, StandardCharsets.UTF_8).trim();
            return new String(all, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读取 sysfs 文本文件（部分 /sys 或 /proc 节点不支持预先精确 Files.size，直接有界读取） */
    private static String readKernel(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "";
            byte[] all = Files.readAllBytes(path);
            return new String(all, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static Map<String, Long> parseKeyLongMap(String text) {
        HashMap<String, Long> map = new HashMap<>();
        if (text == null || text.isEmpty()) return map;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(" +");
            if (parts.length >= 2) {
                long v = parseLongSafe(parts[1], -1);
                if (v >= 0) map.put(parts[0], v);
            }
        }
        return map;
    }

    private int sampleHostCpuPctX10() {
        String stat = readKernel(Paths.get("/proc/stat"));
        for (String line : stat.split("\n")) {
            if (!line.startsWith("cpu ")) continue;
            String[] cols = line.trim().split(" +");
            if (cols.length < 5) break;
            long total = 0;
            for (int i = 1; i < cols.length; i++) total += Math.max(0, parseLongSafe(cols[i], 0));
            long idle = Math.max(0, parseLongSafe(cols[4], 0)) + (cols.length > 5 ? Math.max(0, parseLongSafe(cols[5], 0)) : 0);
            synchronized (hostCpuLock) {
                if (prevHostTotal >= 0 && total > prevHostTotal) {
                    long dt = total - prevHostTotal;
                    long di = Math.max(0, idle - prevHostIdle);
                    long busy = Math.max(0, dt - di);
                    cachedHostCpuPctX10 = (int) Math.min(1000L, (busy * 1000L) / dt);
                }
                prevHostTotal = total;
                prevHostIdle = idle;
                return cachedHostCpuPctX10;
            }
        }
        return cachedHostCpuPctX10;
    }

    private static long[] statFs(String dir) {
        try {
            StatFs s = new StatFs(dir);
            return new long[]{s.getTotalBytes(), s.getAvailableBytes()};
        } catch (Exception e) {
            return new long[]{-1L, -1L};
        }
    }

    private static long readHostUptimeSeconds() {
        String up = readKernel(Paths.get("/proc/uptime"));
        if (up.isEmpty()) return -1L;
        String first = up.split(" +")[0];
        int dot = first.indexOf('.');
        return parseLongSafe(dot >= 0 ? first.substring(0, dot) : first, -1L);
    }

    /** 采集宿主机硬件与系统摘要 */
    JSONObject hostSnapshot(boolean detailed) throws Exception {
        JSONObject out = new JSONObject();
        out.put("cpuPctX10", sampleHostCpuPctX10());
        out.put("cpuCount", CPU_COUNT);

        // 各 CPU 调频策略当前频率（MHz）
        JSONArray freqs = new JSONArray();
        try {
            Path cpufreqDir = Paths.get("/sys/devices/system/cpu/cpufreq");
            if (Files.isDirectory(cpufreqDir)) {
                List<String> policies = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(cpufreqDir, "policy*")) {
                    for (Path p : ds) policies.add(p.getFileName().toString());
                }
                Collections.sort(policies);
                for (String name : policies) {
                    long khz = parseLongSafe(readKernel(cpufreqDir.resolve(name).resolve("scaling_cur_freq")), -1);
                    if (khz > 0) freqs.put(khz / 1000);
                }
            }
        } catch (Exception ignored) {}
        out.put("cpuFreqMHz", freqs);

        // /proc/meminfo
        Map<String, Long> mem = new HashMap<>();
        for (String line : readKernel(Paths.get("/proc/meminfo")).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            String num = rest.split(" +")[0];
            long kb = parseLongSafe(num, -1);
            if (kb >= 0) mem.put(key, kb * 1024L);
        }
        long memTotal = mem.getOrDefault("MemTotal", -1L);
        long memAvail = mem.getOrDefault("MemAvailable", -1L);
        long swapTotal = mem.getOrDefault("SwapTotal", 0L);
        long swapFree = mem.getOrDefault("SwapFree", 0L);
        out.put("memTotalBytes", memTotal);
        out.put("memAvailBytes", memAvail);
        out.put("memUsedPctX10", memTotal > 0 && memAvail >= 0 ? (int) (((memTotal - memAvail) * 1000L) / memTotal) : -1);
        out.put("swapTotalBytes", swapTotal);
        out.put("swapFreeBytes", swapFree);

        // Mali GPU devfreq 负载与频率
        int gpuLoadPct = -1;
        long gpuFreqMHz = -1;
        try {
            Path devfreq = Paths.get("/sys/class/devfreq");
            if (Files.isDirectory(devfreq)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(devfreq)) {
                    for (Path p : ds) {
                        if (!p.getFileName().toString().contains("gpu")) continue;
                        String load = readKernel(p.resolve("load"));
                        if (!load.isEmpty() && load.contains("@")) {
                            String[] parts = load.split("@", 2);
                            gpuLoadPct = (int) parseLongSafe(parts[0], -1);
                            String hzStr = parts[1].replace("Hz", "").trim();
                            long hz = parseLongSafe(hzStr, -1);
                            if (hz > 0) gpuFreqMHz = hz / 1000000L;
                        }
                        if (gpuFreqMHz < 0) {
                            long hz = parseLongSafe(readKernel(p.resolve("cur_freq")), -1);
                            if (hz > 0) gpuFreqMHz = hz / 1000000L;
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
        out.put("gpuLoadPct", gpuLoadPct);
        out.put("gpuFreqMHz", gpuFreqMHz);

        // 温控区域
        int maxTemp = Integer.MIN_VALUE;
        JSONArray zones = detailed ? new JSONArray() : null;
        try {
            Path thermal = Paths.get("/sys/class/thermal");
            if (Files.isDirectory(thermal)) {
                List<Path> list = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(thermal, "thermal_zone*")) {
                    for (Path p : ds) list.add(p);
                }
                Collections.sort(list, Comparator.comparing(p -> p.getFileName().toString()));
                for (Path z : list) {
                    String type = readKernel(z.resolve("type"));
                    int temp = (int) parseLongSafe(readKernel(z.resolve("temp")), Integer.MIN_VALUE);
                    if (type.isEmpty() || temp == Integer.MIN_VALUE) continue;
                    if (zones != null) {
                        zones.put(new JSONObject().put("zone", z.getFileName().toString()).put("type", type).put("tempMilliC", temp));
                    }
                    // 排除非板载电池伪传感读数
                    if (!type.contains("battery") && temp > 0 && temp < 130000 && temp > maxTemp) {
                        maxTemp = temp;
                    }
                }
            }
        } catch (Exception ignored) {}
        out.put("maxTempMilliC", maxTemp == Integer.MIN_VALUE ? -1 : maxTemp);
        if (zones != null) out.put("thermalZones", zones);

        // 存储空间（SSD /data/irisbox 与内部 /data）
        long[] irisFs = statFs("/data/irisbox");
        long[] dataFs = statFs("/data");
        out.put("irisTotalBytes", irisFs[0]);
        out.put("irisAvailBytes", irisFs[1]);
        out.put("dataTotalBytes", dataFs[0]);
        out.put("dataAvailBytes", dataFs[1]);

        // LoadAvg
        String loadAvgRaw = readKernel(Paths.get("/proc/loadavg"));
        if (!loadAvgRaw.isEmpty()) {
            String[] parts = loadAvgRaw.split(" +");
            if (parts.length >= 3) out.put("loadAvg", parts[0] + " " + parts[1] + " " + parts[2]);
        }

        if (detailed) {
            try {
                out.put("kernelRelease", Os.uname().release);
                out.put("arch", Os.uname().machine);
            } catch (Exception ignored) {}
            out.put("uptimeSec", readHostUptimeSeconds());
        }
        return out;
    }

    /** 采集单实例轻量性能指标（用于列表卡片与全屏实时 HUD） */
    JSONObject instancePerfBrief(int id, String session) throws Exception {
        JSONObject out = new JSONObject();
        if (session == null || session.isEmpty()) {
            samples.remove(id);
            return out.put("running", false);
        }
        out.put("running", true);

        // 1. Memory Cgroup v1
        Path memcg = Paths.get("/dev/memcg/irisbox_" + id);
        long memUsage = parseLongSafe(readKernel(memcg.resolve("memory.usage_in_bytes")), -1);
        long memMax = parseLongSafe(readKernel(memcg.resolve("memory.max_usage_in_bytes")), -1);
        long memLimit = parseLongSafe(readKernel(memcg.resolve("memory.limit_in_bytes")), -1);
        long memFail = parseLongSafe(readKernel(memcg.resolve("memory.failcnt")), -1);
        long memswUsage = parseLongSafe(readKernel(memcg.resolve("memory.memsw.usage_in_bytes")), -1);
        long memSwap = (memUsage >= 0 && memswUsage >= memUsage) ? (memswUsage - memUsage) : -1;
        out.put("memUsageBytes", memUsage);
        out.put("memMaxBytes", memMax);
        out.put("memLimitBytes", memLimit);
        out.put("memFailcnt", memFail);
        out.put("memSwapBytes", memSwap);

        // 2. Cgroup v2 PIDs 与 CPU 增量速率
        Path cg2 = Paths.get("/sys/fs/cgroup/irisbox_" + id);
        long pidsCur = parseLongSafe(readKernel(cg2.resolve("managed/pids.current")), -1);
        String pidsMaxStr = readKernel(cg2.resolve("managed/pids.max"));
        long pidsMax = "max".equals(pidsMaxStr) ? -1 : parseLongSafe(pidsMaxStr, -1);
        out.put("pidsCurrent", pidsCur);
        out.put("pidsMax", pidsMax);

        Map<String, Long> cpuStat = parseKeyLongMap(readKernel(cg2.resolve("cpu.stat")));
        long cpuUsec = cpuStat.getOrDefault("usage_usec", -1L);
        long nrThrottled = cpuStat.getOrDefault("nr_throttled", 0L);

        // 3. 虚拟网卡统计
        String iface = "rv" + id + "h";
        Path netStats = Paths.get("/sys/class/net", iface, "statistics");
        long rxBytes = parseLongSafe(readKernel(netStats.resolve("rx_bytes")), -1);
        long txBytes = parseLongSafe(readKernel(netStats.resolve("tx_bytes")), -1);

        long nowNs = SystemClock.elapsedRealtimeNanos();
        InstanceSample prev = samples.get(id);
        if (prev == null || !session.equals(prev.session)) {
            InstanceSample fresh = new InstanceSample(session);
            fresh.wallNs = nowNs;
            fresh.cpuUsec = cpuUsec;
            fresh.rxBytes = rxBytes;
            fresh.txBytes = txBytes;
            samples.put(id, fresh);
            out.put("cpuPctX10", 0);
            out.put("netRxBps", 0L);
            out.put("netTxBps", 0L);
        } else {
            long elapsedNs = nowNs - prev.wallNs;
            if (elapsedNs >= 400_000_000L) {
                double sec = elapsedNs / 1e9;
                if (cpuUsec >= 0 && prev.cpuUsec >= 0 && cpuUsec >= prev.cpuUsec) {
                    long dCpu = cpuUsec - prev.cpuUsec;
                    prev.cpuPctX10 = (int) Math.min(1000L, Math.round((dCpu / (sec * 1e6 * CPU_COUNT)) * 1000.0));
                }
                if (rxBytes >= 0 && prev.rxBytes >= 0 && rxBytes >= prev.rxBytes) {
                    prev.rxBps = Math.round((rxBytes - prev.rxBytes) / sec);
                }
                if (txBytes >= 0 && prev.txBytes >= 0 && txBytes >= prev.txBytes) {
                    prev.txBps = Math.round((txBytes - prev.txBytes) / sec);
                }
                prev.wallNs = nowNs;
                prev.cpuUsec = cpuUsec;
                prev.rxBytes = rxBytes;
                prev.txBytes = txBytes;
            }
            out.put("cpuPctX10", prev.cpuPctX10);
            out.put("netRxBps", prev.rxBps);
            out.put("netTxBps", prev.txBps);
        }
        out.put("cpuThrottledNr", nrThrottled);
        out.put("netIface", iface);
        out.put("netRxBytes", rxBytes);
        out.put("netTxBytes", txBytes);

        // 4. 运行时长（秒）
        String[] ident = session.split(":");
        if (ident.length == 2) {
            long startTick = parseLongSafe(ident[1], -1);
            long hostUp = readHostUptimeSeconds();
            if (startTick > 0 && hostUp >= 0) {
                out.put("uptimeSec", Math.max(0L, hostUp - (startTick / CLK_TCK)));
            }
        }
        return out;
    }

    /** 采集单实例深度 Linux 内核参数（Namespace / UserNS UID Map / PSI / memory.stat / Cgroup） */
    JSONObject instanceFullDetail(int id, String session) throws Exception {
        JSONObject out = instancePerfBrief(id, session);
        out.put("id", id);
        out.put("session", session);
        out.put("guestIp", "172.30." + (id - 1000) + ".2");
        out.put("cgroupV1Path", "/dev/memcg/irisbox_" + id + "/managed");
        out.put("cgroupV2Path", "/sys/fs/cgroup/irisbox_" + id + "/managed");
        out.put("overlayUpperDir", BASE + "/instances/" + id + "/upper");

        String savedUidMap = readSmall(Paths.get(BASE, "config/userns_v1", id + ".map"), 128);
        out.put("usernsConfigMap", savedUidMap);

        if (session == null || session.isEmpty()) return out;

        Path cg2 = Paths.get("/sys/fs/cgroup/irisbox_" + id);
        Map<String, Long> cpuStat = parseKeyLongMap(readKernel(cg2.resolve("cpu.stat")));
        out.put("cpuUsageUsec", cpuStat.getOrDefault("usage_usec", -1L));
        out.put("cpuUserUsec", cpuStat.getOrDefault("user_usec", -1L));
        out.put("cpuSystemUsec", cpuStat.getOrDefault("system_usec", -1L));
        out.put("cpuThrottledUsec", cpuStat.getOrDefault("throttled_usec", 0L));

        out.put("psiCpu", readKernel(cg2.resolve("cpu.pressure")));
        out.put("psiMemory", readKernel(cg2.resolve("memory.pressure")));
        out.put("psiIo", readKernel(cg2.resolve("io.pressure")));

        Map<String, Long> memStat = parseKeyLongMap(readKernel(Paths.get("/dev/memcg/irisbox_" + id, "memory.stat")));
        out.put("memRssBytes", memStat.getOrDefault("total_rss", memStat.getOrDefault("rss", -1L)));
        out.put("memCacheBytes", memStat.getOrDefault("total_cache", memStat.getOrDefault("cache", -1L)));
        out.put("memPgfault", memStat.getOrDefault("total_pgfault", memStat.getOrDefault("pgfault", -1L)));
        out.put("memPgmajfault", memStat.getOrDefault("total_pgmajfault", memStat.getOrDefault("pgmajfault", -1L)));

        String pid = session.split(":")[0];
        if (pid.matches("[0-9]+")) {
            out.put("initPid", Integer.parseInt(pid));
            out.put("procUidMap", readKernel(Paths.get("/proc", pid, "uid_map")).replaceAll(" +", " "));

            JSONArray nsList = new JSONArray();
            for (String ns : new String[]{"user", "pid", "mnt", "net", "ipc", "uts", "cgroup"}) {
                try {
                    String hostNs = Files.readSymbolicLink(Paths.get("/proc/1/ns", ns)).toString();
                    String guestNs = Files.readSymbolicLink(Paths.get("/proc", pid, "ns", ns)).toString();
                    nsList.put(new JSONObject()
                        .put("name", ns)
                        .put("guest", guestNs)
                        .put("isolated", !guestNs.equals(hostNs)));
                } catch (Exception ignored) {}
            }
            out.put("namespaces", nsList);
        }
        return out;
    }
}
