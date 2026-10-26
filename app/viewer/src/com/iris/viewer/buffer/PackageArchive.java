package com.irisbox.viewer;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** 有界、逐文件校验的 ZIP 读取器。只产生普通文件，不保留归档权限或链接。 */
final class PackageArchive {
    interface Progress { void update(String message); }
    static final String PROFILE = "rpi4-a13-v1";
    static final String FINGERPRINT = "Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys";
    static boolean safePath(String p) {
        if (p.length() > 240 || !p.matches("[A-Za-z0-9._@+/-]+") || p.startsWith("/") || p.endsWith("/")) return false;
        for (String part : p.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    static String hex(byte[] value) {
        StringBuilder s = new StringBuilder(); for (byte b : value) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString();
    }
    static String hash(File file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[1048576]; int n; while ((n = in.read(b)) != -1) d.update(b, 0, n); }
        return hex(d.digest());
    }
    static JSONObject extract(InputStream input, File dest, String manifestName, boolean rom, Progress progress) throws Exception {
        if (!dest.isDirectory() || dest.list().length != 0) throw new IOException("暂存目录必须为空");
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input, 1048576))) {
            ZipEntry first = zip.getNextEntry();
            if (first == null || !first.getName().equals(manifestName)) throw new IOException("文件不是有效的 虹膜空间 包；首项必须是 " + manifestName);
            ByteArrayOutputStream header = new ByteArrayOutputStream(); byte[] b = new byte[1048576]; int n;
            while ((n = zip.read(b)) != -1) { if (header.size() + n > 131072) throw new IOException("包清单过大"); header.write(b, 0, n); }
            JSONObject manifest = new JSONObject(new String(header.toByteArray(), StandardCharsets.UTF_8));
            if (manifest.getInt("formatVersion") != 1 || !manifest.getString("compatibility").equals(PROFILE)) throw new IOException("包格式或适配版本不支持");
            if (rom && (!manifest.getString("id").matches("[a-z0-9][a-z0-9._-]{0,63}") || manifest.getString("id").contains("..") ||
                !manifest.getString("abi").equals("arm64-v8a") || manifest.getInt("androidApi") != 33 ||
                !manifest.getString("hostFingerprint").equals(FINGERPRINT))) throw new IOException("ROM 需要匹配的树莓派4 AOSP13 ARM64 适配包");
            JSONObject files = manifest.getJSONObject("files");
            if (files.length() < 1 || files.length() > 512) throw new IOException("包文件数超出范围");
            if (rom) for (String required : new String[]{"system.img", "vendor.img", "compat/iris_init", "compat/guest.rc", "compat/guest_vendor.rc", "compat/loaded_files.txt"})
                if (!files.has(required)) throw new IOException("ROM 缺少 " + required);
            long total = 0;
            for (Iterator<String> keys = files.keys(); keys.hasNext();) {
                String name = keys.next(); JSONObject f = files.getJSONObject(name); long size = f.getLong("size");
                if (!safePath(name) || name.equals(manifestName) || name.equals("files.sha256") ||
                    rom && !(name.equals("system.img") || name.equals("vendor.img") || name.startsWith("compat/")) ||
                    size < 0 || size > 4294967296L || !f.getString("sha256").matches("[a-f0-9]{64}")) throw new IOException("包清单包含非法文件：" + name);
                total += size; if (total > (rom ? 6442450944L : 134217728L)) throw new IOException("包解压后过大");
            }
            if (dest.getUsableSpace() < total + 268435456L) throw new IOException("App 暂存空间不足，需要 " + (total / 1048576 + 256) + " MiB");
            Set<String> seen = new HashSet<>(); long done = 0, reported = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !safePath(name) || !files.has(name) || !seen.add(name)) throw new IOException("归档有重复、越界或未声明文件：" + name);
                JSONObject f = files.getJSONObject(name); long expected = f.getLong("size"), bytes = 0;
                File target = new File(dest, name);
                if (!target.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) throw new IOException("路径越界");
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("不能创建暂存目录");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileOutputStream output = new FileOutputStream(target)) {
                    while ((n = zip.read(b)) != -1) {
                        bytes += n; done += n; if (bytes > expected) throw new IOException("文件超过清单大小：" + name);
                        output.write(b, 0, n); digest.update(b, 0, n);
                        if (done - reported >= 16777216L) { progress.update("解压并校验 " + done / 1048576 + " / " + total / 1048576 + " MiB"); reported = done; }
                    }
                    output.getFD().sync();
                }
                if (bytes != expected || !hex(digest.digest()).equals(f.getString("sha256"))) throw new IOException("文件摘要不匹配：" + name);
            }
            if (seen.size() != files.length()) throw new IOException("包被截断，缺少清单文件");
            if (rom) for (String image : new String[]{"system.img", "vendor.img"}) {
                try (RandomAccessFile raw = new RandomAccessFile(new File(dest,image), "r")) {
                    if (raw.length() < 1048576) throw new IOException("ROM 镜像过小");
                    raw.seek(1080);
                    if (raw.readUnsignedByte()!=0x53 || raw.readUnsignedByte()!=0xef) throw new IOException("需要原始 ext4 镜像，不支持 sparse/boot/super："+image);
                }
            }
            try (FileOutputStream out = new FileOutputStream(new File(dest, manifestName))) { out.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(dest, "files.sha256")), StandardCharsets.US_ASCII)) {
                List<String> names = new ArrayList<>(seen); Collections.sort(names);
                for (String name : names) out.write(files.getJSONObject(name).getString("sha256") + "  " + name + "\n");
            }
            return manifest;
        }
    }
    static void erase(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children != null) for (File f : children) {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new IOException("拒绝清理链接目录");
            erase(f);
        }
        if (dir.exists() && !dir.delete()) throw new IOException("不能清理暂存文件");
    }
}
