package com.irisbox.buffer.viewer.broker;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.SystemClock;
import android.system.Os;
import android.system.StructStat;
import android.util.DisplayMetrics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用与资源直迁组件（HostAppImporter）：
 * 支持将「宿主机」或「其他运行中 Guest 实例」的第三方已安装应用（Base APK + Split APKs）
 * 以及可选 OBB、Android/data 外置资源、/data/data 内部热更资源，通过管道零落盘流式迁入目标 Guest 实例并自动修复 UID/GID 与 SELinux 上下文。
 */
final class HostAppImporter {
    private static final String BASE = "/data/irisbox/runtime", BB = "/data/irisbox/host/bin/busybox";
    private static final String DATA_ROOT = "/data/irisbox", HOST_OBB_ROOT = "/data/media/0/Android/obb";
    private static final String HOST_EXPORT_ROOT = "/data/media/0/Download/IrisBoxExport";
    private static final String GUEST_DATA_EXT_ROOT = "/data/media/0/Android/data";
    private static final String GUEST_DATA_PRIV_ROOT = "/data/data";
    private static final String GUEST_DATA_DE_ROOT = "/data/user_de/0";
    private static final Pattern SESSION_PATTERN = Pattern.compile("\\[([0-9]+)\\]");
    private static final long MIN_FREE_HEADROOM_BYTES = 512L * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final int LABEL_CACHE_MAX = 512;
    private static final Map<String, String> LABEL_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > LABEL_CACHE_MAX;
            }
        });

    interface State {
        String session(int id);
        boolean ready(int id, String expected);
        boolean busy(int id);
        boolean stopping(int id);
    }

    static final class Job {
        final int id, srcId;
        final String session, srcSession, source, pkg, token;
        final String direction, targetMode, exportDir;
        final boolean includeObb, includeExtData, includeAppData;
        final List<String> apkPaths;
        final List<String> obbNames;
        final long apkBytes, obbBytes, extDataBytes, appDataBytes, totalBytes;
        final long startedAt = SystemClock.elapsedRealtime();
        volatile long doneBytes;
        volatile String phase = "queued", detail = "", error = "";
        volatile boolean finished, success;

        Job(int id, String session, String direction, String targetMode, String exportDir,
                int srcId, String srcSession, String source, String pkg,
                boolean includeObb, boolean includeExtData, boolean includeAppData,
                List<String> apkPaths, List<String> obbNames,
                long apkBytes, long obbBytes, long extDataBytes, long appDataBytes) {
            this.id = id;
            this.session = session;
            this.direction = direction;
            this.targetMode = targetMode;
            this.exportDir = exportDir;
            this.srcId = srcId;
            this.srcSession = srcSession;
            this.source = source;
            this.pkg = pkg;
            this.includeObb = includeObb;
            this.includeExtData = includeExtData;
            this.includeAppData = includeAppData;
            this.apkPaths = apkPaths;
            this.obbNames = obbNames;
            this.apkBytes = apkBytes;
            this.obbBytes = obbBytes;
            this.extDataBytes = extDataBytes;
            this.appDataBytes = appDataBytes;
            this.totalBytes = apkBytes + obbBytes + extDataBytes + appDataBytes;
            byte[] rand = new byte[8];
            new SecureRandom().nextBytes(rand);
            StringBuilder sb = new StringBuilder();
            for (byte b : rand) sb.append(String.format(Locale.US, "%02x", b & 255));
            this.token = sb.toString();
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("id", id)
                .put("session", session)
                .put("direction", direction)
                .put("targetMode", targetMode)
                .put("exportDir", exportDir)
                .put("source", source)
                .put("pkg", pkg)
                .put("includeObb", includeObb)
                .put("includeExtData", includeExtData)
                .put("includeAppData", includeAppData)
                .put("phase", phase)
                .put("detail", detail)
                .put("error", error)
                .put("doneBytes", Math.min(doneBytes, totalBytes))
                .put("totalBytes", totalBytes)
                .put("apkBytes", apkBytes)
                .put("obbBytes", obbBytes)
                .put("extDataBytes", extDataBytes)
                .put("appDataBytes", appDataBytes)
                .put("splitCount", apkPaths.size())
                .put("obbCount", obbNames.size())
                .put("finished", finished)
                .put("success", success);
        }
    }

    private final State state;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Job> exportJobs = new ConcurrentHashMap<>();

    HostAppImporter(State state) {
        this.state = state;
    }

    void close() {
        workers.shutdownNow();
    }

    private int parseSourceId(String source) throws Exception {
        if (source == null || source.isEmpty() || "host".equals(source)) return 0;
        if (source.startsWith("instance:")) {
            String raw = source.substring(9);
            if (raw.matches("[1-9][0-9]{3}")) {
                int srcId = Integer.parseInt(raw);
                if (InstanceCatalog.contains(srcId)) return srcId;
            }
        }
        throw new ExchangeProtocol.Failure(400, "INVALID_SOURCE", "无效的应用迁移来源：" + source);
    }

    private String requireReadySourceSession(int srcId) throws ExchangeProtocol.Failure {
        String sess = state.session(srcId);
        if (sess == null || sess.isEmpty() || !state.ready(srcId, sess) || state.stopping(srcId)) {
            throw new ExchangeProtocol.Failure(409, "SOURCE_NOT_READY", "来源实例未就绪或已停止");
        }
        return sess;
    }

    /** 枚举宿主机已安装的第三方应用 */
    JSONObject listHostApps() throws Exception {
        return listApps("host");
    }

    /** 枚举宿主机或指定运行中实例的第三方应用及其体积信息 */
    JSONObject listApps(String source) throws Exception {
        int srcId = parseSourceId(source);
        if (srcId == 0) {
            String raw = runHost(15, "/system/bin/cmd", "package", "list", "packages", "-3", "-f");
            if (raw.trim().isEmpty()) {
                raw = runHost(15, "/system/bin/pm", "list", "packages", "-3", "-f");
            }
            TreeMap<String, JSONObject> unique = new TreeMap<>();
            for (String line : raw.split("\n")) {
                line = line.trim();
                if (!line.startsWith("package:")) continue;
                int eq = line.lastIndexOf('=');
                if (eq <= 8) continue;
                String baseApkStr = line.substring(8, eq).trim();
                String pkg = line.substring(eq + 1).trim();
                if (!validPkg(pkg) || pkg.startsWith("com.irisbox.")) continue;

                List<Path> apks = collectApkFilesFromHint(baseApkStr);
                long apkBytes = 0;
                for (Path p : apks) apkBytes += safeSize(p);
                List<Path> obbs = collectObbFiles(pkg);
                long obbBytes = 0;
                for (Path p : obbs) obbBytes += safeSize(p);
                long extDataBytes = safeDirBytesHost(Paths.get(HOST_OBB_ROOT).getParent().resolve("data").resolve(pkg));
                Path labelApk = null;
                if (isAllowedHostApkPath(baseApkStr)) {
                    Path candidate = Paths.get(baseApkStr).normalize();
                    if (!Files.isSymbolicLink(candidate) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        labelApk = candidate;
                    }
                }
                if (labelApk == null && !apks.isEmpty()) labelApk = apks.get(0);
                String label = resolveApkLabel(labelApk, pkg);

                JSONObject item = new JSONObject()
                    .put("pkg", pkg)
                    .put("label", label)
                    .put("apkBytes", apkBytes)
                    .put("splitCount", apks.size())
                    .put("obbCount", obbs.size())
                    .put("obbBytes", obbBytes)
                    .put("extDataBytes", extDataBytes)
                    .put("appDataBytes", 0L)
                    .put("totalBytes", apkBytes + obbBytes + extDataBytes);
                unique.put(pkg, item);
            }
            JSONArray arr = new JSONArray();
            for (JSONObject item : unique.values()) arr.put(item);
            return new JSONObject().put("source", "host").put("apps", arr);
        }

        String srcSess = requireReadySourceSession(srcId);
        // 在源容器内一次性枚举所有第三方包（用 wc -c / du -sk 输出原始值，由 Java 64-bit long 累加，规避 mksh 32 位整数溢出）
        String script =
            "LIST=$(/system/bin/cmd package list packages -3 -f 2>/dev/null || /system/bin/pm list packages -3 -f)\n" +
            "for line in $LIST; do\n" +
            "  case \"$line\" in package:*=*) ;; *) continue ;; esac\n" +
            "  rest=${line#package:}; base=${rest%=*}; pkg=${rest##*=}\n" +
            "  case \"$pkg\" in ''|com.irisbox.*) continue ;; esac\n" +
            "  dir=${base%/*}; splits=0; apk_list=''\n" +
            "  for a in \"$dir\"/*.apk; do\n" +
            "    [ -f \"$a\" ] && [ ! -L \"$a\" ] || continue\n" +
            "    splits=$((splits + 1))\n" +
            "    sz=$(/system/bin/busybox stat -c %s \"$a\" 2>/dev/null || echo 0)\n" +
            "    apk_list=\"$apk_list $sz\"\n" +
            "  done\n" +
            "  if [ \"$splits\" = 0 ] && [ -f \"$base\" ] && [ ! -L \"$base\" ]; then\n" +
            "    splits=1; apk_list=$(/system/bin/busybox stat -c %s \"$base\" 2>/dev/null || echo 0)\n" +
            "  fi\n" +
            "  apk_b=$(printf '%s\\n' $apk_list | /system/bin/busybox awk '{s+=$1} END{printf \"%.0f\", s}')\n" +
            "  obb_c=0; obb_list=''\n" +
            "  if [ -d \"/data/media/0/Android/obb/$pkg\" ]; then\n" +
            "    for o in \"/data/media/0/Android/obb/$pkg\"/*.obb; do\n" +
            "      [ -f \"$o\" ] && [ ! -L \"$o\" ] || continue\n" +
            "      obb_c=$((obb_c + 1))\n" +
            "      sz=$(/system/bin/busybox stat -c %s \"$o\" 2>/dev/null || echo 0)\n" +
            "      obb_list=\"$obb_list $sz\"\n" +
            "    done\n" +
            "  fi\n" +
            "  obb_b=$(printf '%s\\n' $obb_list | /system/bin/busybox awk '{s+=$1} END{printf \"%.0f\", s}')\n" +
            "  ek=0\n" +
            "  if [ -d \"/data/media/0/Android/data/$pkg\" ]; then\n" +
            "    ek=$(/system/bin/busybox du -sk \"/data/media/0/Android/data/$pkg\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
            "  fi\n" +
            "  ak=0\n" +
            "  if [ -d \"/data/data/$pkg\" ]; then\n" +
            "    for E in \"/data/data/$pkg\"/* \"/data/data/$pkg\"/.*; do\n" +
            "      B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
            "      [ -e \"$E\" ] && [ ! -L \"$E\" ] || continue\n" +
            "      k=$(/system/bin/busybox du -sk \"$E\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
            "      ak=$((ak + ${k:-0}))\n" +
            "    done\n" +
            "    if [ -d \"/data/user_de/0/$pkg\" ]; then\n" +
            "      for E in \"/data/user_de/0/$pkg\"/* \"/data/user_de/0/$pkg\"/.*; do\n" +
            "        B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
            "        [ -e \"$E\" ] && [ ! -L \"$E\" ] || continue\n" +
            "        k=$(/system/bin/busybox du -sk \"$E\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
            "        ak=$((ak + ${k:-0}))\n" +
            "      done\n" +
            "    fi\n" +
            "  fi\n" +
            "  printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"$pkg\" \"$splits\" \"${apk_b:-0}\" \"$obb_c\" \"${obb_b:-0}\" \"${ek:-0}\" \"${ak:-0}\" \"$base\"\n" +
            "done\n";
        String out = execContainer(srcId, srcSess, 45, null, "/system/bin/sh", "-c", script);
        TreeMap<String, JSONObject> unique = new TreeMap<>();
        for (String line : out.split("\n")) {
            String[] cols = line.trim().split("\t");
            if (cols.length < 7) continue;
            String pkg = cols[0].trim();
            if (!validPkg(pkg) || pkg.startsWith("com.irisbox.")) continue;
            int splits = parseIntSafe(cols[1], 1);
            long apkBytes = parseLongSafe(cols[2], 0L);
            int obbCount = parseIntSafe(cols[3], 0);
            long obbBytes = parseLongSafe(cols[4], 0L);
            long extDataBytes = parseLongSafe(cols[5], 0L) * 1024L;
            long appDataBytes = parseLongSafe(cols[6], 0L) * 1024L;
            String baseGuestApk = cols.length >= 8 ? cols[7].trim() : "";
            Path hostBaseApk = safeInstanceDataPath(srcId, baseGuestApk);
            String label = resolveApkLabel(hostBaseApk, pkg);
            JSONObject item = new JSONObject()
                .put("pkg", pkg)
                .put("label", label)
                .put("apkBytes", apkBytes)
                .put("splitCount", splits)
                .put("obbCount", obbCount)
                .put("obbBytes", obbBytes)
                .put("extDataBytes", extDataBytes)
                .put("appDataBytes", appDataBytes)
                .put("totalBytes", apkBytes + obbBytes + extDataBytes + appDataBytes);
            unique.put(pkg, item);
        }
        JSONArray arr = new JSONArray();
        for (JSONObject item : unique.values()) arr.put(item);
        return new JSONObject().put("source", "instance:" + srcId).put("apps", arr);
    }

    /** 提交应用迁入任务（兼容 host 或 instance:<srcId> 来源，可选 OBB / Android/data / 应用私有数据） */
    JSONObject startImport(int id, String expectedSession, String source, String pkg,
            boolean includeObb, boolean includeExtData, boolean includeAppData) throws Exception {
        String normSource = (source == null || source.isEmpty()) ? "host" : source;
        int srcId = parseSourceId(normSource);
        if (!InstanceCatalog.contains(id) || expectedSession == null || expectedSession.isEmpty()
                || !validPkg(pkg) || pkg.startsWith("com.irisbox.")
                || (srcId > 0 && srcId == id)
                || (srcId == 0 && includeAppData)) {
            throw new ExchangeProtocol.Failure(400, "INVALID_REQUEST", "参数无效、源与目标相同或禁止迁入管理端自身");
        }
        String current = state.session(id);
        if (!expectedSession.equals(current) || !state.ready(id, expectedSession)) {
            throw new ExchangeProtocol.Failure(409, "INSTANCE_NOT_READY", "目标实例未就绪或会话已变化");
        }
        if (state.busy(id) || state.stopping(id)) {
            throw new ExchangeProtocol.Failure(423, "INSTANCE_BUSY", "目标实例正在执行其他操作，请稍后再试");
        }
        String srcSession = srcId > 0 ? requireReadySourceSession(srcId) : "";
        if (srcId > 0 && state.busy(srcId)) {
            throw new ExchangeProtocol.Failure(423, "SOURCE_BUSY", "来源实例正在执行其他操作，请稍后再试");
        }

        synchronized (jobs) {
            Job active = jobs.get(id);
            if (active != null && !active.finished) {
                throw new ExchangeProtocol.Failure(409, "IMPORT_IN_PROGRESS", "当前已有应用迁入任务正在执行");
            }

            List<String> apkPaths;
            List<String> obbNames;
            long apkBytes = 0, obbBytes = 0, extDataBytes = 0, appDataBytes = 0;

            if (srcId == 0) {
                List<Path> hostApks = resolveHostPackageApks(pkg);
                if (hostApks.isEmpty()) {
                    throw new ExchangeProtocol.Failure(404, "APP_NOT_FOUND", "宿主中未找到有效的应用安装包：" + pkg);
                }
                apkPaths = new ArrayList<>();
                for (Path p : hostApks) {
                    apkPaths.add(p.toString());
                    apkBytes += Files.size(p);
                }
                obbNames = new ArrayList<>();
                if (includeObb) {
                    for (Path p : collectObbFiles(pkg)) {
                        obbNames.add(p.getFileName().toString());
                        obbBytes += Files.size(p);
                    }
                }
                if (includeExtData) {
                    Path hostExtDir = Paths.get(HOST_OBB_ROOT).getParent().resolve("data").resolve(pkg).normalize();
                    if (Files.isDirectory(hostExtDir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(hostExtDir)) {
                        extDataBytes = safeDirBytesHost(hostExtDir);
                    }
                }
            } else {
                String probeScript =
                    "PKG=\"$1\"\n" +
                    "OUT=$(/system/bin/cmd package path \"$PKG\" 2>/dev/null || /system/bin/pm path \"$PKG\")\n" +
                    "for l in $OUT; do\n" +
                    "  case \"$l\" in package:*) ;; *) continue ;; esac\n" +
                    "  p=${l#package:}\n" +
                    "  case \"$p\" in *..*) continue ;; /data/app/*.apk|/mnt/asec/*.apk) ;; *) continue ;; esac\n" +
                    "  [ -f \"$p\" ] && [ ! -L \"$p\" ] || continue\n" +
                    "  sz=$(/system/bin/busybox stat -c %s \"$p\" 2>/dev/null || echo 0)\n" +
                    "  printf 'APK\\t%s\\t%s\\n' \"$p\" \"$sz\"\n" +
                    "done\n" +
                    "if [ \"$2\" = 1 ] && [ -d \"/data/media/0/Android/obb/$PKG\" ] && [ ! -L \"/data/media/0/Android/obb/$PKG\" ]; then\n" +
                    "  for o in \"/data/media/0/Android/obb/$PKG\"/*.obb; do\n" +
                    "    [ -f \"$o\" ] && [ ! -L \"$o\" ] || continue\n" +
                    "    n=${o##*/}; sz=$(/system/bin/busybox stat -c %s \"$o\" 2>/dev/null || echo 0)\n" +
                    "    printf 'OBB\\t%s\\t%s\\n' \"$n\" \"$sz\"\n" +
                    "  done\n" +
                    "fi\n" +
                    "if [ \"$3\" = 1 ] && [ -d \"/data/media/0/Android/data/$PKG\" ] && [ ! -L \"/data/media/0/Android/data/$PKG\" ]; then\n" +
                    "  ek=$(/system/bin/busybox du -sk \"/data/media/0/Android/data/$PKG\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
                    "  printf 'EXTK\\t%s\\n' \"${ek:-0}\"\n" +
                    "fi\n" +
                    "if [ \"$4\" = 1 ] && [ -d \"/data/data/$PKG\" ] && [ ! -L \"/data/data/$PKG\" ]; then\n" +
                    "  ak=0\n" +
                    "  for E in \"/data/data/$PKG\"/* \"/data/data/$PKG\"/.*; do\n" +
                    "    B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
                    "    [ -e \"$E\" ] && [ ! -L \"$E\" ] || continue\n" +
                    "    k=$(/system/bin/busybox du -sk \"$E\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
                    "    ak=$((ak + ${k:-0}))\n" +
                    "  done\n" +
                    "  if [ -d \"/data/user_de/0/$PKG\" ] && [ ! -L \"/data/user_de/0/$PKG\" ]; then\n" +
                    "    for E in \"/data/user_de/0/$PKG\"/* \"/data/user_de/0/$PKG\"/.*; do\n" +
                    "      B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
                    "      [ -e \"$E\" ] && [ ! -L \"$E\" ] || continue\n" +
                    "      k=$(/system/bin/busybox du -sk \"$E\" 2>/dev/null | /system/bin/busybox awk '{print $1}' || echo 0)\n" +
                    "      ak=$((ak + ${k:-0}))\n" +
                    "    done\n" +
                    "  fi\n" +
                    "  printf 'APPK\\t%s\\n' \"$ak\"\n" +
                    "fi\n";
                String probeOut = execContainer(srcId, srcSession, 30, null,
                    "/system/bin/sh", "-c", probeScript, "sh", pkg,
                    includeObb ? "1" : "0", includeExtData ? "1" : "0", includeAppData ? "1" : "0");
                apkPaths = new ArrayList<>();
                obbNames = new ArrayList<>();
                for (String line : probeOut.split("\n")) {
                    String[] cols = line.trim().split("\t");
                    if (cols.length >= 3 && "APK".equals(cols[0])) {
                        if (isAllowedHostApkPath(cols[1])) {
                            apkPaths.add(cols[1]);
                            apkBytes += parseLongSafe(cols[2], 0L);
                        }
                    } else if (cols.length >= 3 && "OBB".equals(cols[0])) {
                        String n = cols[1];
                        if (n.endsWith(".obb") && n.matches("[a-zA-Z0-9._-]+")) {
                            obbNames.add(n);
                            obbBytes += parseLongSafe(cols[2], 0L);
                        }
                    } else if (cols.length >= 2 && "EXTK".equals(cols[0])) {
                        extDataBytes = parseLongSafe(cols[1], 0L) * 1024L;
                    } else if (cols.length >= 2 && "APPK".equals(cols[0])) {
                        appDataBytes = parseLongSafe(cols[1], 0L) * 1024L;
                    }
                }
                if (apkPaths.isEmpty()) {
                    throw new ExchangeProtocol.Failure(404, "APP_NOT_FOUND", "来源实例中未找到有效安装包：" + pkg);
                }
            }

            long usable = new File(DATA_ROOT).getUsableSpace();
            long need = apkBytes + obbBytes + extDataBytes + appDataBytes + MIN_FREE_HEADROOM_BYTES;
            if (usable < need) {
                throw new ExchangeProtocol.Failure(507, "INSUFFICIENT_STORAGE",
                    String.format(Locale.US, "外置存储空间不足：需要约 %d MiB，可用 %d MiB", need / (1024 * 1024), usable / (1024 * 1024)));
            }

            Job job = new Job(id, expectedSession, "import", "", "", srcId, srcSession, normSource, pkg,
                includeObb, includeExtData, includeAppData,
                apkPaths, obbNames, apkBytes, obbBytes, extDataBytes, appDataBytes);
            jobs.put(id, job);
            workers.execute(() -> execute(job));
            return job.toJson();
        }
    }

    /** 查询最近一次应用迁入任务的状态 */
    JSONObject status(int id, String expectedSession) throws Exception {
        if (!InstanceCatalog.contains(id) || expectedSession == null || expectedSession.isEmpty()) {
            throw new ExchangeProtocol.Failure(400, "INVALID_REQUEST", "无效的实例会话查询");
        }
        Job job = jobs.get(id);
        if (job == null || !expectedSession.equals(job.session)) {
            return new JSONObject().put("id", id).put("session", expectedSession).put("phase", "idle").put("finished", true).put("success", false);
        }
        return job.toJson();
    }

    /** 提交容器应用导出至宿主机任务（支持导出安装包到 Download/IrisBoxExport 或直接安装到宿主机；严禁导出 /data/data） */
    JSONObject startExportToHost(int srcId, String expectedSession, String pkg, String targetMode, boolean includeObb) throws Exception {
        if (!InstanceCatalog.contains(srcId) || expectedSession == null || expectedSession.isEmpty()
                || !validPkg(pkg) || pkg.startsWith("com.irisbox.")
                || (!"apk_file".equals(targetMode) && !"install_host".equals(targetMode))) {
            throw new ExchangeProtocol.Failure(400, "INVALID_REQUEST", "无效的导出参数或包名");
        }
        String current = state.session(srcId);
        if (!expectedSession.equals(current) || !state.ready(srcId, expectedSession) || state.stopping(srcId)) {
            throw new ExchangeProtocol.Failure(409, "INSTANCE_NOT_READY", "来源实例未就绪或会话已变化");
        }
        if (state.busy(srcId)) {
            throw new ExchangeProtocol.Failure(423, "INSTANCE_BUSY", "来源实例正在执行其他操作，请稍后再试");
        }

        synchronized (exportJobs) {
            Job active = exportJobs.get(srcId);
            if (active != null && !active.finished) {
                throw new ExchangeProtocol.Failure(409, "EXPORT_IN_PROGRESS", "当前已有应用导出任务正在执行");
            }

            String probeScript =
                "PKG=\"$1\"\n" +
                "OUT=$(/system/bin/cmd package path \"$PKG\" 2>/dev/null || /system/bin/pm path \"$PKG\")\n" +
                "for l in $OUT; do\n" +
                "  case \"$l\" in package:*) ;; *) continue ;; esac\n" +
                "  p=${l#package:}\n" +
                "  case \"$p\" in *..*) continue ;; /data/app/*.apk|/mnt/asec/*.apk) ;; *) continue ;; esac\n" +
                "  [ -f \"$p\" ] && [ ! -L \"$p\" ] || continue\n" +
                "  sz=$(/system/bin/busybox stat -c %s \"$p\" 2>/dev/null || echo 0)\n" +
                "  printf 'APK\\t%s\\t%s\\n' \"$p\" \"$sz\"\n" +
                "done\n" +
                "if [ \"$2\" = 1 ] && [ -d \"/data/media/0/Android/obb/$PKG\" ] && [ ! -L \"/data/media/0/Android/obb/$PKG\" ]; then\n" +
                "  for o in \"/data/media/0/Android/obb/$PKG\"/*.obb; do\n" +
                "    [ -f \"$o\" ] && [ ! -L \"$o\" ] || continue\n" +
                "    n=${o##*/}; sz=$(/system/bin/busybox stat -c %s \"$o\" 2>/dev/null || echo 0)\n" +
                "    printf 'OBB\\t%s\\t%s\\n' \"$n\" \"$sz\"\n" +
                "  done\n" +
                "fi\n";
            String probeOut = execContainer(srcId, expectedSession, 30, null,
                "/system/bin/sh", "-c", probeScript, "sh", pkg, includeObb ? "1" : "0");
            List<String> apkPaths = new ArrayList<>();
            List<String> obbNames = new ArrayList<>();
            long apkBytes = 0, obbBytes = 0;
            for (String line : probeOut.split("\n")) {
                String[] cols = line.trim().split("\t");
                if (cols.length >= 3 && "APK".equals(cols[0]) && isAllowedHostApkPath(cols[1])) {
                    apkPaths.add(cols[1]);
                    apkBytes += parseLongSafe(cols[2], 0L);
                } else if (cols.length >= 3 && "OBB".equals(cols[0])) {
                    String n = cols[1];
                    if (n.endsWith(".obb") && n.matches("[a-zA-Z0-9._-]+")) {
                        obbNames.add(n);
                        obbBytes += parseLongSafe(cols[2], 0L);
                    }
                }
            }
            if (apkPaths.isEmpty()) {
                throw new ExchangeProtocol.Failure(404, "APP_NOT_FOUND", "容器中未找到可导出的第三方安装包：" + pkg);
            }

            long usableHost = new File("/data/media/0").getUsableSpace();
            long need = apkBytes + obbBytes + 256L * 1024 * 1024;
            if (usableHost < need) {
                throw new ExchangeProtocol.Failure(507, "INSUFFICIENT_STORAGE",
                    String.format(Locale.US, "宿主机内部存储空间不足：需要约 %d MiB，可用 %d MiB", need / (1024 * 1024), usableHost / (1024 * 1024)));
            }

            String exportDir = "apk_file".equals(targetMode) ? ("Download/IrisBoxExport/" + pkg) : "宿主机应用";
            Job job = new Job(srcId, expectedSession, "export", targetMode, exportDir,
                srcId, expectedSession, "instance:" + srcId, pkg,
                includeObb, false, false, apkPaths, obbNames, apkBytes, obbBytes, 0L, 0L);
            exportJobs.put(srcId, job);
            workers.execute(() -> executeExport(job));
            return job.toJson();
        }
    }

    /** 查询最近一次应用导出任务的状态 */
    JSONObject exportStatus(int srcId, String expectedSession) throws Exception {
        if (!InstanceCatalog.contains(srcId) || expectedSession == null || expectedSession.isEmpty()) {
            throw new ExchangeProtocol.Failure(400, "INVALID_REQUEST", "无效的导出状态查询");
        }
        Job job = exportJobs.get(srcId);
        if (job == null || !expectedSession.equals(job.session)) {
            return new JSONObject().put("id", srcId).put("session", expectedSession).put("direction", "export")
                .put("phase", "idle").put("finished", true).put("success", false);
        }
        return job.toJson();
    }

    private void executeExport(Job job) {
        String hostInstallSession = "";
        try {
            ensureAlive(job);
            if ("apk_file".equals(job.targetMode)) {
                Path baseRoot = Paths.get(HOST_EXPORT_ROOT);
                ensureDirOwned(baseRoot, 1023, 1079, 0775);
                Path pkgDir = baseRoot.resolve(job.pkg).normalize();
                if (!pkgDir.startsWith(baseRoot)) throw new IOException("非法导出路径");
                ensureDirOwned(pkgDir, 1023, 1079, 0775);

                job.phase = "exporting_apk";
                for (int i = 0; i < job.apkPaths.size(); i++) {
                    ensureAlive(job);
                    String guestApk = job.apkPaths.get(i);
                    String rawName = Paths.get(guestApk).getFileName().toString();
                    String safeName = sanitizeName(rawName, "part_" + i + ".apk");
                    if (job.apkPaths.size() == 1 && "base.apk".equals(safeName)) safeName = job.pkg + ".apk";
                    Path outApk = pkgDir.resolve(safeName);
                    long len = getSourceFileLength(job, guestApk);
                    job.detail = String.format(Locale.US, "导出 APK (%d/%d): %s", i + 1, job.apkPaths.size(), safeName);
                    streamGuestFileToHost(job, guestApk, outApk, len, 1023, 1079, 0664);
                    scanMediaFileHost("/sdcard/Download/IrisBoxExport/" + job.pkg + "/" + safeName);
                }

                if (job.includeObb && !job.obbNames.isEmpty()) {
                    job.phase = "exporting_obb";
                    Path obbDir = pkgDir.resolve("obb");
                    ensureDirOwned(obbDir, 1023, 1079, 0775);
                    for (int i = 0; i < job.obbNames.size(); i++) {
                        ensureAlive(job);
                        String obbName = sanitizeName(job.obbNames.get(i), "main_" + i + ".obb");
                        String srcObb = HOST_OBB_ROOT + "/" + job.pkg + "/" + obbName;
                        long len = getSourceFileLength(job, srcObb);
                        job.detail = String.format(Locale.US, "导出 OBB (%d/%d): %s", i + 1, job.obbNames.size(), obbName);
                        streamGuestFileToHost(job, srcObb, obbDir.resolve(obbName), len, 1023, 1079, 0664);
                    }
                }
                try { runHost(20, "/system/bin/restorecon", "-R", pkgDir.toString()); } catch (Exception ignored) {}
                job.doneBytes = job.totalBytes;
                job.phase = "done";
                job.detail = "已导出至宿主机 /sdcard/Download/IrisBoxExport/" + job.pkg;
                job.success = true;
            } else {
                job.phase = "installing_host";
                job.detail = "正在宿主机创建安装会话…";
                String createOut = runHost(30, "/system/bin/pm", "install-create", "-r", "-S", Long.toString(job.apkBytes));
                Matcher m = SESSION_PATTERN.matcher(createOut);
                if (!m.find()) throw new IOException("宿主机创建安装会话失败：" + trimMsg(createOut));
                hostInstallSession = m.group(1);

                for (int i = 0; i < job.apkPaths.size(); i++) {
                    ensureAlive(job);
                    String guestApk = job.apkPaths.get(i);
                    long len = getSourceFileLength(job, guestApk);
                    String safeName = sanitizeName(Paths.get(guestApk).getFileName().toString(), "split_" + i + ".apk");
                    job.detail = String.format(Locale.US, "安装至宿主机 (%d/%d): %s", i + 1, job.apkPaths.size(), safeName);
                    streamGuestApkToHostPm(job, guestApk, len, hostInstallSession, i + "_" + safeName);
                }

                ensureAlive(job);
                job.detail = "提交宿主机安装…";
                String commitOut = runHost(600, "/system/bin/pm", "install-commit", hostInstallSession);
                if (!commitOut.contains("Success")) throw new IOException("宿主机安装失败：" + trimMsg(commitOut));
                hostInstallSession = "";

                if (job.includeObb && !job.obbNames.isEmpty()) {
                    job.phase = "exporting_obb";
                    int hostAppUid = resolveHostAppUid(job.pkg);
                    int obbUid = hostAppUid >= 10000 ? hostAppUid : 1023;
                    int obbGid = hostAppUid >= 10000 ? 1079 : 1023;
                    Path hostObbDir = Paths.get(HOST_OBB_ROOT, job.pkg).normalize();
                    if (!hostObbDir.startsWith(Paths.get(HOST_OBB_ROOT))) throw new IOException("非法 OBB 目录");
                    ensureDirOwned(hostObbDir, obbUid, obbGid, 0770);
                    for (int i = 0; i < job.obbNames.size(); i++) {
                        ensureAlive(job);
                        String obbName = sanitizeName(job.obbNames.get(i), "main_" + i + ".obb");
                        String srcObb = HOST_OBB_ROOT + "/" + job.pkg + "/" + obbName;
                        long len = getSourceFileLength(job, srcObb);
                        job.detail = String.format(Locale.US, "导出 OBB 至宿主机 (%d/%d): %s", i + 1, job.obbNames.size(), obbName);
                        streamGuestFileToHost(job, srcObb, hostObbDir.resolve(obbName), len, obbUid, obbGid, 0660);
                    }
                    try { runHost(20, "/system/bin/restorecon", "-R", hostObbDir.toString()); } catch (Exception ignored) {}
                }
                job.doneBytes = job.totalBytes;
                job.phase = "done";
                job.detail = "已安装到宿主机：" + job.pkg;
                job.success = true;
            }
        } catch (Exception e) {
            if (!hostInstallSession.isEmpty()) {
                try { runHost(15, "/system/bin/pm", "install-abandon", hostInstallSession); } catch (Exception ignored) {}
            }
            job.phase = "failed";
            job.error = trimMsg(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            job.detail = job.error;
            job.success = false;
        } finally {
            job.finished = true;
        }
    }

    private void streamGuestFileToHost(Job job, String guestSrc, Path hostDst, long expectedLen, int uid, int gid, int mode) throws Exception {
        Process srcProc = startCtlExec(job.srcId, job.srcSession, 900, false, "/system/bin/cat", guestSrc);
        srcProc.getOutputStream().close();
        ByteArrayOutputStream srcErr = new ByteArrayOutputStream();
        Thread errDrainer = drainStreamAsync(srcProc.getErrorStream(), srcErr);
        Files.deleteIfExists(hostDst);
        long written = 0;
        try (InputStream in = srcProc.getInputStream();
             OutputStream out = Files.newOutputStream(hostDst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) {
                ensureAlive(job);
                out.write(buf, 0, n);
                written += n;
                job.doneBytes += n;
            }
            out.flush();
            if (!srcProc.waitFor(905L, TimeUnit.SECONDS)) throw new IOException("读取容器文件超时");
            errDrainer.join(500);
            if (srcProc.exitValue() != 0) throw new IOException("读取容器文件失败：" + trimMsg(srcErr.toString(StandardCharsets.UTF_8.name())));
        } finally {
            srcProc.destroyForcibly();
        }
        if (written != expectedLen) {
            try { Files.deleteIfExists(hostDst); } catch (Exception ignored) {}
            throw new IOException("导出文件长度不一致（期望 " + expectedLen + " 字节，实际 " + written + " 字节）");
        }
        Os.lchown(hostDst.toString(), uid, gid);
        Os.chmod(hostDst.toString(), mode);
    }

    private void streamGuestApkToHostPm(Job job, String guestApk, long expectedLen, String pmSession, String splitName) throws Exception {
        Process srcProc = startCtlExec(job.srcId, job.srcSession, 900, false, "/system/bin/cat", guestApk);
        srcProc.getOutputStream().close();
        ByteArrayOutputStream srcErr = new ByteArrayOutputStream();
        Thread srcErrDrainer = drainStreamAsync(srcProc.getErrorStream(), srcErr);

        ProcessBuilder pmPb = new ProcessBuilder(BB, "timeout", "600",
            "/system/bin/pm", "install-write", "-S", Long.toString(expectedLen), pmSession, splitName, "-");
        pmPb.environment().remove("CLASSPATH");
        pmPb.redirectErrorStream(true);
        Process pmProc = pmPb.start();
        ByteArrayOutputStream pmOut = new ByteArrayOutputStream();
        Thread pmOutDrainer = drainStreamAsync(pmProc.getInputStream(), pmOut);

        long written = 0;
        try (InputStream in = srcProc.getInputStream();
             OutputStream pmIn = pmProc.getOutputStream()) {
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) {
                ensureAlive(job);
                pmIn.write(buf, 0, n);
                written += n;
                job.doneBytes += n;
            }
            pmIn.flush();
        } catch (IOException e) {
            pmOutDrainer.join(500);
            throw new IOException("写入宿主机安装包失败：" + trimMsg(pmOut.toString(StandardCharsets.UTF_8.name()) + " " + e.getMessage()));
        } finally {
            if (!srcProc.waitFor(10L, TimeUnit.SECONDS)) srcProc.destroyForcibly();
            srcErrDrainer.join(300);
        }
        if (!pmProc.waitFor(605L, TimeUnit.SECONDS)) {
            pmProc.destroyForcibly();
            throw new IOException("宿主机 pm install-write 超时");
        }
        pmOutDrainer.join(500);
        String res = pmOut.toString(StandardCharsets.UTF_8.name());
        if (pmProc.exitValue() != 0 || !res.contains("Success")) {
            throw new IOException("宿主机 install-write 失败：" + trimMsg(res));
        }
        if (written != expectedLen || srcProc.exitValue() != 0) {
            throw new IOException("容器读取不完整：" + trimMsg(srcErr.toString(StandardCharsets.UTF_8.name())));
        }
    }

    private static int resolveHostAppUid(String pkg) {
        try {
            String out = runHost(15, "/system/bin/sh", "-c",
                "dumpsys package \"$1\" | /data/irisbox/host/bin/busybox sed -n 's/.*userId=\\([0-9][0-9]*\\).*/\\1/p' | /data/irisbox/host/bin/busybox head -n 1",
                "sh", pkg).trim();
            if (out.matches("[0-9]+")) return Integer.parseInt(out);
        } catch (Exception ignored) {}
        return -1;
    }

    private void execute(Job job) {
        String guestTmp = "/data/local/tmp/iris_app_" + job.token;
        String installSession = "";
        try {
            ensureAlive(job);
            if (job.includeExtData || job.includeAppData) {
                if (job.srcId > 0) {
                    try { execContainer(job.srcId, job.srcSession, 15, null, "/system/bin/am", "force-stop", job.pkg); } catch (Exception ignored) {}
                }
                try { execGuest(job, 15, null, "/system/bin/am", "force-stop", job.pkg); } catch (Exception ignored) {}
            }

            job.phase = "installing_apk";
            job.detail = "创建目标容器安装会话…";

            String createOut = execGuest(job, 30, null,
                "/system/bin/pm", "install-create", "-r", "-S", Long.toString(job.apkBytes));
            Matcher m = SESSION_PATTERN.matcher(createOut);
            if (!m.find()) {
                throw new IOException("创建安装会话失败：" + trimMsg(createOut));
            }
            installSession = m.group(1);

            for (int i = 0; i < job.apkPaths.size(); i++) {
                ensureAlive(job);
                String apkPath = job.apkPaths.get(i);
                long apkLen = getSourceFileLength(job, apkPath);
                String safeName = sanitizeName(Paths.get(apkPath).getFileName().toString(), "split_" + i + ".apk");
                String tmpApk = guestTmp + "/" + i + ".apk";
                job.detail = String.format(Locale.US, "同盘直传安装包 (%d/%d): %s", i + 1, job.apkPaths.size(), safeName);

                transferSingleFile(job, apkPath, tmpApk, apkLen, 0644, 0, 0);

                job.detail = String.format(Locale.US, "写入安装分片 (%d/%d)…", i + 1, job.apkPaths.size());
                String splitTag = i + "_" + safeName;
                try {
                    String writeOut = execGuest(job, 600, null,
                        "/system/bin/pm", "install-write", "-S", Long.toString(apkLen), installSession, splitTag, tmpApk);
                    if (!writeOut.contains("Success")) {
                        throw new IOException("install-write 失败：" + trimMsg(writeOut));
                    }
                } finally {
                    // 单个分片写完立即清理，减少容器内临时磁盘峰值占用
                    try { execGuest(job, 15, null, "/system/bin/rm", "-f", tmpApk); } catch (Exception ignored) {}
                }
            }

            ensureAlive(job);
            job.detail = "提交安装事务…";
            String commitOut = execGuest(job, 600, null, "/system/bin/pm", "install-commit", installSession);
            if (!commitOut.contains("Success")) {
                throw new IOException("安装失败：" + trimMsg(commitOut));
            }
            installSession = "";

            int dstUid = resolveGuestAppUid(job);

            if (job.includeObb && !job.obbNames.isEmpty()) {
                ensureAlive(job);
                job.phase = "copying_obb";
                String guestObbDir = HOST_OBB_ROOT + "/" + job.pkg;
                int obbUid = dstUid >= 10000 ? dstUid : 1023;
                int obbGid = dstUid >= 10000 ? 1079 : 1023;
                String obbOwner = obbUid + ":" + obbGid;
                execGuest(job, 30, null,
                    "/system/bin/sh", "-c",
                    "mkdir -p /data/media/0/Android/obb \"$1\" && /system/bin/busybox chown 1023:1079 /data/media/0/Android/obb && /system/bin/busybox chown \"$2\" \"$1\" && chmod 0770 /data/media/0/Android/obb \"$1\"",
                    "sh", guestObbDir, obbOwner);

                for (int i = 0; i < job.obbNames.size(); i++) {
                    ensureAlive(job);
                    String obbName = sanitizeName(job.obbNames.get(i), "main_" + i + ".obb");
                    String srcObbPath = HOST_OBB_ROOT + "/" + job.pkg + "/" + obbName;
                    long obbLen = getSourceFileLength(job, srcObbPath);
                    String guestObbPath = guestObbDir + "/" + obbName;
                    job.detail = String.format(Locale.US, "迁入 OBB (%d/%d): %s", i + 1, job.obbNames.size(), obbName);

                    transferSingleFile(job, srcObbPath, guestObbPath, obbLen, 0660, obbUid, obbGid);
                }
                try {
                    execGuest(job, 30, null, "/system/bin/sh", "-c", "restorecon -R \"$1\" >/dev/null 2>&1 || true", "sh", guestObbDir);
                } catch (Exception ignored) {}
            }

            if (job.includeExtData && job.extDataBytes > 0) {
                ensureAlive(job);
                if (dstUid < 10000) throw new IOException("无法解析目标应用 UID，终止外置数据迁移");
                job.phase = "copying_ext_data";
                job.detail = "同盘高速迁入 Android/data 资源…";
                String dstExtDir = GUEST_DATA_EXT_ROOT + "/" + job.pkg;
                if (!copyExtDataDirect(job, dstExtDir, dstUid)) {
                    if (job.srcId == 0) {
                        Path hostExtDir = Paths.get(GUEST_DATA_EXT_ROOT, job.pkg);
                        streamHostTarToGuest(job, hostExtDir, GUEST_DATA_EXT_ROOT, 3600);
                    } else {
                        streamTarBetweenGuests(job,
                            new String[]{"/system/bin/busybox", "tar", "-C", GUEST_DATA_EXT_ROOT, "-cf", "-", job.pkg},
                            new String[]{"/system/bin/sh", "-c", "mkdir -p \"$1\" && exec /system/bin/busybox tar -o -C \"$1\" -xf -", "sh", GUEST_DATA_EXT_ROOT},
                            3600);
                    }
                    job.detail = "修复 Android/data 权限归属…";
                    execGuest(job, 180, null,
                        "/system/bin/sh", "-c",
                        "/system/bin/busybox chown -Rh \"$1:1078\" \"$2\" && " +
                        "/system/bin/busybox find \"$2\" -type d -exec chmod 2770 {} + && " +
                        "/system/bin/busybox find \"$2\" -type f -exec chmod 0660 {} +",
                        "sh", Integer.toString(dstUid), dstExtDir);
                }
                try {
                    execGuest(job, 120, null, "/system/bin/sh", "-c", "restorecon -R \"$1\" >/dev/null 2>&1 || true", "sh", dstExtDir);
                } catch (Exception ignored) {}
            }

            if (job.includeAppData && job.appDataBytes > 0 && job.srcId > 0) {
                ensureAlive(job);
                if (dstUid < 10000) throw new IOException("无法解析目标应用 UID，终止内部资源迁移");
                job.phase = "copying_app_data";
                job.detail = "同盘高速迁入内部热更新与数据 (/data/data)…";

                String srcPrivPkg = GUEST_DATA_PRIV_ROOT + "/" + job.pkg;
                String dePkg = GUEST_DATA_DE_ROOT + "/" + job.pkg;
                if (!copyAppDataDirect(job, srcPrivPkg, dePkg, dstUid)) {
                    String srcDataPackCmd =
                        "cd \"$1\" || exit 1\n" +
                        "set --\n" +
                        "for E in * .*; do\n" +
                        "  case \"$E\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
                        "  [ -e \"$E\" ] || [ -L \"$E\" ] || continue\n" +
                        "  set -- \"$@\" \"$E\"\n" +
                        "done\n" +
                        "[ $# -gt 0 ] || exit 0\n" +
                        "exec /system/bin/busybox tar -cf - \"$@\"\n";
                    String dstDataExtractCmd =
                        "mkdir -p \"$1\" && exec /system/bin/busybox tar -o -C \"$1\" -xf -";
                    streamTarBetweenGuests(job,
                        new String[]{"/system/bin/sh", "-c", srcDataPackCmd, "sh", srcPrivPkg},
                        new String[]{"/system/bin/sh", "-c", dstDataExtractCmd, "sh", srcPrivPkg},
                        3600);
                    String fixPrivOwnerCmd =
                        "PKG_DIR=\"$1\"; UID=\"$2\"\n" +
                        "/system/bin/busybox chown \"$UID:$UID\" \"$PKG_DIR\"\n" +
                        "for E in \"$PKG_DIR\"/* \"$PKG_DIR\"/.*; do\n" +
                        "  B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac\n" +
                        "  [ -e \"$E\" ] || [ -L \"$E\" ] || continue\n" +
                        "  /system/bin/busybox chown -Rh \"$UID:$UID\" \"$E\"\n" +
                        "done\n";
                    execGuest(job, 240, null, "/system/bin/sh", "-c", fixPrivOwnerCmd, "sh", srcPrivPkg, Integer.toString(dstUid));
                    String hasDe = execContainer(job.srcId, job.srcSession, 15, null,
                        "/system/bin/sh", "-c",
                        "if [ -d \"$1\" ]; then for E in \"$1\"/* \"$1\"/.*; do B=${E##*/}; case \"$B\" in .|..|'*'|lib|cache|code_cache) continue ;; esac; [ -e \"$E\" ] && { echo YES; break; }; done; fi",
                        "sh", dePkg);
                    if (hasDe.contains("YES")) {
                        ensureAlive(job);
                        streamTarBetweenGuests(job,
                            new String[]{"/system/bin/sh", "-c", srcDataPackCmd, "sh", dePkg},
                            new String[]{"/system/bin/sh", "-c", dstDataExtractCmd, "sh", dePkg},
                            600);
                        execGuest(job, 120, null, "/system/bin/sh", "-c", fixPrivOwnerCmd, "sh", dePkg, Integer.toString(dstUid));
                    }
                }
                try {
                    execGuest(job, 180, null, "/system/bin/sh", "-c", "restorecon -R \"$1\" \"$2\" >/dev/null 2>&1 || true", "sh", srcPrivPkg, dePkg);
                } catch (Exception ignored) {}
            }

            job.doneBytes = job.totalBytes;
            job.phase = "done";
            StringBuilder doneMsg = new StringBuilder("安装成功");
            if (job.includeObb && !job.obbNames.isEmpty()) doneMsg.append("（含 ").append(job.obbNames.size()).append(" 个 OBB）");
            if (job.includeExtData && job.extDataBytes > 0) doneMsg.append(" · 已迁 Android/data");
            if (job.includeAppData && job.appDataBytes > 0) doneMsg.append(" · 已迁内部资源数据");
            job.detail = doneMsg.toString();
            job.success = true;
        } catch (Exception e) {
            if (!installSession.isEmpty()) {
                try { execGuest(job, 15, null, "/system/bin/pm", "install-abandon", installSession); } catch (Exception ignored) {}
            }
            job.phase = "failed";
            job.error = trimMsg(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            job.detail = job.error;
            job.success = false;
        } finally {
            try {
                if (job.session.equals(state.session(job.id))) {
                    execGuest(job, 15, null, "/system/bin/rm", "-rf", guestTmp);
                }
            } catch (Exception ignored) {}
            job.finished = true;
        }
    }

    private int resolveGuestAppUid(Job job) {
        try {
            String out = execGuest(job, 20, null, "/system/bin/sh", "-c",
                "dumpsys package \"$1\" | /system/bin/busybox sed -n 's/.*userId=\\([0-9][0-9]*\\).*/\\1/p' | /system/bin/busybox head -n 1",
                "sh", job.pkg).trim();
            if (out.matches("[0-9]+")) return Integer.parseInt(out);
        } catch (Exception ignored) {}
        return -1;
    }

    private long getSourceFileLength(Job job, String path) throws Exception {
        if (job.srcId == 0) return Files.size(Paths.get(path));
        Path direct = safeInstanceDataPath(job.srcId, path);
        if (direct != null && Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(direct)) {
            return Files.size(direct);
        }
        String sz = execContainer(job.srcId, job.srcSession, 15, null,
            "/system/bin/sh", "-c", "/system/bin/busybox stat -c %s \"$1\"", "sh", path).trim();
        return Long.parseLong(sz);
    }

    /** 解析实例的 userns 起始宿主 UID 偏移（如 2000100000），校验非软链与格式合法性 */
    private static int resolveUsernsBaseUid(int id) throws IOException {
        Path map = Paths.get(BASE, "config/userns_v1", id + ".map");
        if (Files.isSymbolicLink(map) || Files.isSymbolicLink(map.getParent()) || !Files.isRegularFile(map, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing userns map for instance " + id);
        }
        String line = new String(Files.readAllBytes(map), StandardCharsets.UTF_8).trim();
        String[] parts = line.split("\\s+");
        if (parts.length != 4 || !"1".equals(parts[0])) throw new IOException("Invalid userns map");
        int base = Integer.parseInt(parts[1]), count = Integer.parseInt(parts[2]);
        if (base < 2000000000 || count < 65536) throw new IOException("Invalid userns range");
        return base;
    }

    /** 安全将容器内 /data/... 路径映射到宿主机 instances/<id>/data/... 物理路径（禁止符号链接越界） */
    static Path safeInstanceDataPath(int id, String guestPath) {
        try {
            if (id <= 0 || guestPath == null || !guestPath.startsWith("/data/") || guestPath.contains("..")) return null;
            Path inst = Paths.get(BASE, "instances", Integer.toString(id));
            Path dataRoot = inst.resolve("data");
            if (Files.isSymbolicLink(inst) || Files.isSymbolicLink(dataRoot) || !Files.isDirectory(dataRoot, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path realRoot = dataRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            String rel = guestPath.substring(6);
            Path target = realRoot.resolve(rel).normalize();
            if (!target.startsWith(realRoot)) return null;
            Path cur = realRoot;
            for (Path part : realRoot.relativize(target)) {
                cur = cur.resolve(part);
                if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cur)) return null;
            }
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    static void ensureDirOwned(Path dir, int hostUid, int hostGid, int mode) throws Exception {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = dir.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                ensureDirOwned(parent, hostUid, hostGid, mode);
            }
            Files.createDirectory(dir);
        } else if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe directory path: " + dir);
        }
        Os.lchown(dir.toString(), hostUid, hostGid);
        Os.chmod(dir.toString(), mode);
    }

    private void copySingleFileDirect(Job job, Path src, Path dst, int hostUid, int hostGid, int mode) throws Exception {
        byte[] buf = new byte[COPY_BUFFER_BYTES];
        Files.deleteIfExists(dst);
        try (InputStream in = Files.newInputStream(src, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = Files.newOutputStream(dst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                ensureAlive(job);
                out.write(buf, 0, n);
                job.doneBytes += n;
            }
            out.flush();
        }
        Os.lchown(dst.toString(), hostUid, hostGid);
        Os.chmod(dst.toString(), mode);
    }

    private void transferSingleFile(Job job, String srcGuestOrHostPath, String dstGuestPath, long expectedLen, int fileMode, int guestUid, int guestGid) throws Exception {
        Path srcHost = job.srcId == 0 ? Paths.get(srcGuestOrHostPath) : safeInstanceDataPath(job.srcId, srcGuestOrHostPath);
        Path dstHost = safeInstanceDataPath(job.id, dstGuestPath);
        if (srcHost != null && dstHost != null && Files.isRegularFile(srcHost, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(srcHost)) {
            int dstBase = resolveUsernsBaseUid(job.id);
            int hostUid = dstBase + guestUid, hostGid = dstBase + guestGid;
            ensureDirOwned(dstHost.getParent(), hostUid, hostGid, guestUid >= 10000 ? 0770 : 0755);
            long before = job.doneBytes;
            copySingleFileDirect(job, srcHost, dstHost, hostUid, hostGid, fileMode);
            if (job.doneBytes - before != expectedLen) {
                throw new IOException("同盘文件长度不一致（期望 " + expectedLen + " 字节）");
            }
            return;
        }
        // 回退路径：管道流写入目标容器
        String uidArg = guestUid + ":" + guestGid;
        String chmodArg = Integer.toOctalString(fileMode);
        streamFileToGuest(job, srcGuestOrHostPath, expectedLen, 1800,
            "/system/bin/sh", "-c",
            "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\" && /system/bin/busybox chown \"$2\" \"$1\" && chmod \"$3\" \"$1\"",
            "sh", dstGuestPath, uidArg, chmodArg);
    }

    private void copyTreeDirect(Job job, Path srcDir, Path dstDir, int hostUid, int hostGid,
                                int fixedDirMode, int fixedFileMode, Set<String> skipTopNames) throws Exception {
        ensureDirOwned(dstDir, hostUid, hostGid, fixedDirMode > 0 ? fixedDirMode : 0700);
        byte[] buf = new byte[COPY_BUFFER_BYTES];
        ArrayDeque<Path[]> queue = new ArrayDeque<>();
        queue.add(new Path[]{srcDir, dstDir});
        boolean isTop = true;
        while (!queue.isEmpty()) {
            ensureAlive(job);
            Path[] pair = queue.removeFirst();
            Path curSrc = pair[0], curDst = pair[1];
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(curSrc)) {
                for (Path entry : ds) {
                    String name = entry.getFileName().toString();
                    if (isTop && skipTopNames != null && skipTopNames.contains(name)) continue;
                    Path target = curDst.resolve(name);
                    if (Files.isSymbolicLink(entry)) {
                        Path link = Files.readSymbolicLink(entry);
                        if (!link.isAbsolute() && !link.toString().contains("..")) {
                            Files.deleteIfExists(target);
                            Os.symlink(link.toString(), target.toString());
                            Os.lchown(target.toString(), hostUid, hostGid);
                        }
                        continue;
                    }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        int dmode = fixedDirMode > 0 ? fixedDirMode : (Os.lstat(entry.toString()).st_mode & 07777);
                        ensureDirOwned(target, hostUid, hostGid, dmode);
                        queue.add(new Path[]{entry, target});
                    } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        int fmode = fixedFileMode > 0 ? fixedFileMode : (Os.lstat(entry.toString()).st_mode & 07777);
                        Files.deleteIfExists(target);
                        try (InputStream in = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS);
                             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                ensureAlive(job);
                                out.write(buf, 0, n);
                                job.doneBytes += n;
                            }
                            out.flush();
                        }
                        Os.lchown(target.toString(), hostUid, hostGid);
                        Os.chmod(target.toString(), fmode);
                    }
                }
            }
            isTop = false;
        }
    }

    private boolean copyExtDataDirect(Job job, String guestExtPkgDir, int dstUid) throws Exception {
        Path srcHost = job.srcId == 0 ? Paths.get(guestExtPkgDir) : safeInstanceDataPath(job.srcId, guestExtPkgDir);
        Path dstHost = safeInstanceDataPath(job.id, guestExtPkgDir);
        if (srcHost == null || dstHost == null || !Files.isDirectory(srcHost, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(srcHost)) {
            return false;
        }
        int dstBase = resolveUsernsBaseUid(job.id);
        ensureDirOwned(dstHost.getParent(), dstBase + 1023, dstBase + 1078, 02771);
        copyTreeDirect(job, srcHost, dstHost, dstBase + dstUid, dstBase + 1078, 02770, 0660, null);
        return true;
    }

    private boolean copyAppDataDirect(Job job, String guestPrivPkgDir, String guestDePkgDir, int dstUid) throws Exception {
        Path srcPriv = safeInstanceDataPath(job.srcId, guestPrivPkgDir);
        Path dstPriv = safeInstanceDataPath(job.id, guestPrivPkgDir);
        if (srcPriv == null || dstPriv == null || !Files.isDirectory(srcPriv, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(srcPriv)) {
            return false;
        }
        int dstBase = resolveUsernsBaseUid(job.id);
        Set<String> skips = new HashSet<>(Arrays.asList("lib", "cache", "code_cache"));
        int privRootMode = Os.lstat(srcPriv.toString()).st_mode & 07777;
        copyTreeDirect(job, srcPriv, dstPriv, dstBase + dstUid, dstBase + dstUid, 0, 0, skips);
        Os.chmod(dstPriv.toString(), privRootMode);

        Path srcDe = safeInstanceDataPath(job.srcId, guestDePkgDir);
        Path dstDe = safeInstanceDataPath(job.id, guestDePkgDir);
        if (srcDe != null && dstDe != null && Files.isDirectory(srcDe, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(srcDe)) {
            int deRootMode = Os.lstat(srcDe.toString()).st_mode & 07777;
            copyTreeDirect(job, srcDe, dstDe, dstBase + dstUid, dstBase + dstUid, 0, 0, skips);
            Os.chmod(dstDe.toString(), deRootMode);
        }
        return true;
    }

    private void ensureAlive(Job job) throws IOException {
        if (!job.session.equals(state.session(job.id)) || state.stopping(job.id)) {
            throw new IOException("目标实例已停止或会话已变化，操作中止");
        }
        if (job.srcId > 0 && (!job.srcSession.equals(state.session(job.srcId)) || state.stopping(job.srcId))) {
            throw new IOException("来源实例已停止或会话已变化，操作中止");
        }
    }

    /** 通过宿主 cmd package path 获取准确待迁入的 APK（及 Split APKs）列表 */
    private static List<Path> resolveHostPackageApks(String pkg) throws Exception {
        String out = runHost(15, "/system/bin/cmd", "package", "path", pkg);
        if (out.trim().isEmpty()) out = runHost(15, "/system/bin/pm", "path", pkg);
        ArrayList<Path> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String rawPath = line.substring(8).trim();
            if (!isAllowedHostApkPath(rawPath)) continue;
            Path p = Paths.get(rawPath).normalize();
            if (!Files.isSymbolicLink(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && rawPath.endsWith(".apk")) {
                list.add(p);
            }
        }
        return list;
    }

    /** 枚举目录内作为 APK 大小预估的常规 .apk 文件 */
    private static List<Path> collectApkFilesFromHint(String baseApkPath) {
        ArrayList<Path> out = new ArrayList<>();
        try {
            if (!isAllowedHostApkPath(baseApkPath)) return out;
            Path base = Paths.get(baseApkPath).normalize();
            if (Files.isSymbolicLink(base) || !Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)) return out;
            Path dir = base.getParent();
            if (dir != null && !Files.isSymbolicLink(dir) && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.apk")) {
                    for (Path entry : ds) {
                        if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            out.add(entry);
                        }
                    }
                }
            }
            if (out.isEmpty()) out.add(base);
        } catch (Exception ignored) {}
        return out;
    }

    private static List<Path> collectObbFiles(String pkg) {
        ArrayList<Path> out = new ArrayList<>();
        try {
            Path dir = Paths.get(HOST_OBB_ROOT, pkg).normalize();
            if (!dir.startsWith(Paths.get(HOST_OBB_ROOT)) || Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return out;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path entry : ds) {
                    String name = entry.getFileName().toString();
                    if (!Files.isSymbolicLink(entry) && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                        name.endsWith(".obb") && name.matches("[a-zA-Z0-9._-]+")) {
                        out.add(entry);
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static long safeDirBytesHost(Path dir) {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) return 0L;
            String out = runHost(10, BB, "du", "-sk", dir.toString()).trim();
            String[] parts = out.split("\\s+");
            if (parts.length >= 1 && parts[0].matches("[0-9]+")) return Long.parseLong(parts[0]) * 1024L;
        } catch (Exception ignored) {}
        return 0L;
    }

    private static boolean validPkg(String pkg) {
        return pkg != null && pkg.length() >= 3 && pkg.length() <= 120 && pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+");
    }

    private static boolean isAllowedHostApkPath(String path) {
        if (path == null || path.isEmpty() || path.contains("..")) return false;
        return path.startsWith("/data/app/") || path.startsWith("/mnt/asec/");
    }

    private static String sanitizeName(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        String clean = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isEmpty() || clean.startsWith(".") ? fallback : clean;
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0; }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static String trimMsg(String s) {
        if (s == null) return "";
        s = s.replace('\r', ' ').replace('\n', ' ').trim();
        return s.length() > 220 ? s.substring(0, 220) : s;
    }

    static void scanMediaFileHost(String publicSdcardPath) {
        if (publicSdcardPath == null || publicSdcardPath.isEmpty()) return;
        try {
            runHost(10, "/system/bin/am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://" + publicSdcardPath);
        } catch (Exception ignored) {}
    }

    /** 直接解析 APK 中 AndroidManifest.xml 的 application:label（优先简体中文），带 LRU 缓存 */
    static String resolveApkLabel(Path hostApk, String fallbackPkg) {
        if (hostApk == null) return fallbackPkg;
        try {
            if (Files.isSymbolicLink(hostApk) || !Files.isRegularFile(hostApk, LinkOption.NOFOLLOW_LINKS)) {
                return fallbackPkg;
            }
            long size = Files.size(hostApk);
            long mtime = Files.getLastModifiedTime(hostApk).toMillis();
            String cacheKey = hostApk.toString() + ":" + mtime + ":" + size;
            String cached = LABEL_CACHE.get(cacheKey);
            if (cached != null) return cached;

            AssetManager am = null;
            XmlResourceParser parser = null;
            String resolved = null;
            try {
                am = AssetManager.class.getDeclaredConstructor().newInstance();
                Integer cookieObj = (Integer) AssetManager.class.getMethod("addAssetPath", String.class)
                    .invoke(am, hostApk.toString());
                int cookie = cookieObj != null ? cookieObj : 0;
                if (cookie != 0) {
                    parser = am.openXmlResourceParser(cookie, "AndroidManifest.xml");
                    int event = parser.getEventType();
                    while (event != XmlResourceParser.END_DOCUMENT) {
                        if (event == XmlResourceParser.START_TAG && "application".equals(parser.getName())) {
                            int attrCount = parser.getAttributeCount();
                            for (int i = 0; i < attrCount; i++) {
                                if (!"label".equals(parser.getAttributeName(i))) continue;
                                int resId = parser.getAttributeResourceValue(i, 0);
                                if (resId != 0) {
                                    Configuration cfg = new Configuration();
                                    cfg.setLocale(Locale.SIMPLIFIED_CHINESE);
                                    DisplayMetrics dm = new DisplayMetrics();
                                    dm.setToDefaults();
                                    Resources res = new Resources(am, dm, cfg);
                                    CharSequence cs = res.getText(resId);
                                    if (cs != null) resolved = cs.toString().trim();
                                } else {
                                    String direct = parser.getAttributeValue(i);
                                    if (direct != null && !direct.startsWith("@")) resolved = direct.trim();
                                }
                                break;
                            }
                            break;
                        }
                        event = parser.next();
                    }
                }
            } finally {
                if (parser != null) {
                    try { parser.close(); } catch (Throwable ignored) {}
                }
                if (am != null) {
                    try { am.close(); } catch (Throwable ignored) {}
                }
            }
            if (resolved != null && !resolved.isEmpty()) {
                LABEL_CACHE.put(cacheKey, resolved);
                return resolved;
            }
        } catch (Throwable ignored) {}
        return fallbackPkg;
    }

    static String runHost(int timeoutSec, String... cmd) throws Exception {
        ArrayList<String> full = new ArrayList<>();
        full.add(BB);
        full.add("timeout");
        full.add(Integer.toString(timeoutSec));
        full.addAll(Arrays.asList(cmd));
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.environment().remove("CLASSPATH");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() < 262144) out.write(buf, 0, n);
            }
        }
        if (!p.waitFor(timeoutSec + 3L, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("宿主命令超时");
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static Process startCtlExec(int containerId, String session, int timeoutSec, boolean mergeStderr, String... guestCmd) throws IOException {
        ArrayList<String> cmd = new ArrayList<>();
        cmd.add(BB);
        cmd.add("timeout");
        cmd.add(Integer.toString(timeoutSec));
        cmd.add("/system/bin/sh");
        cmd.add(BASE + "/ctl.sh");
        cmd.add("exec");
        cmd.add(Integer.toString(containerId));
        cmd.addAll(Arrays.asList(guestCmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("CLASSPATH");
        pb.environment().put("IRIS_EXPECT_SESSION", session);
        pb.redirectErrorStream(mergeStderr);
        return pb.start();
    }

    private String execGuest(Job job, int timeoutSec, InputStream stdinData, String... guestCmd) throws Exception {
        return execContainerWithProgress(job, job.id, job.session, timeoutSec, stdinData, guestCmd);
    }

    private String execContainer(int containerId, String session, int timeoutSec, InputStream stdinData, String... guestCmd) throws Exception {
        return execContainerWithProgress(null, containerId, session, timeoutSec, stdinData, guestCmd);
    }

    private String execContainerWithProgress(Job progressJob, int containerId, String session, int timeoutSec, InputStream stdinData, String... guestCmd) throws Exception {
        Process p = startCtlExec(containerId, session, timeoutSec, true, guestCmd);
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (capture) {
                        if (capture.size() < 262144) capture.write(buf, 0, Math.min(n, 262144 - capture.size()));
                    }
                }
            } catch (IOException ignored) {}
        });
        drainer.setDaemon(true);
        drainer.start();

        try {
            if (stdinData != null) {
                try (OutputStream os = p.getOutputStream()) {
                    byte[] buf = new byte[COPY_BUFFER_BYTES];
                    int n;
                    while ((n = stdinData.read(buf)) != -1) {
                        if (progressJob != null) ensureAlive(progressJob);
                        os.write(buf, 0, n);
                        if (progressJob != null) progressJob.doneBytes += n;
                    }
                    os.flush();
                }
            } else {
                p.getOutputStream().close();
            }
            if (!p.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) {
                throw new IOException("容器执行超时");
            }
            drainer.join(500);
            String result;
            synchronized (capture) { result = capture.toString(StandardCharsets.UTF_8.name()); }
            if (p.exitValue() != 0) {
                throw new IOException(result.isEmpty() ? "退出码 " + p.exitValue() : result);
            }
            return result;
        } finally {
            p.destroyForcibly();
        }
    }

    private void streamFileToGuest(Job job, String sourcePath, long expectedLen, int timeoutSec, String... guestCmd) throws Exception {
        long before = job.doneBytes;
        if (job.srcId == 0) {
            try (InputStream fis = Files.newInputStream(Paths.get(sourcePath))) {
                execGuest(job, timeoutSec, fis, guestCmd);
            }
        } else {
            Process srcProc = startCtlExec(job.srcId, job.srcSession, timeoutSec, false, "/system/bin/cat", sourcePath);
            srcProc.getOutputStream().close();
            ByteArrayOutputStream srcErr = new ByteArrayOutputStream();
            Thread errDrainer = drainStreamAsync(srcProc.getErrorStream(), srcErr);
            try (InputStream srcStdout = srcProc.getInputStream()) {
                execGuest(job, timeoutSec, srcStdout, guestCmd);
                if (!srcProc.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) {
                    throw new IOException("读取源容器文件超时");
                }
                errDrainer.join(500);
                if (srcProc.exitValue() != 0) {
                    throw new IOException("读取源容器文件失败：" + trimMsg(srcErr.toString(StandardCharsets.UTF_8.name())));
                }
            } finally {
                srcProc.destroyForcibly();
            }
        }
        long written = job.doneBytes - before;
        if (written != expectedLen) {
            throw new IOException("文件读取长度不一致（期望 " + expectedLen + " 字节，实际 " + written + " 字节）");
        }
    }

    private void streamHostTarToGuest(Job job, Path hostDir, String guestParentDir, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(BB, "timeout", Integer.toString(timeoutSec), BB, "tar",
            "-C", hostDir.getParent().toString(), "-cf", "-", hostDir.getFileName().toString());
        pb.environment().remove("CLASSPATH");
        Process srcProc = pb.start();
        srcProc.getOutputStream().close();
        ByteArrayOutputStream srcErr = new ByteArrayOutputStream();
        Thread errDrainer = drainStreamAsync(srcProc.getErrorStream(), srcErr);
        try (InputStream srcStdout = srcProc.getInputStream()) {
            execGuest(job, timeoutSec, srcStdout,
                "/system/bin/sh", "-c", "mkdir -p \"$1\" && exec /system/bin/busybox tar -o -C \"$1\" -xf -", "sh", guestParentDir);
            if (!srcProc.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) throw new IOException("宿主机数据打包超时");
            errDrainer.join(500);
            if (srcProc.exitValue() != 0) throw new IOException("宿主机数据打包失败：" + trimMsg(srcErr.toString(StandardCharsets.UTF_8.name())));
        } finally {
            srcProc.destroyForcibly();
        }
    }

    private void streamTarBetweenGuests(Job job, String[] srcCmd, String[] dstCmd, int timeoutSec) throws Exception {
        Process srcProc = startCtlExec(job.srcId, job.srcSession, timeoutSec, false, srcCmd);
        srcProc.getOutputStream().close();
        ByteArrayOutputStream srcErr = new ByteArrayOutputStream();
        Thread errDrainer = drainStreamAsync(srcProc.getErrorStream(), srcErr);
        try (InputStream srcStdout = srcProc.getInputStream()) {
            execGuest(job, timeoutSec, srcStdout, dstCmd);
            if (!srcProc.waitFor(timeoutSec + 5L, TimeUnit.SECONDS)) throw new IOException("源容器数据读取超时");
            errDrainer.join(500);
            if (srcProc.exitValue() != 0) throw new IOException("源容器数据打包失败：" + trimMsg(srcErr.toString(StandardCharsets.UTF_8.name())));
        } finally {
            srcProc.destroyForcibly();
        }
    }

    private static Thread drainStreamAsync(InputStream err, ByteArrayOutputStream capture) {
        Thread t = new Thread(() -> {
            try (InputStream in = err) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (capture) {
                        if (capture.size() < 16384) capture.write(buf, 0, Math.min(n, 16384 - capture.size()));
                    }
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
