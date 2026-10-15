package com.rootvmos.viewer;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

final class EngineInstaller {
    static final String VERSION = "0.1.2-e13";
    static String preflight(Context c) throws Exception {
        HostTools.ensure(c);
        String script;
        try (InputStream in = c.getAssets().open("preflight.sh")) { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] data = new byte[4096]; int n; while ((n=in.read(data))!=-1) b.write(data,0,n); script=new String(b.toByteArray(),StandardCharsets.UTF_8); }
        return RootShell.run(script, 100);
    }
    static File work(Context c) throws Exception {
        File parent = new File(c.getFilesDir(), "package-work");
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(I18n.s(R.string.ui_d96fc7526625));
        File work = new File(parent, UUID.randomUUID().toString());
        if (!work.mkdir()) throw new IOException(I18n.s(R.string.ui_97d351c230ee)); return work;
    }
    static String copyScript(File work, String stage) {
        return "[ ! -L /data/rootvmos ]\nmkdir -p /data/rootvmos\nchmod 0700 /data/rootvmos\n" +
            "[ ! -L /data/rootvmos/runtime ]\nmkdir -p " + RootShell.quote(stage) + "\n" +
            "cp -R " + RootShell.quote(work.getAbsolutePath()+"/.") + " " + RootShell.quote(stage) + "\n" +
            "chown -R 0:0 " + RootShell.quote(stage) + "\ncd " + RootShell.quote(stage) + "\n/data/rootvmos/host/bin/busybox sha256sum -c files.sha256 >/dev/null\n";
    }
    static void install(Context c, PackageArchive.Progress progress) throws Exception {
        progress.update(I18n.s(R.string.ui_79b3bc1a9795)); preflight(c);
        File work = work(c); String stage = "/data/rootvmos/.engine-"+work.getName();
        try {
            progress.update(I18n.s(R.string.ui_c89faf618244));
            JSONObject manifest = PackageArchive.extract(c.getAssets().open("engine.zip"), work, "engine.json", false, progress);
            if (!manifest.getString("version").equals(VERSION)) throw new IOException(I18n.s(R.string.ui_7974c4e8615f));
            String fingerprint = PackageArchive.hash(new File(work,"files.sha256"));
            progress.update(I18n.s(R.string.ui_ee42ae7c12d7));
            String script = "if [ -f /data/rootvmos/runtime/engine.sha256 ]; then\n"+
                "[ \"$(cat /data/rootvmos/runtime/engine.version)\" = " + RootShell.quote(VERSION) + " ] && [ \"$(cat /data/rootvmos/runtime/engine.sha256)\" = " + RootShell.quote(fingerprint) + " ] || { echo '已安装不同版本；请按升级文档处理，当前版本不会覆盖实例'; exit 4; }\n(cd /data/rootvmos/runtime && /data/rootvmos/host/bin/busybox sha256sum -c files.sha256 >/dev/null)\n/system/bin/sh /data/rootvmos/runtime/instances.sh init\nexit 0\nfi\n"+
                "[ ! -e /data/rootvmos/runtime ] || { echo '发现未完成安装，请查看恢复文档'; exit 4; }\n" + copyScript(work,stage) +
                "find . -type d -exec chmod 0755 {} \\;\nfind . -type f -exec chmod 0644 {} \\;\n" +
                "chmod 0755 rootvmos_ns iris_inet_policy display/buffer_bridge/iris_buffer_producer root/magisk_30_7/busybox root/magisk_30_7/magisk root/magisk_30_7/magisk32 root/magisk_30_7/magiskpolicy\n" +
                "echo " + RootShell.quote(fingerprint) + " > engine.sha256\necho " + RootShell.quote(VERSION) + " > engine.version\n"+
                "mkdir -p config roms instances\nchmod 0700 config instances\nsync\ncd /\nmv " + RootShell.quote(stage) + " /data/rootvmos/runtime\n"+
                "/system/bin/sh /data/rootvmos/runtime/instances.sh init\n";
            RootShell.run(script, 180); progress.update(I18n.s(R.string.ui_a28d875aa888));
        } finally { PackageArchive.erase(work); }
    }
    static String importRom(Context c, Uri uri, PackageArchive.Progress progress) throws Exception {
        progress.update(I18n.s(R.string.ui_79eff70947fd)); preflight(c);
        RootShell.run("test -f /data/rootvmos/runtime/engine.version", 10);
        File work = work(c); String stage = "/data/rootvmos/.rom-" + work.getName();
        try {
            JSONObject manifest;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException(I18n.s(R.string.ui_0d1f7519216b));
                manifest = PackageArchive.extract(in, work, "rom.json", true, progress);
            }
            try (InputStream in = c.getAssets().open("rom-profile.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1) bytes.write(b,0,n);
                JSONObject profile = new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                JSONObject declared=manifest.getJSONObject("files");
                for(java.util.Iterator<String> keys=profile.keys();keys.hasNext();) {
                    String file=keys.next();
                    if(!declared.has(file)||!declared.getJSONObject(file).getString("sha256").equals(profile.getString(file)))
                        throw new IOException(I18n.s(R.string.ui_43e0c6de9e24)+file);
                }
            }
            String id = manifest.getString("id"), dest = RootShell.BASE + "/roms/" + id;
            long total = 0; JSONObject files = manifest.getJSONObject("files");
            for (java.util.Iterator<String> i=files.keys();i.hasNext();) total+=files.getJSONObject(i.next()).getLong("size");
            final long neededKiB = total / 1024 + 262144;
            String manifestHash = PackageArchive.hash(new File(work,"rom.json"));
            progress.update(I18n.s(R.string.ui_33742d3f06d1));
            String script = "[ ! -L " + RootShell.quote(dest) + " ]\n"+
                "if [ -d " + RootShell.quote(dest) + " ]; then\n[ \"$(/data/rootvmos/host/bin/busybox sha256sum " + RootShell.quote(dest+"/rom.json") + " | cut -d ' ' -f1)\" = " + RootShell.quote(manifestHash) + " ] || { echo '同名 ROM 已存在，摘要不同，拒绝覆盖'; exit 4; }\nelse\n"+
                "FREE=$(/data/rootvmos/host/bin/busybox df -Pk /data | /data/rootvmos/host/bin/busybox awk 'NR==2 {print $4}')\n[ \"$FREE\" -ge " + neededKiB + " ] || { echo 'ROM 库空间不足'; exit 4; }\n"+
                copyScript(work,stage) + "\nfind . -type d -exec chmod 0755 {} \\;\nfind . -type f -exec chmod 0644 {} \\;\nchmod 0755 compat/iris_init compat/payload/netd_no_bpf\n"+
                "chcon -R u:object_r:system_file:s0 compat\n"+
                "if [ -d compat/google/rootfs ]; then\nfor D in compat/google/rootfs/system/product/lib compat/google/rootfs/system/product/lib64; do\n[ ! -d \"$D\" ] || chcon -R u:object_r:system_lib_file:s0 \"$D\"\ndone\nfi\n"+
                "sync\ncd /\nmv " + RootShell.quote(stage) + " " + RootShell.quote(dest) + "\nfi\n"+
                "printf '%s\\n' " + RootShell.quote(id) + " > /data/rootvmos/runtime/config/default_rom.tmp\n"+
                "mv /data/rootvmos/runtime/config/default_rom.tmp /data/rootvmos/runtime/config/default_rom\nsync\n";
            RootShell.run(script, 600); return I18n.s(R.string.ui_2ab6b84bab8e) + manifest.getString("name") + I18n.s(R.string.ui_90f4e75c6910);
        } finally { PackageArchive.erase(work); }
    }
    static String status() throws Exception {
        return RootShell.run("if [ -f /data/rootvmos/runtime/engine.version ]; then printf '引擎：'; cat /data/rootvmos/runtime/engine.version; else echo '引擎：未安装'; fi\n"+
            "if [ -f /data/rootvmos/runtime/config/default_rom ]; then printf '新实例 ROM：'; cat /data/rootvmos/runtime/config/default_rom; else echo 'ROM：尚未导入'; fi\n"+
            "for D in /data/rootvmos/runtime/roms/*; do [ ! -f \"$D/rom.json\" ] || printf '已安装 ROM：%s\\n' \"${D##*/}\"; done", 15);
    }
}
