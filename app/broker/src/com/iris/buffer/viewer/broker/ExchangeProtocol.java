package com.irisbox.buffer.viewer.broker;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Host与Guest共用的纯Java边界：固定文件名、大小、HTTP正文和流复制规则。
 * filename只接受名称，不接受目录/任意URI；DIRECTORY固定为Download/IrisExchange。
 * Failure同时带HTTP状态、机器码和用户文案，调用侧不要只靠字符串猜错误类别。
 * Small, Android-independent validation and streaming rules for the exchange API.
 */
public final class ExchangeProtocol {
    public static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    public static final int MAX_FILES = 128;
    public static final int MAX_NAME_UTF8_BYTES = 128;
    public static final int BLOCK_BYTES = 64 * 1024;
    public static final int TRANSFER_SECONDS = 120;
    public static final int IDLE_SECONDS = 15;
    public static final String DIRECTORY = "Download/IrisExchange";

    public static final class Failure extends IOException {
        public final int status;
        public final String code;
        public Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public interface Guard { void check() throws IOException; }

    /** One HTTP request per connection. Never accept ambiguous body framing. */
    public static final class BodyHeaders {
        public String length, type;
        public void accept(String key, String value) throws Failure {
            if (key.equalsIgnoreCase("Transfer-Encoding"))
                throw new Failure(400, "UNSUPPORTED_ENCODING", "本轮只接受已知大小的文件，不支持分块请求");
            if (key.equalsIgnoreCase("Content-Length")) {
                if (length != null) throw new Failure(400, "DUPLICATE_LENGTH", "请求包含重复的文件大小");
                length = value;
            }
            if (key.equalsIgnoreCase("Content-Type")) {
                if (type != null) throw new Failure(400, "DUPLICATE_CONTENT_TYPE", "请求包含重复的数据类型");
                type = value;
            }
        }
        public void requireEmpty() throws Failure {
            if (length != null && contentLength(length) != 0)
                throw new Failure(400, "BODY_NOT_ALLOWED", "此操作不接受请求正文");
        }
    }

    public static final class Copied {
        public final long bytes;
        public final String sha256;
        Copied(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    public static long contentLength(String value) throws Failure {
        if (value == null) throw new Failure(411, "LENGTH_REQUIRED", "需要准确的文件大小，请重新选择文件");
        if (!value.matches("[0-9]{1,20}"))
            throw new Failure(400, "INVALID_LENGTH", "文件大小格式不正确");
        long size;
        try { size = Long.parseLong(value); }
        catch (NumberFormatException error) { throw new Failure(413, "FILE_TOO_LARGE", "单个文件不能超过 128 MiB"); }
        if (size > MAX_FILE_BYTES) throw new Failure(413, "FILE_TOO_LARGE", "单个文件不能超过 128 MiB");
        return size;
    }

    public static String filename(String name) throws Failure {
        if (name == null || name.isEmpty() || name.startsWith(".") || name.endsWith(".") ||
                name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_UTF8_BYTES ||
                Character.isWhitespace(name.codePointAt(0)) ||
                Character.isWhitespace(name.codePointBefore(name.length()))) {
            throw new Failure(400, "INVALID_NAME", "文件名为空、过长，或以点及空白开头/结尾");
        }
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset);
            if (cp == '/' || cp == '\\' || Character.isISOControl(cp) ||
                    Character.getType(cp) == Character.FORMAT ||
                    (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0xFFFD) {
                throw new Failure(400, "INVALID_NAME", "文件名不能包含路径、控制字符或无效字符");
            }
            offset += Character.charCount(cp);
        }
        return name;
    }

    /** Never reads more than the declared size and never allocates by file size. */
    /** 按已声明大小逐块复制并算SHA256；每块检查会话/期限，不按整文件大小分配内存。
     * 只读取expected字节，不吞掉后续COMMIT控制消息；短读EOF表示失败。
     */
    public static Copied copyExactly(InputStream input, OutputStream output, long expected, Guard guard)
            throws IOException {
        if (expected < 0) throw new Failure(400, "INVALID_LENGTH", "文件大小不正确");
        if (expected > MAX_FILE_BYTES) throw new Failure(413, "FILE_TOO_LARGE", "单个文件不能超过 128 MiB");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
        byte[] block = new byte[BLOCK_BYTES];
        long copied = 0;
        guard.check();
        while (copied < expected) {
            guard.check();
            int wanted = (int) Math.min(block.length, expected - copied);
            int count = input.read(block, 0, wanted);
            if (count < 0) throw new Failure(400, "INCOMPLETE_BODY", "文件传输已中断，未完成的导入不会发布");
            if (count == 0) throw new EOFException("Input stream made no progress");
            if (count > wanted) throw new IOException("Input stream exceeded requested block");
            guard.check();
            output.write(block, 0, count);
            digest.update(block, 0, count);
            copied += count;
        }
        guard.check();
        return new Copied(copied, hex(digest.digest()));
    }

    private static String hex(byte[] bytes) {
        final char[] symbols = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = symbols[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = symbols[bytes[i] & 15];
        }
        return new String(result);
    }
}
