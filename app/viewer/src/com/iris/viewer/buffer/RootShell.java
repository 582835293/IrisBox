package com.rootvmos.viewer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 仅在工作线程调用。使用标准 Magisk 授权，不修改 Magisk 数据库。 */
final class RootShell {
    static final String HOME = "/data/rootvmos", BASE = HOME + "/runtime";
    private static String su;
    static synchronized String magisk() throws Exception {
        if (su != null) return su;
        for (String p : new String[]{"/debug_ramdisk/su", "/sbin/su", "/system/bin/su", "/system/xbin/su"}) {
            try {
                String version = process(new String[]{p, "-v"}, "", 3);
                if (version.contains("MAGISKSU")) return su = p;
            } catch (IOException ignored) {}
        }
        throw new IOException(I18n.s(R.string.ui_b81c17cb6353));
    }
    static String run(String script, int timeout) throws Exception {
        return process(new String[]{magisk(), "--mount-master", "-c", "/system/bin/sh"}, "set -eu\n[ ! -e /data/rootvmos/engine-upgrade.pending ] || { echo '引擎维护尚未完成，请按维护文档恢复'; exit 4; }\n" + script + "\n", timeout);
    }
    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String process(String[] args, String script, int seconds) throws Exception {
        ProcessBuilder b = new ProcessBuilder(args).redirectErrorStream(true);
        b.environment().put("PATH", "/system/bin:/system/xbin");
        b.environment().remove("CLASSPATH"); b.environment().remove("LD_PRELOAD");
        Process p = b.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] bytes = new byte[4096]; int n;
                while ((n = in.read(bytes)) != -1) synchronized (out) {
                    if (out.size() < 262144) out.write(bytes, 0, Math.min(n, 262144 - out.size()));
                }
            } catch (IOException ignored) {}
        }, "rootvmos-su-output"); reader.setDaemon(true); reader.start();
        try {
            try (OutputStream in = p.getOutputStream()) { in.write(script.getBytes(StandardCharsets.UTF_8)); }
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException(I18n.s(R.string.ui_c90dc17c7d0f));
            reader.join(2000);
            String result; synchronized (out) { result = new String(out.toByteArray(), StandardCharsets.UTF_8).trim(); }
            if (p.exitValue() != 0) throw new IOException(I18n.s(R.string.ui_4e1c76b3df0a) + p.exitValue() + "）：\n" + result);
            return result;
        } finally { p.destroy(); if (p.isAlive()) p.destroyForcibly(); }
    }
}
