package com.rootvmos.buffer.viewer.broker;

import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * 宿主管理后台守护进程（BufferBroker）：
 * 由 manager.sh 以宿主 root 身份通过 Android 原生 app_process 启动，驻留后台。
 * 前台查看器 App（ListActivity）通过回环网络 127.0.0.1:19762 经 HTTP 协议与之通信。
 * 
 * 【架构职责与核心边界】：
 * 1. 状态聚合：向前台提供各虚拟机实例的状态、内存、分辨率、Root 及实时小窗预览；
 * 2. 串行调度：单线程操作排队（actions 线程池），防止并发破坏实例数据；
 * 3. 启动监测：持续巡检 Guest init 存活、boot_completed、真实桌面 HOME 状态；
 * 4. 架构解耦：全屏的高帧率像素数据与触控输入绝不经过此处，由 gateway 移交给独立的 display.sh 与 producer 进程。
 * 5. 安全准则：HTTP 202 仅表示操作已受理并排队，最终结果由后续 state 轮询决定；绝不在此复制底层脚本逻辑。
 */
public final class BufferBroker {
    /** 基础路径、工作目录与 busybox 执行文件常量 */
    private static final String BASE = "/data/rootvmos/runtime", DIR = BASE + "/display/buffer_viewer", BB = "/data/rootvmos/host/bin/busybox";

    /** 本次启动生成的 64 字符随机认证 Token（防宿主未授权应用越权访问） */
    private final String token = randomToken();

    /** 实例维度的运行态错误信息、已就绪会话映射（实例ID -> session） */
    private final ConcurrentHashMap<Integer, String> errors = new ConcurrentHashMap<>(), readySessions = new ConcurrentHashMap<>();

    /** 记录分辨率应用失败的会话，避免在同一轮次中无休止重复尝试 */
    private final ConcurrentHashMap<Integer, String> resolutionFailures = new ConcurrentHashMap<>();

    /** 支持的分辨率预设档位 */
    private static final List<String> RESOLUTIONS = Arrays.asList("legacy", "960x540", "1280x720", "1600x900", "1920x1080");
    private static final List<String> ORIENTATIONS = Arrays.asList("auto", "landscape", "portrait");

    private static String savedOrientation(int id) throws IOException {
        Path p = Paths.get(BASE, "config/orientation_v1", id + ".mode");
        if (Files.isSymbolicLink(p) || Files.isSymbolicLink(p.getParent()) || Files.isSymbolicLink(p.getParent().getParent()))
            throw new IOException("Invalid orientation file");
        String mode = Files.exists(p) ? read(p, 16) : "auto";
        if (!ORIENTATIONS.contains(mode)) throw new IOException("Invalid orientation mode");
        return mode;
    }

    private static JSONObject orientationState(int id) throws Exception {
        JSONObject result = new JSONObject();
        try { result.put("mode", savedOrientation(id)).put("valid", true); }
        catch (IOException failure) { result.put("mode", "invalid").put("valid", false); }
        return result;
    }

    /** 将分辨率标识转换为前台 UI 友好展示的中文字符串 */
    private static String resolutionLabel(String profile) {
        switch (profile) {
            case "legacy": return "旧版兼容：1080p 画面缩小到 540p 显示";
            case "960x540": return "960×540 · 流畅优先";
            case "1280x720": return "1280×720 · 720p，清晰度与开销折中";
            case "1600x900": return "1600×900 · 900p，更清晰";
            default: return "1920×1080 · 1080p，开销最大";
        }
    }

    /** 校验内存数值合法性：必须为 512 ~ 2560 MiB 之间的合法整型数字 */
    private static boolean validMemory(String value) {
        return value.matches("[1-9][0-9]{2,3}") && Integer.parseInt(value) >= 512 && Integer.parseInt(value) <= 2560;
    }

    /** 读取保存的静态内存配置（严格检查符号链接，防路径遍历安全攻击） */
    private static int savedMemory(int id) throws IOException {
        Path p = Paths.get(BASE, "config/memory_v1", id + ".mib");
        if (Files.isSymbolicLink(p) || Files.isSymbolicLink(p.getParent()) || Files.isSymbolicLink(p.getParent().getParent()))
            throw new IOException("Invalid memory file");
        String value = Files.exists(p) ? read(p, 8) : "1280";
        if (!validMemory(value)) throw new IOException("Invalid memory limit");
        return Integer.parseInt(value);
    }

    /** 聚合该实例当前的内存配置状态，并读取当前 cgroup 中真实生效的字节配额 */
    private static JSONObject memoryState(int id, String session) throws Exception {
        JSONObject result = new JSONObject().put("minMiB", 512).put("maxMiB", 2560).put("defaultMiB", 1280).put("effectiveMiB", 0);
        try {
            result.put("mib", savedMemory(id)).put("valid", true);
        } catch (IOException failure) {
            result.put("mib", -1).put("valid", false);
        }
        if (!session.isEmpty()) try {
            long bytes = Long.parseLong(read(Paths.get("/dev/memcg/rootvmos_" + id + "/memory.limit_in_bytes"), 32));
            if (bytes % 1048576 == 0 && bytes >= 512L * 1048576 && bytes <= 2560L * 1048576 && session.equals(session(id)))
                result.put("effectiveMiB", bytes / 1048576);
        } catch (IOException | NumberFormatException ignored) {}
        return result;
    }

    /** 读取保存的分辨率预设档位（严格校验软链接与合法性） */
    private static String savedResolution(int id) throws IOException {
        Path p = Paths.get(BASE, "config/resolution_v1", id + ".profile");
        if (Files.isSymbolicLink(p) || Files.isSymbolicLink(p.getParent()))
            throw new IOException("Invalid resolution file");
        String profile = Files.exists(p) ? read(p, 32) : "legacy";
        if (!RESOLUTIONS.contains(profile)) throw new IOException("Invalid resolution preset");
        return profile;
    }

    /** 聚合分辨率状态信息（含预设值与运行时从 resolution.effective 读取的真实生效尺寸） */
    private static JSONObject resolutionState(int id, String session) throws Exception {
        JSONObject result = new JSONObject();
        try {
            String profile = savedResolution(id);
            result.put("profile", profile).put("label", resolutionLabel(profile)).put("valid", true);
        } catch (IOException failure) {
            result.put("profile", "invalid").put("label", "分辨率配置损坏，请恢复默认").put("valid", false);
        }
        result.put("effective", "");
        if (!session.isEmpty()) try {
            String[] f = read(instance(id).resolve("resolution.effective"), 256).split(" +");
            if (f.length == 7 && f[0].equals(session) && RESOLUTIONS.contains(f[1]))
                result.put("effective", f[2] + "×" + f[3] + (f[2].equals(f[4]) && f[3].equals(f[5]) ? "" : "（缩小到 " + f[4] + "×" + f[5] + " 显示）"));
        } catch (IOException ignored) {}
        return result;
    }

    /** 记录最近一次进行桌面引导检测的时间戳，限制检测频率在 2 秒以上 */
    private final ConcurrentHashMap<Integer, Long> lastBootChecks = new ConcurrentHashMap<>();

    /** HTTP 请求处理线程池（限制 4 个工作线程，队列容量 8，避免突发高频请求挤占资源） */
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8));

    /** 读取 Magisk Root 配置方案（off, magisk, magisk-safe） */
    private static String savedRoot(int id) throws Exception {
        Path p = Paths.get(BASE, "config/root_v1", id + ".profile");
        if (Files.isSymbolicLink(p) || Files.isSymbolicLink(p.getParent()) || Files.isSymbolicLink(p.getParent().getParent()))
            throw new IOException("Invalid root option file");
        String value = Files.exists(p) ? read(p, 16) : "off";
        if (!Arrays.asList("off", "magisk", "magisk-safe").contains(value)) throw new IOException("Invalid root profile");
        return value;
    }

    /** 聚合该实例 Root 状态：检查配置、检查 data/adb/iris.status 状态并探测 magiskd 守护套接字 */
    private static JSONObject rootState(int id, String session) throws Exception {
        JSONObject result = new JSONObject().put("profile", "invalid").put("valid", false).put("ready", false).put("status", "UNKNOWN");
        try {
            String profile = savedRoot(id);
            result.put("profile", profile).put("valid", true);
            if (profile.equals("off")) { result.put("status", "OFF"); return result; }
            if (session.isEmpty()) { result.put("status", "STOPPED"); return result; }
            String status = read(instance(id).resolve("data/adb/iris.status"), 16);
            boolean socket = Files.exists(Paths.get("/proc", session.split(":")[0], "root/sbin/.magisk/device/socket"));
            result.put("ready", status.equals("READY") && socket).put("status", socket ? status : "STARTING");
        } catch (Exception ignored) {}
        return result;
    }

    /** 核心操作单线程池：所有修改/启停指令严格单线程排队执行，保证生命周期完全串行化 */
    private final ThreadPoolExecutor actions = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4));

    /** 后台状态监控单线程定时调度器（每秒轮询一次） */
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

    /** 显示与小窗预览操作全局互斥锁 */
    private final Object displayLock = new Object();

    /** 记录当前各实例正在执行的繁忙动作类型（如 "start", "stop", "reset" 等） */
    private final ConcurrentHashMap<Integer, String> busy = new ConcurrentHashMap<>();

    /** 宿主与 Guest 间的大文件导入/导出管理组件 */
    private final ExchangeFiles files = new ExchangeFiles(new ExchangeFiles.State() {
        public String session(int id) { return BufferBroker.session(id); }
        public boolean ready(int id, String expected) { return expected.equals(readySessions.get(id)); }
        public boolean busy(int id) { return BufferBroker.this.busy.containsKey(id); }
        public boolean stopping(int id) { return "stop".equals(BufferBroker.this.busy.get(id)); }
    });

    /** 记录各实例当前的启动轮次观测对象（用于收集引导日志） */
    private final ConcurrentHashMap<Integer, BootAttempt> attempts = new ConcurrentHashMap<>();

    /** 小窗预览捕获专用单线程池 */
    private final ThreadPoolExecutor previews = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2));

    /** 预览图请求超时控制调度器 */
    private final ScheduledExecutorService previewDeadlines = Executors.newSingleThreadScheduledExecutor();

    /** 缩略图内存缓存、活跃任务与最近捕获尝试时间戳 */
    private final HashMap<Integer, Preview> previewCache = new HashMap<>();
    private final HashMap<Integer, PreviewJob> previewJobs = new HashMap<>();
    private final HashMap<Integer, Long> previewAttempts = new HashMap<>();
    private long previewSequence;

    /** 缓存的单帧 JPEG 缩略图数据结构 */
    private static final class Preview {
        final String session;
        final long sequence, captured;
        final byte[] jpeg;
        Preview(String s, long q, long c, byte[] j) { session = s; sequence = q; captured = c; jpeg = j; }
    }

    /** 异步缩略图捕获任务描述 */
    private static final class PreviewJob {
        final int id;
        final String session;
        volatile boolean cancelled;
        PreviewJob(int i, String s) { id = i; session = s; }
    }

    /** 当前正在打开全屏画面的实例 ID */
    private volatile int opening;

    /** 标记是否有清理任务已在排队、服务是否正在退出 */
    private volatile boolean cleanupQueued, closing;

    /** 实例目录全局创建锁状态与错误信息 */
    private volatile String catalogBusy = "", catalogError = "";
    private volatile int lastCreatedId;

    /** 生成 32 字节密码学安全的十六进制随机会话 Token */
    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(Locale.US, "%02x", v & 255));
        return s.toString();
    }

    /** 安全读取文件内容，带有严格的大小上限保护（防止超大异常文件撑爆内存） */
    private static String read(Path p, int max) throws IOException {
        if (Files.size(p) > max) throw new IOException("State exceeds limit");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
    }

    /** 获取指定实例的本地文件系统路径 */
    private static Path instance(int id) { return Paths.get(BASE, "instances", Integer.toString(id)); }

    /** 读取进程的启动时钟滴答数（start tick），结合排除僵尸状态，作为防 PID 复用唯一凭证 */
    private static String tick(String pid) throws IOException {
        String stat = read(Paths.get("/proc", pid, "stat"), 4096);
        String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
        if (f[0].equals("Z")) throw new IOException("Exited process");
        return f[19];
    }

    /**
     * 【核心函数：session(id)】
     * 从 Linux 内核真实 /proc 严密核验目标实例的真实运行状态：
     * 1. 读取 init.identity 中的 PID，并核验 stat 里的 start tick 是否完全匹配；
     * 2. 检查 /proc/PID/cmdline 包含 /system/bin/iris_init；
     * 3. 检查 /proc/PID/status 中该进程在其所属空间内的 PID 是否为 1（NSpid: 1）；
     * 4. 检查 6 个命名空间已彻底脱离宿主 1 号进程；
     * 5. 检查 mountinfo 包含本实例的 upperdir，且 cgroup 归属于本实例 managed 子组；
     * 任何一项不匹配立即返回空字符串，绝不伪造在线状态。
     * 
     * @return 校验通过返回 "PID:START_TICK"；未运行或校验失败返回 ""
     */
    private static String session(int id) {
        try {
            String[] ident = read(instance(id).resolve("init.identity"), 128).split(" +");
            if (ident.length != 2 || !ident[0].matches("[0-9]+") || !ident[1].matches("[0-9]+")) return "";
            String pid = ident[0];
            if (!tick(pid).equals(ident[1])) return "";
            if (!read(Paths.get("/proc", pid, "cmdline"), 4096).contains("/system/bin/iris_init")) return "";
            boolean pidOne = false;
            for (String line : read(Paths.get("/proc", pid, "status"), 16384).split("\n"))
                if (line.startsWith("NSpid:")) pidOne = line.trim().matches(".*\\s1");
            if (!pidOne) return "";
            for (String ns : new String[]{"pid", "mnt", "net", "ipc", "uts", "cgroup"})
                if (Files.readSymbolicLink(Paths.get("/proc", pid, "ns", ns)).equals(Files.readSymbolicLink(Paths.get("/proc/1/ns", ns)))) return "";
            String mounts = read(Paths.get("/proc", pid, "mountinfo"), 262144), groups = read(Paths.get("/proc", pid, "cgroup"), 16384);
            if (!mounts.contains("upperdir=" + instance(id) + "/upper,") ||
                !groups.contains("0::/rootvmos_" + id + "/managed") ||
                !groups.contains(":memory:/rootvmos_" + id + "/managed")) return "";
            return pid + ":" + ident[1];
        } catch (Exception e) { return ""; }
    }

    /** 检查辅助进程（如查看器组件或缓冲区生产者）是否存活且命令行符合预期 */
    private static boolean process(Path identity, String command, boolean exact) {
        try {
            String[] v = read(identity, 128).split(" +");
            if (v.length != 2 || !v[0].matches("[0-9]+") || !v[1].equals(tick(v[0]))) return false;
            String cmd = read(Paths.get("/proc", v[0], "cmdline"), 4096);
            return exact ? cmd.equals(command) : cmd.contains(command);
        } catch (Exception e) { return false; }
    }

    private static JSONObject audioState(int id, String expected) throws Exception {
        JSONObject result = new JSONObject().put("ready", false).put("status", expected.isEmpty() ? "STOPPED" : "WAITING");
        if (expected.isEmpty()) return result;
        try {
            Path run = Paths.get(read(instance(id).resolve("latest.logdir"), 512));
            if (!run.normalize().equals(run) || !run.startsWith(instance(id)) || !run.toRealPath().equals(run) ||
                !read(run.resolve("init.identity"), 128).replace(' ', ':').equals(expected)) return result;
            Path status = run.resolve("audio/host.status");
            if (!process(run.resolve("audio/player.identity"), "com.rootvmos.audio.AudioBridge", false) ||
                System.currentTimeMillis() - Files.getLastModifiedTime(status).toMillis() > 5000) return result;
            for (String field : read(status, 256).split(" +")) {
                String[] pair = field.split("=");
                if (pair.length == 2 && Arrays.asList("bytes", "nonzero", "peak", "head").contains(pair[0]))
                    result.put(pair[0], Long.parseLong(pair[1]));
            }
            boolean ready = result.optLong("bytes") > 0 && result.optLong("head") > 0;
            result.put("ready", ready).put("status", ready ? "PLAYING" : "CONNECTING");
        } catch (Exception ignored) {}
        return result;
    }

    /** 描述当前活动的全屏显示会话 */
    private static final class Display {
        final Path run;
        final int id;
        final String session;
        final boolean active;
        Display(Path r, int i, String s, boolean a) { run = r; id = i; session = s; active = a; }
    }

    /** 探测当前宿主上是否正有处于活动状态的全屏投屏/查看会话 */
    private static Display display() {
        try {
            Path r = Paths.get(read(Paths.get(DIR, "current"), 512)).normalize();
            if (!r.startsWith(Paths.get(DIR, "runs")) || !r.toRealPath().equals(r)) return null;
            if (read(r.resolve("state"), 32).equals("STOPPED")) return null;
            int id = Integer.parseInt(read(r.resolve("instance"), 8));
            if (!InstanceCatalog.contains(id)) return null;
            String s = read(r.resolve("init.identity"), 128).replace(' ', ':');
            boolean active = s.equals(session(id)) &&
                process(r.resolve("display.identity"), "com.rootvmos.viewer:display", true) &&
                process(r.resolve("producer.identity"), "/iris_buffer_producer", false);
            return new Display(r, id, s, active);
        } catch (Exception e) { return null; }
    }

    private String run(String script, int id, String expected, String expectedRun, int seconds, String... args) throws Exception {
        return runCaptured(script, id, expected, expectedRun, seconds, null, args);
    }

    /**
     * 【子进程执行核心封装】
     * 1. 数组方式传入命令列表，杜绝 Shell 命令行拼接与命令注入；
     * 2. 清理 CLASSPATH，防止宿主 Java 类的环境变量泄漏进子环境；
     * 3. 独立后台守护线程排空 stdout 与 stderr，写日志的同时防止 Linux 管道填满死锁；
     * 4. 严格超时控制，返回时核验退出码。
     */
    private String runCaptured(String script, int id, String expected, String expectedRun, int seconds, BootAttempt attempt, String... args) throws Exception {
        ArrayList<String> cmd = new ArrayList<>(Arrays.asList(BB, "timeout", Integer.toString(seconds), "/system/bin/sh", script));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder b = new ProcessBuilder(cmd);
        b.environment().remove("CLASSPATH");
        if (!expected.isEmpty()) b.environment().put("IRIS_EXPECT_SESSION", expected);
        if (!expectedRun.isEmpty()) b.environment().put("IRIS_EXPECT_RUN", expectedRun);
        b.redirectErrorStream(true);
        Process p = b.start();
        p.getOutputStream().close();
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream(); OutputStream log = new FileOutputStream(DIR + "/command_" + id + ".log", true)) {
                byte[] block = new byte[4096];
                int n;
                while ((n = in.read(block)) != -1) {
                    log.write(block, 0, n);
                    if (attempt != null) attempt.command(block, n);
                    synchronized (capture) {
                        if (capture.size() < 32768) capture.write(block, 0, Math.min(n, 32768 - capture.size()));
                    }
                }
            } catch (IOException ignored) {}
        });
        drain.setDaemon(true);
        drain.start();
        try {
            if (!p.waitFor(seconds + 3L, TimeUnit.SECONDS)) throw new IOException("Command deadline");
            drain.join(500);
            String output;
            synchronized (capture) { output = capture.toString("UTF-8"); }
            if (p.exitValue() != 0) throw new IOException("Command failed (" + p.exitValue() + "); see command_" + id + ".log");
            return output;
        } finally {
            p.destroy();
            if (attempt != null) attempt.commandEnded();
        }
    }

    /** 封装调用 ctl.sh 控制脚本 */
    private String ctl(int id, String expected, int seconds, String... args) throws Exception {
        return run(BASE + "/ctl.sh", id, expected, "", seconds, args);
    }

    /** 停止指定实例的全屏显示辅助管线 */
    private void stopDisplay(Display d) throws Exception {
        run(DIR + "/display.sh", d.id, d.session, d.run.toString(), 30, "stop", Integer.toString(d.id));
    }

    /** 清理指定实例的缩略图缓存并取消正在进行的抓取任务 */
    private void clearPreview(int id) {
        synchronized (displayLock) {
            previewCache.remove(id);
            previewAttempts.remove(id);
            PreviewJob job = previewJobs.get(id);
            if (job != null) job.cancelled = true;
        }
    }

    /** 等待正在进行的缩略图抓取任务退出（例如在启动/关闭或进入全屏前调用） */
    private void waitPreviews(int id) throws Exception {
        long end = SystemClock.elapsedRealtime() + 12000;
        synchronized (displayLock) {
            while (id == 0 ? !previewJobs.isEmpty() : previewJobs.containsKey(id)) {
                long remaining = end - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IOException("List preview is still closing; retry");
                displayLock.wait(Math.min(remaining, 500));
            }
        }
    }

    /** 异步捕获单帧小窗缩略图并生成 JPEG 数据存入缓存 */
    private void capturePreview(PreviewJob job) {
        try {
            long began;
            synchronized (displayLock) {
                if (job.cancelled || closing || opening != 0 || display() != null || !busy.getOrDefault(job.id, "").isEmpty() ||
                    !job.session.equals(session(job.id)) || !job.session.equals(readySessions.get(job.id))) return;
                began = SystemClock.elapsedRealtime();
            }
            byte[] jpeg = PreviewFrames.capture(job.id, job.session, previewDeadlines);
            synchronized (displayLock) {
                if (!job.cancelled && !closing && opening == 0 && display() == null && job.session.equals(session(job.id)) &&
                    job.session.equals(readySessions.get(job.id)) && previewJobs.get(job.id) == job)
                    previewCache.put(job.id, new Preview(job.session, ++previewSequence, began, jpeg));
            }
        } catch (Exception e) {
            System.err.println("Preview ended id=" + job.id + " reason=" + e.getClass().getSimpleName());
        } finally {
            synchronized (displayLock) {
                if (previewJobs.get(job.id) == job) previewJobs.remove(job.id);
                displayLock.notifyAll();
            }
        }
    }

    /** 处理客户端对缩略图的 HTTP GET 请求：若有缓存直接输出 JPEG，否则触发异步抓取并返回 202 */
    private void preview(Socket peer, int id, String expected) throws Exception {
        Preview image;
        long now = SystemClock.elapsedRealtime();
        synchronized (displayLock) {
            if (expected.isEmpty() || !expected.equals(session(id))) {
                json(peer, 409, new JSONObject().put("error", "Instance session changed"));
                return;
            }
            if (opening != 0 || display() != null || cleanupQueued || !busy.getOrDefault(id, "").isEmpty()) {
                json(peer, 409, new JSONObject().put("error", "Preview is paused while an instance operation or fullscreen display is active"));
                return;
            }
            if (!expected.equals(readySessions.get(id))) {
                json(peer, 503, new JSONObject().put("error", "Guest is still booting"));
                return;
            }
            image = previewCache.get(id);
            if (image != null && !image.session.equals(expected)) { previewCache.remove(id); image = null; }
            if (!previewJobs.containsKey(id) && now - previewAttempts.getOrDefault(id, 0L) >= 1000) {
                PreviewJob job = new PreviewJob(id, expected);
                previewJobs.put(id, job);
                previewAttempts.put(id, now);
                try { previews.execute(() -> capturePreview(job)); } catch (RejectedExecutionException e) { previewJobs.remove(id); }
            }
            if (image == null || now - image.captured > 15000) {
                json(peer, 202, new JSONObject().put("pending", true).put("session", expected));
                return;
            }
        }
        // 网络写入绝不能持有全局互斥锁；设置 3 秒写入超时防止慢连接阻塞
        ScheduledFuture<?> deadline = previewDeadlines.schedule(() -> { try { peer.close(); } catch (IOException ignored) {} }, 3, TimeUnit.SECONDS);
        try {
            byte[] header = ("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + image.jpeg.length +
                "\r\nCache-Control: no-store\r\nConnection: close\r\nX-Iris-Session: " + image.session +
                "\r\nX-Iris-Sequence: " + image.sequence + "\r\nX-Iris-Captured-Elapsed-Ms: " + image.captured + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            peer.getOutputStream().write(header);
            peer.getOutputStream().write(image.jpeg);
            peer.getOutputStream().flush();
        } finally { deadline.cancel(false); }
    }

    /** 获取或恢复指定实例的启动追踪对象 */
    private BootAttempt attempt(int id, String current) {
        BootAttempt existing = attempts.get(id);
        if (existing != null) return existing;
        synchronized (attempts) {
            existing = attempts.get(id);
            if (existing != null) return existing;
            BootAttempt restored = BootAttempt.restore(instance(id), current);
            if (restored != null) attempts.put(id, restored);
            return restored;
        }
    }

    /**
     * 【状态聚合接口：state】
     * 聚合全平台所有实例的实时数据：
     * 1. 编号、显示名称、版本 Revision、Google服务开关；
     * 2. 真实内核 Session、是否已完全就绪（ready）、当前繁忙状态（busy）与阶段文案（stage）；
     * 3. 内存、分辨率、Root 详细子状态；
     * 4. 实时启动日志快照（bootLog）与小窗缩略图状态（previewSequence）。
     */
    private JSONObject state(int requestedOffset, int limit) throws Exception {
        SortedMap<Integer, String> catalog = InstanceCatalog.all();
        ArrayList<Integer> ids = new ArrayList<>(catalog.keySet());
        int offset = Math.max(0, Math.min(requestedOffset, ids.isEmpty() ? 0 : ((ids.size() - 1) / 2) * 2));
        JSONArray list = new JSONArray();
        Display d = display();

        for (int id : ids.subList(offset, Math.min(ids.size(), offset + limit))) {
            String s = session(id), operation = busy.getOrDefault(id, "");
            boolean ready = !s.isEmpty() && s.equals(readySessions.get(id));
            BootAttempt attempt = attempt(id, s);
            if (attempt != null && !s.isEmpty() && !attempt.bindSession(s)) attempt = null;

            // 依据当前忙碌状态或内核存活状态，生成人类可读的阶段状态文本描述
            String stage = operation.equals("start") ? (s.isEmpty() ? "正在执行启动命令" : "Android 正在启动") :
                operation.equals("stop") ? "正在停止实例" :
                operation.equals("open") ? "正在打开画面" :
                operation.equals("orientation") ? "正在切换屏幕方向" :
                operation.equals("resolution") ? "正在保存分辨率" :
                operation.equals("memory") ? "正在保存内存上限" :
                operation.equals("root") ? "正在保存应用 Root" :
                operation.equals("app") ? "正在打开应用" :
                operation.equals("reset") ? "正在重置数据" :
                operation.equals("delete") ? "正在删除实例" :
                s.isEmpty() ? (errors.containsKey(id) ? "上次操作未完成，请查看日志" : "实例已停止") :
                ready ? "桌面已就绪" : "Android 正在启动，等待桌面";

            // 装配启动日志
            JSONArray logs = attempt == null ? new JSONArray() : attempt.logs();
            if (attempt == null && !s.isEmpty()) logs.put("[状态] 已接管运行中的会话；本管理器没有该会话的启动日志。");

            // 计算缩略图序号与缓存年龄
            long seq = 0, age = -1;
            synchronized (displayLock) {
                Preview image = previewCache.get(id);
                if (image != null && image.session.equals(s)) {
                    seq = image.sequence;
                    age = Math.max(0, SystemClock.elapsedRealtime() - image.captured);
                }
            }

            // 打包当前实例全部状态 JSON
            list.put(new JSONObject().put("id", id).put("number", InstanceCatalog.number(catalog, id))
                .put("displayName", "实例 " + InstanceCatalog.number(catalog, id))
                .put("revision", catalog.get(id))
                .put("googleEnabled", Files.isRegularFile(instance(id).resolve("google.core.enabled"), LinkOption.NOFOLLOW_LINKS))
                .put("running", !s.isEmpty()).put("session", s)
                .put("ready", ready).put("error", BootAttempt.safe(errors.getOrDefault(id, "")))
                .put("busy", operation).put("stage", stage)
                .put("resolution", resolutionState(id, s))
                .put("orientation", orientationState(id))
                .put("memory", memoryState(id, s)).put("root", rootState(id, s)).put("audio", audioState(id, s))
                .put("cleanupPending", Files.exists(instance(id).resolve("cleanup.pending"), LinkOption.NOFOLLOW_LINKS))
                .put("bootLog", logs).put("bootLogKey", attempt == null ? "" : attempt.key())
                .put("bootElapsedMs", attempt == null ? 0 : attempt.elapsed())
                .put("previewSequence", seq).put("previewAgeMs", age)
                .put("displaying", d != null && d.active && d.id == id && d.session.equals(s))
                .put("opening", opening == id));
        }
        JSONArray presets = new JSONArray();
        for (String profile : RESOLUTIONS)
            presets.put(new JSONObject().put("profile", profile).put("label", resolutionLabel(profile)));

        return new JSONObject().put("count", ids.size()).put("offset", offset)
            .put("catalogBusy", catalogBusy).put("catalogError", catalogError)
            .put("lastCreatedId", lastCreatedId).put("instances", list)
            .put("resolutionPresets", presets).put("mode", "shared graphics buffers")
            .put("displaySeconds", 0).put("instanceSeconds", 0).put("previewIntervalMs", 1000);
    }

    /**
     * 【核心操作调度：action】
     * 运行于 actions 单线程池中。由于请求排队期间实例状态可能已变，
     * 执行前必须严格复核 revision、session 以及重置/删除时的用户确认编号。
     */
    private void action(int id, String expected, String type, String app, int displaySeconds,
                        String profile, String previousProfile, String mib, String previousMib,
                        String revision, int confirmedNumber) throws Exception {
        SortedMap<Integer, String> currentCatalog = InstanceCatalog.all();
        // 校验 Revision：若数据目录在此期间被其他操作变更，立即拒绝执行
        if (!revision.equals(currentCatalog.get(id))) throw new IOException("Instance data changed; refresh first");
        String now = session(id);

        // 分支 1：重置数据（reset）或彻底删除实例（delete）
        if (type.equals("reset") || type.equals("delete")) {
            if (confirmedNumber != InstanceCatalog.number(currentCatalog, id))
                throw new IOException("Instance numbering changed; refresh and confirm again");
            if (!now.isEmpty()) throw new IOException("Stop this instance before clearing data");
            // 清理并等待所有预览和正在传输的文件任务彻底排空
            clearPreview(id);
            waitPreviews(id);
            files.requireStoppedIdle(id);
            run(BASE + "/instances.sh", id, "", "", 145, type, Integer.toString(id), revision);
            files.forgetStopped(id);
            BootAttempt removedAttempt = attempts.remove(id);
            if (removedAttempt != null) removedAttempt.close();
            readySessions.remove(id);
            resolutionFailures.remove(id);
            lastBootChecks.remove(id);
        } else if (type.equals("root")) {
            // 分支 2：修改 Magisk Root 方案（必须在停机状态下执行）
            if (!now.isEmpty()) throw new IOException("Stop the instance before changing application root");
            run(BASE + "/root.sh", id, "", "", 45, "set", Integer.toString(id), profile, previousProfile, revision);
        } else if (type.equals("memory")) {
            // 分支 3：调整静态内存配额（必须在停机状态下执行）
            if (!now.isEmpty()) throw new IOException("Stop the instance before changing memory");
            run(BASE + "/memory.sh", id, "", "", 45, "set", Integer.toString(id), mib, previousMib);
        } else if (type.equals("resolution")) {
            // 分支 4：修改分辨率预设（必须在停机状态下执行）
            if (!now.isEmpty()) throw new IOException("Stop the instance before changing resolution");
            run(BASE + "/resolution.sh", id, "", "", 45, "set", Integer.toString(id), profile, previousProfile);
        } else if (type.equals("start")) {
            // 分支 5：点火启动虚拟机实例
            if (!now.isEmpty()) throw new IOException("Instance already running");
            clearPreview(id);
            BootAttempt attempt = attempts.get(id);
            try {
                runCaptured(BASE + "/ctl.sh", id, "", "", 45, attempt, "start", Integer.toString(id), "0");
            } catch (Exception e) {
                // ctl.sh 的前台等待时间可能先于 Android 完整开机超时，若内核已成功建立 session 则不视为失败
                if (session(id).isEmpty()) throw e;
            }
        } else {
            // 运行态依赖操作：核验会话必须与排队时的 expected session 严格一致
            if (expected.isEmpty() || !now.equals(expected)) throw new IOException("Queued action session changed");

            if (type.equals("stop")) {
                // 分支 6：优雅停止实例（取消文件传输、等待预览排空、关闭全屏投屏，最后调用 ctl.sh stop）
                files.cancelAndWait(id, expected);
                clearPreview(id);
                waitPreviews(id);
                Display d = display();
                if (d != null && d.id == id && d.session.equals(expected)) stopDisplay(d);
                ctl(id, expected, 45, "stop", Integer.toString(id));
                readySessions.remove(id);
            } else if (type.equals("open")) {
                // 分支 7：全屏投屏连接（必须桌面就绪、排空预览、关闭前一个显示会话）
                if (!expected.equals(readySessions.get(id))) throw new IOException("Guest is still booting");
                waitPreviews(0);
                if (display() != null) throw new IOException("Previous display is still open or cleaning up");
                run(DIR + "/display.sh", id, expected, "", 55, "start", Integer.toString(id), expected,
                    Integer.toString(displaySeconds), Integer.toString(InstanceCatalog.number(currentCatalog, id)));
            } else if (type.equals("orientation")) {
                if (!expected.equals(readySessions.get(id))) throw new IOException("Guest is still booting");
                run(BASE + "/orientation.sh", id, expected, "", 45, "set", Integer.toString(id), expected, profile, previousProfile);
            } else if (type.equals("app")) {
                // 分支 8：打开常用应用（如 Magisk 授权管理、系统设置、时钟等）
                if (app.equals("magisk") && (savedRoot(id).equals("off") || !rootState(id, now).optBoolean("ready")))
                    throw new IOException("Application root is not ready");
                String component = app.equals("magisk") ? "com.topjohnwu.magisk/.ui.MainActivity" :
                    app.equals("settings") ? "com.android.settings/.homepage.SettingsHomepageActivity" : "com.android.deskclock/.DeskClock";

                if (app.equals("magisk")) {
                    String launch = ctl(id, expected, 15, "exec", Integer.toString(id), "/system/bin/busybox", "timeout", "10",
                        "cmd", "package", "resolve-activity", "--brief", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "com.topjohnwu.magisk");
                    component = "";
                    for (String line : launch.split("\n"))
                        if (line.startsWith("com.topjohnwu.magisk/")) component = line.trim();
                    if (component.isEmpty()) throw new IOException("Magisk manager is not installed");
                }

                // 通过 cmd package resolve-activity 核验组件就绪状态，随后调用 am start 唤起
                String resolved = ctl(id, expected, 15, "exec", Integer.toString(id), "/system/bin/busybox", "timeout", "10",
                    "cmd", "package", "resolve-activity", "--brief", "--user", "0", "-n", component);
                if (!resolved.contains(component.substring(0, component.indexOf('/')) + "/"))
                    throw new IOException("Application is not ready");
                String result = ctl(id, expected, 20, "exec", Integer.toString(id), "/system/bin/busybox", "timeout", "15",
                    "am", "start", "-W", "--user", "0", "-n", component);
                if (result.contains("Error:") || result.contains("Error type") || result.contains("Exception"))
                    throw new IOException("Application launch failed; see command log");
            }
        }
    }

    /**
     * 【定时巡检器：monitor】
     * 每秒巡检一次：
     * 1. 探测各实例内核会话存活状态；
     * 2. 检查 sys.boot_completed == 1 并解析是否存在非 Fallback/Resolver 的真实 HOME 桌面 Activity；
     * 3. 桌面就绪后自动触发 resolution.sh apply 应用目标分辨率；
     * 4. 成功后将该会话记入 readySessions；
     * 5. 巡检全屏显示会话：若显示助手已退出，异步触发资源清理。
     */
    private void monitor() {
        try {
            long sampled = SystemClock.elapsedRealtime();
            for (int id : InstanceCatalog.all().keySet()) {
                String current = session(id);
                BootAttempt attempt = attempt(id, current);
                if (attempt != null) { attempt.collectInit(); attempt.checkpoint(); }
                if (current.isEmpty()) {
                    readySessions.remove(id);
                    resolutionFailures.remove(id);
                    clearPreview(id);
                    if (attempt != null) attempt.stopCapture();
                    if (attempt != null && attempt.finished == 0 && !busy.getOrDefault(id, "").equals("start"))
                        attempt.finished = SystemClock.elapsedRealtime();
                    continue;
                }
                if (attempt != null && !attempt.bindSession(current)) attempt = null;
                if (attempt != null) attempt.captureGuest(id, current);

                // 实例存活但未就绪时，每隔 2 秒检测一次 boot_completed 与桌面组件
                if (!current.equals(readySessions.get(id)) && !current.equals(resolutionFailures.get(id)) &&
                    sampled - lastBootChecks.getOrDefault(id, 0L) >= 2000) {
                    lastBootChecks.put(id, sampled);
                    try {
                        String ready = ctl(id, current, 6, "exec", Integer.toString(id), "/system/bin/busybox", "timeout", "4", "sh", "-c",
                            "[ \"$(getprop sys.boot_completed)\" = 1 ] && cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME");
                        boolean realHome = false;
                        for (String component : ready.split("\n"))
                            if (component.trim().matches("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+") &&
                                !component.contains("FallbackHome") && !component.contains("ResolverActivity"))
                                realHome = true;

                        // 确认桌面真实就绪后，应用预设的分辨率
                        if (realHome && session(id).equals(current)) {
                            try {
                                runCaptured(BASE + "/resolution.sh", id, current, "", 35, attempt, "apply", Integer.toString(id), current);
                                runCaptured(BASE + "/orientation.sh", id, current, "", 45, attempt, "apply", Integer.toString(id), current);
                            } catch (Exception failure) {
                                if (current.equals(session(id))) {
                                    resolutionFailures.put(id, current);
                                    errors.put(id, "分辨率应用失败，请停止后重新启动；详见本次日志");
                                }
                                continue;
                            }
                            if (!current.equals(session(id))) continue;
                            readySessions.put(id, current);
                            if (attempt != null) { attempt.finished = SystemClock.elapsedRealtime(); attempt.checkpoint(); }
                        }
                    } catch (Exception ignored) {} // 引导中正常等待，不伪造 ready
                }
            }

            // 巡检全屏显示管线：若前台关闭了全屏（active 为 false），异步停止并清理显示助手
            synchronized (displayLock) {
                Display d = display();
                if (opening == 0 && d != null && !d.active && !cleanupQueued) {
                    cleanupQueued = true;
                    try {
                        actions.execute(() -> {
                            try { stopDisplay(d); } catch (Exception e) { errors.put(d.id, "Display cleanup: " + e.getMessage()); }
                            finally { cleanupQueued = false; }
                        });
                    } catch (RejectedExecutionException e) { cleanupQueued = false; }
                }
            }
        } catch (Exception e) { System.err.println("Monitor: " + e.getClass().getSimpleName()); }
    }

    /** 安全解析 URL 查询字符串 */
    private static Map<String, String> query(String text) throws Exception {
        HashMap<String, String> v = new HashMap<>();
        for (String pair : text.split("&")) {
            String[] p = pair.split("=", 2);
            if (pair.isEmpty()) continue;
            if (p.length != 2) throw new IOException("Query pair missing value");
            String key = URLDecoder.decode(p[0], "UTF-8"), value = URLDecoder.decode(p[1], "UTF-8");
            if (value.length() > 128 || v.put(key, value) != null) throw new IOException("Query");
        }
        return v;
    }

    /** 带超时的单行读取（用于解析 HTTP 请求行与请求头，防止慢速挂起攻击） */
    private static String line(Socket peer, InputStream in, int max, long deadline) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) throw new SocketTimeoutException("Headers");
            peer.setSoTimeout((int) left);
            int ch = in.read();
            if (ch < 0) throw new EOFException();
            if (ch == '\n') {
                String s = new String(b.toByteArray(), StandardCharsets.US_ASCII);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            if (ch == 0 || ch > 127 || b.size() >= max) throw new IOException("Header limit");
            b.write(ch);
        }
    }

    /** 输出 JSON 格式的 HTTP 响应报文 */
    private static void json(Socket peer, int code, JSONObject data) throws IOException {
        byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = peer.getOutputStream();
        out.write(("HTTP/1.1 " + code + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length +
            "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /**
     * 【HTTP 请求服务处理中心：serve】
     * 1. 严格安全校验：必须包含 Authorization: Bearer <token>，严禁 Origin（防 CSRF）；
     * 2. 路由分发：
     *    - /files: 文件传输交换协议；
     *    - /state: 状态聚合查询；
     *    - /boot-log: 启动引导日志分页拉取；
     *    - /preview: 小窗缩略图流式返回；
     *    - /instances/create: 创建新实例；
     *    - /action: 实例操作请求（异步交付 actions 线程池，返回 202 Accepted）。
     */
    private void serve(Socket peer) {
        try (Socket socket = peer) {
            try {
                socket.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(socket.getInputStream(), 1024);
                long deadline = SystemClock.elapsedRealtime() + 3000;
                String first = line(socket, in, 2048, deadline);
                String[] r = first.split(" ");
                if (r.length != 3 || !r[2].equals("HTTP/1.1")) throw new IOException("Request");
                String auth = "", origin = "";
                int total = first.length();
                boolean end = false, authSeen = false, originSeen = false;
                ExchangeProtocol.BodyHeaders bodyHeaders = new ExchangeProtocol.BodyHeaders();
                for (int n = 0; n < 32; n++) {
                    String value = line(socket, in, 2048, deadline);
                    total += value.length() + 2;
                    if (total > 8192) throw new IOException("Header total");
                    if (value.isEmpty()) { end = true; break; }
                    int colon = value.indexOf(':');
                    if (colon < 0) throw new IOException("Header");
                    String key = value.substring(0, colon), text = value.substring(colon + 1).trim();
                    if (key.equalsIgnoreCase("Authorization")) {
                        if (authSeen) throw new IOException("Duplicate authorization");
                        authSeen = true; auth = text;
                    }
                    if (key.equalsIgnoreCase("Origin")) {
                        if (originSeen) throw new IOException("Duplicate origin");
                        originSeen = true; origin = text;
                    }
                    bodyHeaders.accept(key, text);
                }
                if (!end) throw new IOException("Incomplete headers");
                // 鉴权拦截：Token 不匹配或携带了浏览器 Origin 头直接拒绝
                if (!auth.equals("Bearer " + token) || !origin.isEmpty()) {
                    json(socket, 403, new JSONObject().put("error", "Access denied"));
                    return;
                }

                String[] route = r[1].split("\\?", 2);
                Map<String, String> v;
                try {
                    v = query(route.length == 2 ? route[1] : "");
                } catch (Exception invalidQuery) {
                    json(socket, 400, new JSONObject().put("error", "Invalid query"));
                    return;
                }

                // 文件交换路由
                if (route[0].equals("/files") || route[0].startsWith("/files/")) {
                    try {
                        files.handle(socket, in, route[0], r[0], v, bodyHeaders.length, bodyHeaders.type);
                    } catch (ExchangeProtocol.Failure failure) {
                        if (!socket.isClosed())
                            json(socket, failure.status, new JSONObject().put("error", failure.getMessage())
                                .put("code", failure.code).put("session", v.getOrDefault("session", "")));
                    }
                    return;
                }
                bodyHeaders.requireEmpty();

                // 状态查询路由
                if (route[0].equals("/state") && r[0].equals("GET")) {
                    int offset = Integer.parseInt(v.getOrDefault("offset", "0")), limit = Integer.parseInt(v.getOrDefault("limit", "65535"));
                    if (offset < 0 || limit < 1 || limit > 65535) { json(socket, 400, new JSONObject().put("error", "Invalid page")); return; }
                    json(socket, 200, state(offset, limit));
                    return;
                }

                // 启动日志分页拉取路由
                if (route[0].equals("/boot-log") && r[0].equals("GET")) {
                    int id = Integer.parseInt(v.getOrDefault("id", "0"));
                    long offset = Long.parseLong(v.getOrDefault("offset", "0"));
                    if (!InstanceCatalog.contains(id) || offset < 0) { json(socket, 400, new JSONObject().put("error", "Invalid log request")); return; }
                    String current = session(id);
                    BootAttempt attempt = attempt(id, current);
                    if (attempt == null || !attempt.key().equals(v.getOrDefault("key", "")) || !current.isEmpty() && !attempt.bindSession(current)) {
                        json(socket, 409, new JSONObject().put("error", "Startup log attempt changed"));
                        return;
                    }
                    json(socket, 200, attempt.page(offset));
                    return;
                }

                // 实时小窗缩略图路由
                if (route[0].equals("/preview") && r[0].equals("GET")) {
                    int previewId = Integer.parseInt(v.getOrDefault("id", "0"));
                    if (!InstanceCatalog.contains(previewId)) { json(socket, 400, new JSONObject().put("error", "Invalid instance")); return; }
                    preview(socket, previewId, v.getOrDefault("session", ""));
                    return;
                }

                // 创建新实例路由
                if (route[0].equals("/instances/create") && r[0].equals("POST")) {
                    String google = v.getOrDefault("google", "0"), root = v.getOrDefault("root", "off");
                    if (!Arrays.asList("0", "1").contains(google) || !Arrays.asList("off", "magisk").contains(root) ||
                        !(v.keySet().equals(Collections.singleton("google")) || v.keySet().equals(new HashSet<>(Arrays.asList("google", "root"))))) {
                        json(socket, 400, new JSONObject().put("error", "Invalid create options"));
                        return;
                    }
                    synchronized (displayLock) {
                        if (!catalogBusy.isEmpty()) { json(socket, 409, new JSONObject().put("error", "Instance creation pending")); return; }
                        catalogBusy = "create"; catalogError = "";
                        try {
                            actions.execute(() -> {
                                try {
                                    String result = run(BASE + "/instances.sh", 0, "", "", 145, "create", google, root);
                                    for (String line : result.split("\n"))
                                        if (line.startsWith("INSTANCE_CREATED=")) lastCreatedId = Integer.parseInt(line.substring(17));
                                } catch (Exception e) {
                                    catalogError = "新增实例未完成，请查看管理日志";
                                    System.err.println("Create: " + e.getMessage());
                                } finally { catalogBusy = ""; }
                            });
                        } catch (RejectedExecutionException e) {
                            catalogBusy = "";
                            json(socket, 429, new JSONObject().put("error", "Action queue full"));
                            return;
                        }
                    }
                    json(socket, 202, new JSONObject().put("accepted", true));
                    return;
                }

                // 实例操作路由（/action POST）
                if (!route[0].equals("/action") || !r[0].equals("POST")) {
                    json(socket, 404, new JSONObject().put("error", "Unknown endpoint"));
                    return;
                }

                int id = Integer.parseInt(v.getOrDefault("id", "0"));
                String type = v.getOrDefault("type", ""), app = v.getOrDefault("app", ""), expected = v.getOrDefault("session", "");
                final String profile = v.getOrDefault("profile", ""), previousProfile = v.getOrDefault("previousProfile", "");
                final String mib = v.getOrDefault("mib", ""), previousMib = v.getOrDefault("previousMib", "");

                // 参数白名单严密防呆校验
                if (!InstanceCatalog.contains(id) || !Arrays.asList("start", "stop", "open", "app", "orientation", "resolution", "memory", "root", "reset", "delete").contains(type) ||
                    type.equals("app") && !Arrays.asList("settings", "clock", "magisk").contains(app) ||
                    type.equals("root") && (!Arrays.asList("off", "magisk", "magisk-safe").contains(profile) || !Arrays.asList("off", "magisk", "magisk-safe").contains(previousProfile)) ||
                    type.equals("memory") && (!validMemory(mib) || !(validMemory(previousMib) || previousMib.equals("-1"))) ||
                    type.equals("resolution") && (!RESOLUTIONS.contains(profile) || !(RESOLUTIONS.contains(previousProfile) || previousProfile.equals("invalid"))) ||
                    type.equals("orientation") && (!ORIENTATIONS.contains(profile) || !ORIENTATIONS.contains(previousProfile))) {
                    json(socket, 400, new JSONObject().put("error", "Invalid action"));
                    return;
                }

                String requestedSeconds = v.getOrDefault("seconds", "0");
                if (v.containsKey("seconds") && (!type.equals("open") || !Arrays.asList("0", "60", "120", "180", "300", "900").contains(requestedSeconds))) {
                    json(socket, 400, new JSONObject().put("error", "Invalid display seconds"));
                    return;
                }
                final int displaySeconds = Integer.parseInt(requestedSeconds);
                final SortedMap<Integer, String> actionCatalog = InstanceCatalog.all();
                final String revision = actionCatalog.get(id);
                final int confirmedNumber = InstanceCatalog.number(actionCatalog, id);
                boolean destructive = type.equals("reset") || type.equals("delete");
                if (type.equals("root") && !revision.equals(v.getOrDefault("revision", ""))) {
                    json(socket, 409, new JSONObject().put("error", "Instance data changed; refresh first"));
                    return;
                }
                if (destructive && (!revision.equals(v.getOrDefault("revision", "")) || !Integer.toString(confirmedNumber).equals(v.getOrDefault("confirm", "")))) {
                    json(socket, 409, new JSONObject().put("error", "Confirm the current instance and refresh first"));
                    return;
                }

                String current = session(id);
                if (type.equals("start") || type.equals("resolution") || type.equals("memory") || type.equals("root") || destructive ?
                    !current.isEmpty() || !expected.isEmpty() : expected.isEmpty() || !expected.equals(current)) {
                    json(socket, 409, new JSONObject().put("error", "Instance session changed"));
                    return;
                }

                // 将操作提交至 actions 串行线程池，并返回 HTTP 202 Accepted
                synchronized (displayLock) {
                    if (busy.containsKey(id)) { json(socket, 409, new JSONObject().put("error", "An instance operation is already pending")); return; }
                    if (type.equals("open")) {
                        if (opening != 0 || display() != null || cleanupQueued) {
                            json(socket, 409, new JSONObject().put("error", "Close the current display and wait for cleanup"));
                            return;
                        }
                        if (!expected.equals(readySessions.get(id))) {
                            json(socket, 503, new JSONObject().put("error", "Guest is still booting"));
                            return;
                        }
                        opening = id;
                    }
                    busy.put(id, type);
                    errors.remove(id);
                    BootAttempt previousAttempt = attempts.get(id);
                    if (type.equals("start")) { attempts.put(id, new BootAttempt(instance(id))); clearPreview(id); }
                    if (type.equals("stop")) clearPreview(id);

                    try {
                        actions.execute(() -> {
                            try {
                                action(id, expected, type, app, displaySeconds, profile, previousProfile, mib, previousMib, revision, confirmedNumber);
                                errors.remove(id);
                            } catch (Exception e) {
                                errors.put(id, e.getMessage() == null ? "Action failed" : e.getMessage());
                                if (type.equals("start")) {
                                    BootAttempt attempt = attempts.get(id);
                                    if (attempt != null) attempt.finished = SystemClock.elapsedRealtime();
                                }
                            } finally {
                                busy.remove(id, type);
                                if (type.equals("open")) opening = 0;
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        busy.remove(id, type);
                        if (type.equals("open")) opening = 0;
                        if (type.equals("start")) {
                            if (previousAttempt == null) attempts.remove(id); else attempts.put(id, previousAttempt);
                        }
                        json(socket, 429, new JSONObject().put("error", "Action queue full"));
                        return;
                    }
                    if (type.equals("start") && previousAttempt != null) previousAttempt.close();
                }
                json(socket, 202, new JSONObject().put("accepted", true));
            } catch (ExchangeProtocol.Failure failure) {
                try { if (!socket.isClosed()) json(socket, failure.status, new JSONObject().put("error", failure.getMessage()).put("code", failure.code)); } catch (IOException ignored) {}
            } catch (Exception e) {
                try { if (!socket.isClosed()) json(socket, 503, new JSONObject().put("error", e.getMessage() == null ? "Unavailable" : e.getMessage())); } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * 【主服务循环：run】
     * 1. 注册进程退出清理钩子（ShutdownHook）；
     * 2. 绑定 127.0.0.1:19762 回环端口；
     * 3. 生成并持久化权限 0600 的 broker.token；
     * 4. 启动定时巡检任务（monitor）；
     * 5. 循环 accept 连接并交由 clients 线程池处理。
     */
    private void run() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closing = true;
            for (BootAttempt attempt : attempts.values()) attempt.close();
            files.close();
            clients.shutdownNow();
            actions.shutdownNow();
            monitor.shutdownNow();
            previews.shutdownNow();
            previewDeadlines.shutdownNow();
        }));
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 19762), 8);
            Path p = Paths.get(DIR, "broker.token");
            Files.deleteIfExists(p);
            Files.createFile(p);
            Os.chmod(p.toString(), 0600);
            Files.write(p, token.getBytes(StandardCharsets.US_ASCII));
            monitor.scheduleWithFixedDelay(this::monitor, 1, 1, TimeUnit.SECONDS);
            System.out.println("Iris buffer broker ready on 127.0.0.1:19762");
            while (!closing) {
                Socket peer = server.accept();
                try {
                    clients.execute(() -> serve(peer));
                } catch (RejectedExecutionException e) {
                    peer.close();
                }
            }
        }
    }

    /**
     * 【守护进程入口：main】
     * 强制安全门禁：
     * 1. 必须由 UID 0（宿主 Root）启动；
     * 2. 必须处于宿主原生 6 大命名空间（pid, mnt, net, ipc, uts, cgroup 与系统 1 号进程完全一致）。
     */
    public static void main(String[] args) throws Exception {
        if (Os.getuid() != 0) throw new IllegalStateException("Host root required");
        for (String ns : new String[]{"pid", "mnt", "net", "ipc", "uts", "cgroup"})
            if (!Files.readSymbolicLink(Paths.get("/proc/self/ns", ns)).equals(Files.readSymbolicLink(Paths.get("/proc/1/ns", ns))))
                throw new IllegalStateException("Host namespaces required");
        new BufferBroker().run();
    }
}

