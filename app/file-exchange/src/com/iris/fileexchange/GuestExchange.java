package com.iris.fileexchange;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import com.irisbox.buffer.viewer.broker.ExchangeProtocol;
import com.irisbox.buffer.viewer.broker.ExchangeProtocol.Failure;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Guest 容器内一次性 MediaProvider 文件交换客户端：
 * 运行于 Shell UID (2000)，通过系统 MediaProvider 读写 Download/IrisExchange/。
 * 导入遵循 pending -> 逐块写入 -> SHA-256 核验 -> staged -> COMMIT -> committed 原子发布流；
 * 异常中断后可按唯一事务标识由 recover 子命令精确回收半成品。
 */
public final class GuestExchange {
    private static final String DOWNLOADS_DIR = ExchangeProtocol.DIRECTORY + "/";
    private static final Uri DOWNLOADS = Uri.parse("content://media/external_primary/downloads");
    private static final String[] PROJECTION = {
        "_id", "_display_name", "_size", "date_modified", "mime_type", "is_pending", "relative_path"
    };
    private static final long DEADLINE = deadline();

    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int b) {}
        @Override public void write(byte[] b, int off, int len) {}
    };

    private static long deadline() {
        String raw = System.getenv("IRIS_EXCHANGE_DEADLINE_MS");
        if (raw == null || !raw.matches("[0-9]{1,16}")) {
            return SystemClock.elapsedRealtime() + ExchangeProtocol.TRANSFER_SECONDS * 1000L;
        }
        return Long.parseLong(raw);
    }

    private static void checkDeadline() throws Failure {
        if (SystemClock.elapsedRealtime() >= DEADLINE) {
            throw new Failure(408, "TRANSFER_TIMEOUT", "文件操作超时，请重试");
        }
    }

    private static String temporary(String transaction) throws Failure {
        if (transaction == null || !transaction.matches("[0-9a-f]{32}")) {
            throw new Failure(400, "INVALID_TRANSACTION", "文件事务无效");
        }
        return "IrisPending_" + transaction + ".tmp";
    }

    private static String mimeType(String name) {
        return name.toLowerCase().endsWith(".apk")
            ? "application/vnd.android.package-archive"
            : "application/octet-stream";
    }

    private static final class Entry {
        final long id;
        final String name;
        final long bytes;
        final long modifiedMs;
        final String mimeType;
        final boolean pending;
        final String relativePath;

        Entry(long id, String name, long bytes, long modifiedMs, String mimeType, boolean pending, String relativePath) {
            this.id = id;
            this.name = name;
            this.bytes = bytes;
            this.modifiedMs = modifiedMs;
            this.mimeType = mimeType;
            this.pending = pending;
            this.relativePath = relativePath;
        }

        Uri uri() { return ContentUris.withAppendedId(DOWNLOADS, id); }

        JSONObject json() throws Exception {
            return new JSONObject()
                .put("name", name)
                .put("bytes", bytes)
                .put("modifiedMs", modifiedMs)
                .put("mimeType", mimeType);
        }
    }

    private static final class Provider implements AutoCloseable {
        private final IBinder token = new Binder();
        private final Class<?> managerType;
        private final Class<?> providerType;
        private final Class<?> attributionType;
        private final Class<?> signalType;
        private final Object activityManager;
        private final Object attribution;
        private final Object provider;

        Provider() throws Exception {
            managerType = Class.forName("android.app.IActivityManager");
            providerType = Class.forName("android.content.IContentProvider");
            attributionType = Class.forName("android.content.AttributionSource");
            signalType = Class.forName("android.os.ICancellationSignal");
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            Method getService = amClass.getDeclaredMethod("getService");
            activityManager = getService.invoke(null);
            Constructor<?> attrCtor = attributionType.getConstructor(int.class, String.class, String.class);
            attribution = attrCtor.newInstance(Process.myUid(), "com.android.shell", null);
            Method getExt = managerType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class);
            Object holder = getExt.invoke(activityManager, "media", 0, token, "iris-file-exchange");
            if (holder == null) throw new Failure(503, "STORAGE_NOT_READY", "实例文件服务不可用");
            Class<?> holderType = Class.forName("android.app.ContentProviderHolder");
            provider = holderType.getField("provider").get(holder);
            if (provider == null) throw new Failure(503, "STORAGE_NOT_READY", "实例共享存储尚未就绪");
        }

        private Object call(String name, Class<?>[] types, Object[] args) throws Exception {
            checkDeadline();
            try {
                Method m = providerType.getMethod(name, types);
                return m.invoke(provider, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw e;
            }
        }

        Cursor query(Uri uri, String selection, String[] selectionArgs, int limit) throws Exception {
            Bundle args = new Bundle();
            args.putString("android:query-arg-sql-selection", selection);
            args.putStringArray("android:query-arg-sql-selection-args", selectionArgs);
            args.putString("android:query-arg-sql-sort-order", "_id ASC");
            args.putInt("android:query-arg-limit", limit);
            args.putInt("android:query-arg-match-pending", 1);
            Cursor c = (Cursor) call("query",
                new Class<?>[]{attributionType, Uri.class, String[].class, Bundle.class, signalType},
                new Object[]{attribution, uri, PROJECTION, args, null});
            if (c == null) throw new Failure(503, "STORAGE_NOT_READY", "实例文件服务没有返回结果");
            return c;
        }

        Uri insert(ContentValues values) throws Exception {
            return (Uri) call("insert",
                new Class<?>[]{attributionType, Uri.class, ContentValues.class, Bundle.class},
                new Object[]{attribution, DOWNLOADS, values, null});
        }

        int update(Uri uri, ContentValues values) throws Exception {
            return (Integer) call("update",
                new Class<?>[]{attributionType, Uri.class, ContentValues.class, Bundle.class},
                new Object[]{attribution, uri, values, null});
        }

        int delete(Uri uri) throws Exception {
            return (Integer) call("delete",
                new Class<?>[]{attributionType, Uri.class, Bundle.class},
                new Object[]{attribution, uri, null});
        }

        ParcelFileDescriptor open(Uri uri, String mode) throws Exception {
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) call("openFile",
                new Class<?>[]{attributionType, Uri.class, String.class, signalType},
                new Object[]{attribution, uri, mode, null});
            if (pfd == null) throw new Failure(503, "STORAGE_NOT_READY", "实例文件无法打开");
            return pfd;
        }

        @Override
        public void close() {
            try {
                Method remove = managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
                remove.invoke(activityManager, "media", token, 0);
            } catch (Exception ignored) {}
        }
    }

    private static Entry fromCursor(Cursor c) {
        long id = c.getLong(0);
        String name = c.isNull(1) ? "" : c.getString(1);
        long size = c.isNull(2) ? -1L : c.getLong(2);
        long modifiedSec = c.isNull(3) ? 0L : c.getLong(3);
        String mime = c.isNull(4) ? "application/octet-stream" : c.getString(4);
        boolean pending = !c.isNull(5) && c.getInt(5) != 0;
        String rel = c.isNull(6) ? "" : c.getString(6);
        return new Entry(id, name, size, modifiedSec * 1000L, mime, pending, rel);
    }

    private static List<Entry> listDir(Provider provider) throws Exception {
        ArrayList<Entry> out = new ArrayList<>();
        try (Cursor c = provider.query(DOWNLOADS, "relative_path=? AND is_pending=0",
                new String[]{DOWNLOADS_DIR}, ExchangeProtocol.MAX_FILES + 1)) {
            while (c.moveToNext()) {
                checkDeadline();
                Entry e = fromCursor(c);
                if (!DOWNLOADS_DIR.equals(e.relativePath)) {
                    throw new Failure(503, "STORAGE_CHANGED", "共享存储查询范围不符");
                }
                if (out.size() >= ExchangeProtocol.MAX_FILES) {
                    throw new Failure(503, "LIST_TOO_LARGE", "交换目录超过 128 项，请先在实例内整理");
                }
                out.add(e);
            }
        }
        return out;
    }

    private static Entry byName(Provider provider, String name) throws Exception {
        Entry match = null;
        try (Cursor c = provider.query(DOWNLOADS, "relative_path=? AND _display_name=?",
                new String[]{DOWNLOADS_DIR, name}, 4)) {
            while (c.moveToNext()) {
                Entry e = fromCursor(c);
                if (!DOWNLOADS_DIR.equals(e.relativePath) || !name.equals(e.name)) continue;
                if (match != null) throw new Failure(409, "NAME_AMBIGUOUS", "存在同名记录，请先在实例内整理文件");
                match = e;
            }
        }
        return match;
    }

    private static String sha256AndVerifySeekable(ParcelFileDescriptor pfd, long expectedBytes) throws Exception {
        FileDescriptor fd = pfd.getFileDescriptor();
        try (FileInputStream fis = new FileInputStream(fd)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[ExchangeProtocol.BLOCK_BYTES];
            long total = 0;
            int n;
            while ((n = fis.read(buf)) != -1) {
                checkDeadline();
                md.update(buf, 0, n);
                total += n;
            }
            if (expectedBytes >= 0 && total != expectedBytes) {
                throw new Failure(503, "WRITE_MISMATCH", "实例文件大小校验失败");
            }
            fis.getChannel().position(0);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 255));
            return sb.toString();
        } catch (IOException e) {
            throw new Failure(503, "UNSUPPORTED_FILE", "该文件无法重新定位读取");
        }
    }

    private static void list(Provider provider) throws Exception {
        List<Entry> entries = listDir(provider);
        JSONArray files = new JSONArray();
        int skipped = 0;
        for (Entry e : entries) {
            if (e.pending || e.bytes < 0 || e.bytes > ExchangeProtocol.MAX_FILE_BYTES) {
                skipped++;
                continue;
            }
            files.put(e.json());
        }
        emit(new JSONObject().put("phase", "listed").put("files", files).put("skipped", skipped));
    }

    private static void exportFile(Provider provider, String rawName) throws Exception {
        String name = ExchangeProtocol.filename(rawName);
        Entry e = byName(provider, name);
        if (e == null || e.pending) {
            throw new Failure(404, "FILE_NOT_FOUND", "文件已不存在或尚未发布，请刷新列表");
        }
        if (e.bytes < 0 || e.bytes > ExchangeProtocol.MAX_FILE_BYTES) {
            throw new Failure(413, "FILE_TOO_LARGE", "单个文件不能超过 4 GiB");
        }
        try (ParcelFileDescriptor pfd = provider.open(e.uri(), "r")) {
            long statSize = pfd.getStatSize();
            if (statSize < 0 || statSize != e.bytes) {
                throw new Failure(409, "UNSUPPORTED_FILE", "该文件不支持可靠的定长导出");
            }
            String digest = sha256AndVerifySeekable(pfd, e.bytes);
            emit(new JSONObject().put("phase", "export").put("name", e.name).put("bytes", e.bytes).put("sha256", digest));
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                fis.getChannel().position(0);
                ExchangeProtocol.Copied copied = ExchangeProtocol.copyExactly(fis, System.out, e.bytes, GuestExchange::checkDeadline);
                System.out.flush();
                if (!copied.sha256.equals(digest)) {
                    throw new Failure(409, "FILE_CHANGED", "导出过程中实例文件发生变化");
                }
            }
        }
    }

    private static void recover(Provider provider, String tx) throws Exception {
        String tempName = temporary(tx);
        Entry e = byName(provider, tempName);
        boolean deleted = false;
        if (e != null) {
            if (!e.pending) throw new Failure(409, "RECOVERY_CONFLICT", "旧事务文件已发布，不自动删除");
            int rows = provider.delete(e.uri());
            if (rows != 1) throw new Failure(503, "RECOVERY_FAILED", "Pending delete affected " + rows + " rows");
            if (byName(provider, tempName) != null) {
                throw new Failure(503, "RECOVERY_FAILED", "Pending URI still exists after delete");
            }
            deleted = true;
        }
        emit(new JSONObject().put("phase", "recovered").put("deleted", deleted));
    }

    private static void importFile(Provider provider, String tx, String rawName, long expectedBytes) throws Exception {
        String tempName = temporary(tx);
        String targetName = ExchangeProtocol.filename(rawName);
        if (expectedBytes < 0 || expectedBytes > ExchangeProtocol.MAX_FILE_BYTES) {
            throw new Failure(413, "FILE_TOO_LARGE", "单个文件不能超过 4 GiB");
        }
        if (byName(provider, tempName) != null) throw new Failure(503, "RECOVERY_REQUIRED", "上次事务尚未清理");
        if (byName(provider, targetName) != null) throw new Failure(409, "FILE_EXISTS", "实例中已有同名文件，请更换名称");
        if (listDir(provider).size() >= ExchangeProtocol.MAX_FILES) {
            throw new Failure(503, "LIST_TOO_LARGE", "交换目录文件过多，请先在实例内整理");
        }

        ContentValues cv = new ContentValues();
        cv.put("_display_name", tempName);
        cv.put("mime_type", "application/octet-stream");
        cv.put("relative_path", DOWNLOADS_DIR);
        cv.put("is_pending", 1);
        Uri pendingUri = provider.insert(cv);
        if (pendingUri == null || !pendingUri.toString().matches("content://media/external_primary/downloads/[0-9]+")) {
            throw new Failure(503, "STORAGE_NOT_READY", "实例未能创建待写入文件");
        }

        boolean committed = false;
        try {
            emit(new JSONObject().put("phase", "pending").put("uri", pendingUri.toString()));
            ExchangeProtocol.Copied copied;
            try (ParcelFileDescriptor pfd = provider.open(pendingUri, "w");
                 FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
                copied = ExchangeProtocol.copyExactly(System.in, fos, expectedBytes, GuestExchange::checkDeadline);
                fos.getFD().sync();
            }
            String verifiedSha;
            try (ParcelFileDescriptor rfd = provider.open(pendingUri, "r")) {
                verifiedSha = sha256AndVerifySeekable(rfd, expectedBytes);
            }
            if (!copied.sha256.equals(verifiedSha)) {
                throw new Failure(503, "WRITE_MISMATCH", "实例文件内容校验失败");
            }
            emit(new JSONObject().put("phase", "staged").put("bytes", copied.bytes).put("sha256", verifiedSha));

            ByteArrayOutputStream cmd = new ByteArrayOutputStream();
            int ch;
            while ((ch = System.in.read()) != -1 && ch != '\n') {
                checkDeadline();
                if (cmd.size() < 64) cmd.write(ch);
            }
            if (ch < 0) throw new Failure(409, "TRANSFER_CANCELLED", "文件导入已取消");
            String line = new String(cmd.toByteArray(), StandardCharsets.US_ASCII);
            if (!"COMMIT".equals(line)) throw new Failure(400, "INVALID_COMMIT", "文件提交指令不正确");
            if (byName(provider, targetName) != null) {
                throw new Failure(409, "FILE_EXISTS", "实例中已出现同名文件，请更换名称");
            }

            ContentValues publish = new ContentValues();
            publish.put("_display_name", targetName);
            publish.put("mime_type", mimeType(targetName));
            publish.put("is_pending", 0);
            int updated = provider.update(pendingUri, publish);
            if (updated != 1) throw new Failure(503, "PUBLISH_FAILED", "文件未能发布到实例下载目录");
            committed = true;

            Entry finalEntry = byName(provider, targetName);
            if (finalEntry == null || finalEntry.pending || finalEntry.bytes != expectedBytes) {
                throw new Failure(503, "PUBLISH_UNCONFIRMED", "文件发布结果未确认，请刷新实例文件列表");
            }
            emit(new JSONObject()
                .put("phase", "committed")
                .put("name", finalEntry.name)
                .put("bytes", finalEntry.bytes)
                .put("sha256", verifiedSha));
        } finally {
            if (!committed) {
                try {
                    provider.delete(pendingUri);
                } catch (Exception e) {
                    System.err.println("Pending cleanup unconfirmed: " + e.getMessage());
                }
            }
        }
    }

    private static void emit(JSONObject obj) {
        System.out.print(obj.toString() + "\n");
        System.out.flush();
    }

    public static void main(String[] args) {
        if (Process.myUid() != 2000) {
            emitError(403, "ACCESS_DENIED", "仅允许实例管理器通过 Shell 身份调用");
            System.exit(1);
            return;
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "exchange-deadline");
            t.setDaemon(true);
            return t;
        });
        long delay = Math.max(0L, DEADLINE - SystemClock.elapsedRealtime());
        watchdog.schedule(() -> System.exit(2), delay + 5000L, TimeUnit.MILLISECONDS);
        try (Provider provider = new Provider()) {
            if (args.length == 1 && "list".equals(args[0])) {
                list(provider);
            } else if (args.length == 2 && "export".equals(args[0])) {
                exportFile(provider, args[1]);
            } else if (args.length == 2 && "recover".equals(args[0])) {
                recover(provider, args[1]);
            } else if (args.length == 4 && "import".equals(args[0])) {
                importFile(provider, args[1], args[2], Long.parseLong(args[3]));
            } else {
                throw new Failure(400, "INVALID_OPERATION", "文件操作参数不正确");
            }
        } catch (Failure f) {
            emitError(f.status, f.code, f.getMessage());
            System.exit(1);
        } catch (Throwable t) {
            System.err.println("GuestExchange: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            emitError(500, "STORAGE_ERROR", "实例文件服务操作失败，请重试或查看日志");
            System.exit(1);
        } finally {
            watchdog.shutdownNow();
        }
    }

    private static void emitError(int status, String code, String message) {
        try {
            emit(new JSONObject()
                .put("phase", "error")
                .put("status", status)
                .put("code", code)
                .put("error", message == null ? "" : message));
        } catch (Exception ignored) {}
    }
}
