package com.irisbox.viewer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 宿主 Root 入口：负责在宿主 Android App 内部通过 Magisk 提权拉起后台管理服务。
 * 
 * 【核心设计与安全规范】：
 * 1. 最小特权与只读设计：仅用于执行既有的 manager.sh start 与 open 命令，绝不越权直接启动或关闭 Guest。
 * 2. 挂载命名空间穿透（--mount-master）：Magisk 默认给 App 授予 Root 时会创建隔离的 mount 空间，
 *    此处必须显式使用 --mount-master 借道进入系统主挂载命名空间，才能满足 manager.sh 对宿主 6 个 Namespace 的一致性校验。
 * 3. 防命令注入：执行命令为硬编码固定常量，禁止动态拼接实例编号、Intent 参数或任何外界输入。
 * 4. 敏感信息防泄露：不记录 Root 密码、授权 Token 或详细输出日志；后台并发通过原有 manager.lock 串行化。
 */
final class HostRootEntry {
    /** 硬编码的启动与唤起命令：先 start 启动后台 Broker，再 open 唤起前台查看器 Activity */
    private static final String COMMAND =
        "test ! -e /data/irisbox/engine-upgrade.pending && " +
        "/system/bin/sh /data/irisbox/runtime/display/buffer_viewer/manager.sh start && " +
        "/system/bin/sh /data/irisbox/runtime/display/buffer_viewer/manager.sh open";

    /** 宿主系统常见 Magisk su 二进制文件候选路径（兼容不同 Android 固件及 root 安装方案） */
    private static final String[] CANDIDATES = {
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su"
    };

    /**
     * 连接并启动后台管理服务
     * 
     * @return 错误提示信息；若连接成功则返回空字符串 ""
     */
    static String connect(android.content.Context context) {
        try {
            HostTools.ensure(context);
            String su = null;
            // 1. 遍历候选路径，校验是否存在正品 Magisk su
            for (String path : CANDIDATES) {
                try {
                    // 执行 su -v 检查版本信息，限定 3 秒超时
                    Result version = execute(new String[]{path, "-v"}, 3);
                    if (version.code == 0 && version.output.contains("MAGISKSU")) {
                        su = path;
                        break;
                    }
                } catch (java.io.IOException unavailable) {
                    // 若当前路径不存在或不可执行，直接跳过并检查下一个候选
                }
            }
            if (su == null) {
                return I18n.s(R.string.ui_f135d8912da5);
            }

            // 2. 使用 --mount-master 模式提权执行管理命令：
            //    --mount-master 是关键：让子进程直接进入宿主系统主挂载空间（PID 1 所在空间），
            //    确保满足 manager.sh 脚本内 host_only() 对 6 大 Namespace 与 Root 权限的双重核验。
            Result result = execute(new String[]{su, "--mount-master", "-c", COMMAND}, 90);
            if (result.code == 0) return "";
            if (result.code == -1) return I18n.s(R.string.ui_b8f44104e192);
            if (result.output.toLowerCase(java.util.Locale.ROOT).contains("permission denied"))
                return I18n.s(R.string.ui_3950e1128b8e);

            return I18n.s(R.string.ui_f04e11a0ff14);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return I18n.s(R.string.ui_7d536627a3c1);
        } catch (Exception failed) {
            return I18n.s(R.string.ui_a6112556325f);
        }
    }

    /**
     * 进程安全执行封装：
     * 1. 净化环境变量（清理 LD_PRELOAD、CLASSPATH，防动态库劫持）；
     * 2. 异步排空标准输出与错误流，防止 Linux 管道缓冲区（默认 64KB）填满导致 su 挂起死锁；
     * 3. 内存截断防护：限制最多读取 16KB 诊断信息，不泄露过多堆栈和敏感凭据。
     */
    private static Result execute(String[] args, int seconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        // 净化执行环境：固定系统 PATH，清理可能被注入的类路径或预加载库
        builder.environment().put("PATH", "/system/bin:/system/xbin");
        builder.environment().remove("CLASSPATH");
        builder.environment().remove("LD_PRELOAD");

        Process process = builder.start();
        // 明确关闭标准输入 stdin，告知子进程无交互输入，避免交互式 su 盲等
        process.getOutputStream().close();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // 启动后台守护读取线程，实时抽干进程输出
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[2048];
                int n;
                while ((n = input.read(bytes)) != -1) {
                    // 仅保留最多 16KB 输出内容，多余的字节丢弃，防 OOM 内存爆满
                    int retain = Math.min(n, 16384 - output.size());
                    if (retain > 0) output.write(bytes, 0, retain);
                }
            } catch (Exception closed) {
                // 超时或进程销毁导致管道关闭时正常退出读取循环
            }
        }, "iris-root-output");
        reader.setDaemon(true);
        reader.start();

        try {
            // 等待进程在指定秒数内执行完毕
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                return new Result(-1, ""); // 超时返回特定码 -1
            }
            reader.join(1000); // 宽限 1 秒让输出流收集完毕
            return new Result(process.exitValue(), new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            // 确保进程生命周期彻底回收，防止遗留僵尸进程
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (Exception ignored) {}
        }
    }

    /** 命令执行结果封装对象（退出码与标准输出截取内容） */
    private static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }
}
