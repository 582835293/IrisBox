package com.rootvmos.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** The real transfer loop, kept free of Android so its byte contract is testable. */
final class TransferIO {
    static final long MAX_BYTES = 134217728L;
    interface Guard { void check() throws IOException; }
    interface Progress { void bytes(long copied, long total); }

    static void copyExact(InputStream input, OutputStream output, long total,
                          MessageDigest digest, Guard guard, Progress progress) throws IOException {
        if (total < 0 || total > MAX_BYTES) throw new IOException("文件大小超过允许范围");
        byte[] buffer = new byte[65536];
        long copied = 0;
        while (copied < total) {
            guard.check();
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, total - copied));
            if (count < 0) throw new IOException("文件提前结束");
            if (count == 0) continue;
            guard.check();
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            copied += count;
            progress.bytes(copied, total);
        }
        guard.check();
        if (input.read() != -1) throw new IOException("文件大小与声明不符");
        guard.check();
        progress.bytes(copied, total);
    }
    private TransferIO() {}
}
