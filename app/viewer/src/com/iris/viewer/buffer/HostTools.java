package com.irisbox.viewer;

import android.content.Context;
import java.io.*;
import java.util.UUID;

/** Verified app payload installed only in this project's root-owned host directory. */
final class HostTools {
    static final String BB="/data/irisbox/host/bin/busybox";
    static final String SHA="4d60ab3f5a59ebb2ca863f2f514e6924401b581e9b64f602665c008177626651";
    static synchronized void ensure(Context context) throws Exception {
        File file=new File(context.getFilesDir(),"host-busybox-"+UUID.randomUUID());
        String temporary=BB+"."+UUID.randomUUID();
        try {
            try(InputStream in=context.getAssets().open("host-tools/busybox");OutputStream out=new FileOutputStream(file)) {
                byte[] data=new byte[65536];int count;while((count=in.read(data))!=-1)out.write(data,0,count);
            }
            if(!SHA.equals(PackageArchive.hash(file)))throw new IOException("Host tools checksum mismatch");
            RootShell.run("[ \"$(id -u)\" = 0 ]\n"+
                "for NS in mnt pid net uts ipc cgroup; do [ \"$(readlink /proc/$$/ns/$NS)\" = \"$(readlink /proc/1/ns/$NS)\" ]; done\n"+
                "for D in /data/irisbox /data/irisbox/host /data/irisbox/host/bin; do\n"+
                "[ ! -L \"$D\" ]\nif [ -e \"$D\" ]; then [ -d \"$D\" ] && [ \"$(stat -c %u \"$D\")\" = 0 ]; else mkdir -m 0700 \"$D\"; fi\nchmod 0700 \"$D\"\ndone\n"+
                "[ ! -L "+BB+" ]\n"+
                "if [ -e "+BB+" ]; then\n[ -f "+BB+" ] && [ \"$(stat -c %u "+BB+")\" = 0 ]\n"+
                "[ \"$(sha256sum "+BB+" | cut -d ' ' -f 1)\" = "+SHA+" ]\nelse\n"+
                "trap 'rm -f "+temporary+"' EXIT\ncp "+RootShell.quote(file.getAbsolutePath())+" "+temporary+"\n"+
                "chown 0:0 "+temporary+"\nchmod 0755 "+temporary+"\n"+
                "[ \"$(sha256sum "+temporary+" | cut -d ' ' -f 1)\" = "+SHA+" ]\nmv "+temporary+" "+BB+"\nfi\n",30);
        } finally {file.delete();}
    }
}
