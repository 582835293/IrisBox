#!/system/bin/sh
# ==============================================================================
# Iris 嵌套安卓虚拟机运行时 - 容器内部点火内室（PID 1 Shell）
#
# 【阅读入口说明】
# 当前进程已经由 wrapper.sh 通过 unshare 进入了 Guest 专属的六个 Linux Namespace
# （mnt, pid, net, ipc, uts, cgroup）。
# 此时当前 Shell 是该命名空间视图下的【PID 1】。虽然处于新的挂载空间，但继承了父级
# 的挂载拓扑，因此在此刻【仍能看到宿主机（Host）的旧根文件系统】。
# 
# 【执行序列与阅读顺序】
# 1. Namespace 隔离性深度核验与挂载树私有化；
# 2. ROM 镜像与 OverlayFS 读写层挂载（含可选 Google 服务框架层）；
# 3. 核心专用载荷注入（Gralloc dma-buf ID 共享库、Display 预览捕获、网络、文件交换）；
# 4. RC 启动脚本白名单精确分发与批量权限标记；
# 5. fusectl 内核隔离与系统启动增强脚本植入；
# 6. Magisk 提权套件与 no_bpf 兼容网络栈替换；
# 7. 私有虚拟设备树构建（binderfs、devpts、sysfs、cgroup v1/v2 子树、SELinux 限制）；
# 8. 与 Host 握手完成网络布线，绑定专有 BPF UID 网络权限映射表；
# 9. pivot_root 切换根目录、卸载并彻底销毁旧根，挂载纯净 procfs；
# 10. 通过 procfs 跃迁至 u:r:init:s0，exec 启动原厂 init 的 second_stage！
#
# 【架构边界与容错】
# 这里绝不启动 Linux 内核，也不执行手机物理分区冷引导的 init 第一阶段（first_stage）。
# 任何步骤失败均由 Host 侧的 watchdog 守护进程统一回收，绝不在本脚本内部拼凑第二套服务清理清单。
# 遵循原则：私有 mount/PID/IPC/Net/UTS/cgroup 环境就绪后，严格只执行一次 init exec 替换。
# ==============================================================================

# 启用严格错误检查：遇到未声明变量或命令失败立即退出
set -eu

# 引入基础运行库环境（根据实例 ID 加载环境变量、配置路径与辅助函数）
. /data/rootvmos/runtime/common.sh "$1"

# Guest 容器切根目标挂载点
ROOT=$RUN/rootfs

# 【启动阶段打点函数】
# 利用内核单调时间 /proc/uptime 精准记录每个准备环节的耗时，区分环境准备耗时与 Android 自身启动耗时
boot_step(){ print -r -- "[启动阶段] uptime=$($BB awk '{print $1}' /proc/uptime) $1"; }

boot_step "准备私有挂载"

# 【增量载荷覆盖函数】
# 仅当目标文件不存在或文件字节内容存在差异时才触发物理覆盖；
# 避免重复写入导致 Android 运行时的 PackageManager 包解析缓存或 dex2oat 编译缓存失效
copy_payload(){ "$BB" cmp -s "$1" "$2" || "$BB" cp "$1" "$2"; }

# ------------------------------------------------------------------------------
# 1. 命名空间六维物理隔离校验与基础网络准备
# ------------------------------------------------------------------------------
# 严格比对当前进程与 Host PID 1 的六大 Namespace ID；若任何一个空间未实现隔离，拒绝点火
for N in pid mnt net ipc uts cgroup; do
    [ "$($BB readlink /proc/self/ns/$N)" != "$($BB readlink /proc/1/ns/$N)" ] || exit 3
done

# 将当前挂载树标记为递归私有（Private Mount），阻断后续所有挂载事件向宿主机或兄弟实例传播
$BB mount --make-rprivate /

# 设置实例专用的 UTS 主机名
$BB hostname iris-init-$ID

# 拉起本网络命名空间内的本地回环接口（lo）
$BB ifconfig lo up

# 创建 OverlayFS 运行时目录结构与独立数据持久化目录
$BB mkdir -p "$RUN/upper" "$RUN/work" "$ROOT" \
    "$RUN/vendor.upper" "$RUN/vendor.work" "$RUN/data" "$RUN/metadata"

# ------------------------------------------------------------------------------
# 2. ROM 镜像与 OverlayFS 根文件系统挂载
# ------------------------------------------------------------------------------
# lowerdir 保持只读供多实例共享；upperdir、workdir 与 data 分区相互独立隔离；
# 若启用了 Google 服务支持，动态叠加只读程序层，但绝不共享账号与用户数据
SYSTEM_LOWER=$ROM/system.lower

# 校验并可选挂载 Google 核心程序层
if [ -e "$RUN/google.core.enabled" ] || [ -L "$RUN/google.core.enabled" ]; then
    # 软链接防护：必须是常规文件，且内容与官方组件 ID 一致
    [ -f "$RUN/google.core.enabled" ] && [ ! -L "$RUN/google.core.enabled" ] || exit 3
    [ "$(cat "$RUN/google.core.enabled")" = "$(cat "$COMPAT/google/component.id")" ] || exit 3
    boot_step "开始校验Google程序层"
    /system/bin/sh "$BASE/google/a13_arm64/guest_layer.sh" verify "$ID"
    boot_step "Google程序层校验完成"
    # 将 Google 根文件系统前置插入到 system 镜像的只读层列表中
    SYSTEM_LOWER=$COMPAT/google/rootfs:$SYSTEM_LOWER
fi

# 挂载 system 分区的 OverlayFS 根视图
$BB mount -t overlay overlay -o "lowerdir=$SYSTEM_LOWER,upperdir=$RUN/upper,workdir=$RUN/work" "$ROOT"

# 挂载 vendor 分区的 OverlayFS 驱动视图
$BB mount -t overlay overlay -o "lowerdir=$ROM/vendor.lower,upperdir=$RUN/vendor.upper,workdir=$RUN/vendor.work" "$ROOT/vendor"

# ------------------------------------------------------------------------------
# 3. 核心专用载荷注入（图形、Init 与系统工具）
# ------------------------------------------------------------------------------
# 【容器专用 Gralloc 渲染缓冲标识】：
# Guest 专用的 gralloc ID 直接来源于 Linux 内核全局物理唯一的活动 dma-buf inode 节点。
# 宿主机的 vendor 分区保持官方原厂不变，只在此私有 OverlayFS 写层中替换。
[ -f "$COMPAT/payload/libminigbm_gralloc_gbm_mesa_guest_ids.so" ] || exit 3
copy_payload "$COMPAT/payload/libminigbm_gralloc_gbm_mesa_guest_ids.so" "$ROOT/vendor/lib64/libminigbm_gralloc_gbm_mesa.so"
$BB chmod 0644 "$ROOT/vendor/lib64/libminigbm_gralloc_gbm_mesa.so"
/system/bin/chcon u:object_r:vendor_file:s0 "$ROOT/vendor/lib64/libminigbm_gralloc_gbm_mesa.so"

# 应用针对当前实例分配的 GPU 渲染配置
/system/bin/sh "$BASE/graphics.sh" apply "$ID"
. "$RUN/graphics.env"

# Guest uses the ROM's virtual primary HAL; physical ALSA devices stay on Host.
# A private playback-only remote submix is forwarded to Host AudioTrack.
AUDIO_PROP=$ROOT/vendor/build.prop
[ ! -L "$AUDIO_PROP" ] && [ "$($BB grep -c '^ro.hardware.audio.primary=' "$AUDIO_PROP")" = 1 ] || exit 3
$BB sed -i 's/^ro.hardware.audio.primary=.*/ro.hardware.audio.primary=default/' "$AUDIO_PROP"
/system/bin/chcon u:object_r:vendor_file:s0 "$AUDIO_PROP"
# The Guest has no /dev/video*. Do not advertise the Host-only V4L2 codec.
CODECS=$ROOT/vendor/etc/media_codecs.xml
copy_payload "$ROM/vendor.lower/etc/media_codecs.xml" "$CODECS"
[ ! -L "$CODECS" ] && [ "$($BB grep -c 'href="media_codecs_v4l2_c2_video.xml"' "$CODECS")" = 1 ] || exit 3
$BB sed -i '/href="media_codecs_v4l2_c2_video.xml"/d' "$CODECS"
$BB chmod 0644 "$CODECS"
/system/bin/chcon u:object_r:vendor_configs_file:s0 "$CODECS"

# Traceur depends on global kernel tracing intentionally unavailable in Guest.
# Mask only this fixed ROM system-app directory in the already-private mount tree.
TRACEUR=$ROOT/system/app/Traceur
[ -d "$TRACEUR" ] && [ ! -L "$TRACEUR" ] && [ "$($BB realpath "$TRACEUR")" = "$TRACEUR" ] || exit 3
$BB mount -t tmpfs -o mode=0755,nodev,nosuid,size=64k rootvmos_traceur "$TRACEUR"
/system/bin/chcon u:object_r:system_file:s0 "$TRACEUR"

copy_payload "$BASE/audio/audio.jar" "$ROOT/system/framework/rootvmos-audio.jar"
# AOSP-only ROMs do not inherit the optional Google product directories.
for D in "$ROOT/system/product" "$ROOT/system/product/etc" "$ROOT/system/product/etc/init"; do
    [ ! -L "$D" ] || exit 3
    $BB mkdir -p "$D"
done
copy_payload "$BASE/audio/media.rc" "$ROOT/system/product/etc/init/zzz_rootvmos_media.rc"
$BB chmod 0644 "$ROOT/system/product/etc/init/zzz_rootvmos_media.rc"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/product/etc/init/zzz_rootvmos_media.rc"
copy_payload "$BASE/audio/health.sh" "$ROOT/system/etc/rootvmos-health.sh"
$BB chmod 0644 "$ROOT/system/framework/rootvmos-audio.jar"
$BB chmod 0644 "$ROOT/system/etc/rootvmos-health.sh"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/framework/rootvmos-audio.jar"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/etc/rootvmos-health.sh"

# 植入容器专用的 init 二进制跳板，赋予 init_exec 标签
copy_payload "$COMPAT/iris_init" "$ROOT/system/bin/init"
/system/bin/chcon u:object_r:init_exec:s0 "$ROOT/system/bin/init"
$BB ln -sf init "$ROOT/system/bin/iris_init"

# 植入宿主机独立的 BusyBox 工具，供 Guest 内部运维与网络脚本调用
copy_payload "$BB" "$ROOT/system/bin/busybox"
$BB chmod 0755 "$ROOT/system/bin/busybox"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/bin/busybox"

boot_step "开始准备显示和服务配置"

# 【低频小窗预览截图工具注入】：
# 单次触发的 640×360 列表缩略图采集器（供 BufferBroker 抓取小窗画面）；
# 交互式全屏图形渲染仍通过 Gralloc 共享物理显存缓冲区进行。
copy_payload "$BASE/display/buffer_bridge/iris_buffer_producer" "$ROOT/system/bin/iris_preview_capture"
$BB chmod 0755 "$ROOT/system/bin/iris_preview_capture"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/bin/iris_preview_capture"

# 【网络初始化脚本注入】：
# 将网络配置集中在一个统一维护的文件中；切换到 Guest 根目录后无需再依赖 Host 旧根目录路径。
copy_payload "$BASE/network.sh" "$ROOT/system/bin/iris_network.sh"
$BB chmod 0755 "$ROOT/system/bin/iris_network.sh"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/bin/iris_network.sh"

# 【单次文件交换客户端注入】：
# 标准 MediaProvider 客户端（遵循 pending -> write -> publish 流程导入相册/下载，无需在 Guest 内常驻后台守护进程）。
[ -f "$BASE/file_exchange/guest.jar" ] || exit 3
copy_payload "$BASE/file_exchange/guest.jar" "$ROOT/system/framework/iris_file_exchange.jar"
$BB chmod 0644 "$ROOT/system/framework/iris_file_exchange.jar"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/framework/iris_file_exchange.jar"

# 【原生系统文件管理器平板布局补丁】：
# 仅复制到各 Guest 的私有 OverlayFS 中，修复 DocumentsUI 在平板/大屏分辨率下的布局异常。
[ -f "$COMPAT/payload/DocumentsUI.apk" ] && [ -f "$ROOT/system/priv-app/DocumentsUI/DocumentsUI.apk" ] || exit 3
copy_payload "$COMPAT/payload/DocumentsUI.apk" "$ROOT/system/priv-app/DocumentsUI/DocumentsUI.apk"
$BB chmod 0644 "$ROOT/system/priv-app/DocumentsUI/DocumentsUI.apk"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/priv-app/DocumentsUI/DocumentsUI.apk"

# ------------------------------------------------------------------------------
# 4. RC 启动脚本白名单精确分发与批量权限标记
# ------------------------------------------------------------------------------
# 【白名单 RC 启动脚本精准分发】：
# 将经过白名单筛选的有限候选 RC 脚本写入私有 OverlayFS；
# 清空扫描目录下的陈旧备份，因为原厂 init 会无差别解析目录下的所有常规文件。
for D in system/etc/init vendor/etc/init; do
    $BB rm -rf "$ROOT/$D"
    $BB mkdir -p "$ROOT/$D"
done

# Android /system/bin/sh 是 mksh；使用数组保留路径边界，避免拼接字符串在包含空格时展开错误
set -A SYSTEM_RC
set -A VENDOR_RC
while IFS= read -r FILE; do
    case "$FILE" in /apex/*) continue ;; esac
    SRC=$COMPAT/rc_candidate$FILE
    [ -f "$SRC" ] || exit 3
    DEST=$FILE
    # 【解决 /product 绝对软链接解析问题】：
    # ROM 中的 /product 绝对符号链接在 pivot_root 切根之前绝不能解析到宿主机路径，
    # 因此将其物理重定向至 /system/product/...
    case "$FILE" in /product/*) DEST=/system/product/${FILE#/product/} ;; esac
    $BB mkdir -p "${ROOT}${DEST%/*}"
    $BB cp "$SRC" "$ROOT$DEST"
    case "$FILE" in
        /vendor/*) VENDOR_RC[${#VENDOR_RC[@]}]="$ROOT$DEST" ;;
        *) SYSTEM_RC[${#SYSTEM_RC[@]}]="$ROOT$DEST" ;;
    esac
done < "$COMPAT/loaded_files.txt"

# 性能优化：原来对每个文件单独执行 chmod 与 chcon 会引发几百次进程 fork；
# 改为按类别合并数组一次性批量赋予权限与 SELinux 标签，效果相同但大幅缩短启动时间
if [ "${#SYSTEM_RC[@]}" -gt 0 ]; then
    $BB chmod 0644 "${SYSTEM_RC[@]}"
    /system/bin/chcon u:object_r:system_file:s0 "${SYSTEM_RC[@]}"
fi
if [ "${#VENDOR_RC[@]}" -gt 0 ]; then
    $BB chmod 0644 "${VENDOR_RC[@]}"
    /system/bin/chcon u:object_r:vendor_configs_file:s0 "${VENDOR_RC[@]}"
fi

# ------------------------------------------------------------------------------
# 5. fusectl 内核隔离与系统启动增强脚本植入
# ------------------------------------------------------------------------------
# 【防范 fusectl 全局内核控制树污染与崩溃】：
# fusectl 是全局单例的内核控制树；若允许 Guest 的存储守护进程（vold）重置 fusectl，
# 将导致宿主机或其他 Guest 的 FUSE 存储连接全部被强制中断！
# 因此必须从原厂 init.rc 中剔除该挂载指令，同时完整保留 Guest 的 /dev/fuse 设备与原厂存储挂载流程。
# 后续挂载的纯净只读 sysfs 会保持该目录为空，阻断对全局 FUSE 的控制。
GUEST_INIT_RC=$ROOT/system/etc/init/hw/init.rc
FUSE_MOUNT_LINE='    mount fusectl none /sys/fs/fuse/connections'
FUSE_MOUNT_COUNT=$($BB grep -Fxc "$FUSE_MOUNT_LINE" "$GUEST_INIT_RC" || true)
[ "$FUSE_MOUNT_COUNT" = 1 ] || {
    echo 'Guest fusectl guard: expected exactly one stock mount line' >&2
    exit 3
}
$BB sed -i '/^    mount fusectl none \/sys\/fs\/fuse\/connections$/d' "$GUEST_INIT_RC"
/system/bin/chcon u:object_r:system_file:s0 "$GUEST_INIT_RC"

# 植入 Iris 专属的系统级与供应商级启动脚本（启动专用守卫与服务配置）
copy_payload "$COMPAT/guest.rc" "$ROOT/system/etc/init/zz_iris_guest.rc"
$BB chmod 0644 "$ROOT/system/etc/init/zz_iris_guest.rc"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/etc/init/zz_iris_guest.rc"
copy_payload "$COMPAT/guest_vendor.rc" "$ROOT/vendor/etc/init/zz_iris_guest_vendor.rc"
$BB chmod 0644 "$ROOT/vendor/etc/init/zz_iris_guest_vendor.rc"
/system/bin/chcon u:object_r:vendor_configs_file:s0 "$ROOT/vendor/etc/init/zz_iris_guest_vendor.rc"

boot_step "服务配置准备完成"
# ------------------------------------------------------------------------------
# 6. Magisk 提权套件与 no_bpf 兼容网络栈替换
# ------------------------------------------------------------------------------
# 【可选 Magisk 提权套件注入】：
# 调用扩展脚本在此私有 OverlayFS 写层中准备 Magisk 运行环境（若用户在配置中启用了 Root 功能）
/system/bin/sh "$BASE/root.sh" apply "$ID"

# 【BPF 兼容层核心替换】：
# 在当前 Guest 私有文件系统中替换免 BPF（no_bpf）的系统网络组件。
# 原因：由于在非特权容器环境中宿主机内核独占了 eBPF 网络过滤表，Guest 内部的原生 netd、
# libmeminfo 等若加载 BPF 会直接报 EPERM 导致崩溃；因此替换为打过补丁的 no_bpf 二进制
for SPEC in 'netd_no_bpf system/bin/netd netd_exec' \
    'libmeminfo_no_bpf.so system/lib64/libmeminfo.so system_lib_file' \
    'libnetd_updatable_no_bpf.so system/apex/com.android.tethering/lib64/libnetd_updatable.so system_lib_file' \
    'libnetworkstats_no_bpf.so system/apex/com.android.tethering/lib64/libnetworkstats.so system_lib_file' \
    'libservice-connectivity_guest_inet.so system/apex/com.android.tethering/lib64/libservice-connectivity.so system_lib_file' \
    'service-connectivity_uid_cleanup.jar system/apex/com.android.tethering/javalib/service-connectivity.jar system_file'; do
    set -- $SPEC
    [ -f "$COMPAT/payload/$1" ] && [ -f "$ROOT/$2" ] || exit 3
    copy_payload "$COMPAT/payload/$1" "$ROOT/$2"
    /system/bin/chcon "u:object_r:$3:s0" "$ROOT/$2"
done

# ------------------------------------------------------------------------------
# 7. 物理外设与无用硬件声明裁撤
# ------------------------------------------------------------------------------
# 【移除物理可移动磁盘与不存在的硬件特征声明】：
# 从 fstab.rpi4 中删除 voldmanaged= 物理外接盘配置（防止 vold 尝试格式化宿主机的 SD 卡/U 盘）；
# 删除物理 HDMI-CEC、USB Host、USB 1.0 HAL 服务的声明文件（防止虚拟机因硬件不存在而无限等待或报错）
$BB sed '/voldmanaged=/d' "$ROOT/vendor/etc/fstab.rpi4" > "$ROOT/vendor/etc/fstab.iris"
$BB mv "$ROOT/vendor/etc/fstab.iris" "$ROOT/vendor/etc/fstab.rpi4"
for F in android.hardware.hdmi.cec.xml android.hardware.usb.host.xml; do
    $BB rm -f "$ROOT/vendor/etc/permissions/$F"
done
$BB rm -f "$ROOT/vendor/etc/vintf/manifest/android.hardware.usb@1.0-service.xml"

# 创建关键基础系统目录
$BB mkdir -p "$ROOT/old_root" "$ROOT/metadata" "$ROOT/dev" "$ROOT/sys"

# ------------------------------------------------------------------------------
# 8. 设备文件系统（/dev）与虚拟挂载树精密构建
# ------------------------------------------------------------------------------
# 临时挂载树属于本轮隔离空间，设备按需放入，绝不直接 bind 挂载整份 Host 的 /dev。
# Binder 隔离：彻底隔开服务注册表；GPU 渲染设备（dri）与 dma_heap 则透传复用物理内核设备
for D in dev apex linkerconfig mnt; do
    $BB mount -t tmpfs -o mode=0755 tmpfs "$ROOT/$D"
done

# 【挂载纯净只读 sysfs】：
# 新建的 sysfs 实例将直接反映该私有 Net 命名空间下的网络设备（/sys/class/net），同时保留内核全局的 DRM/设备总线；
# 宿主机的子挂载点不会被继承，后续将显式委托绑定所需节点
$BB mount -t sysfs -o ro,nosuid,nodev,noexec sysfs "$ROOT/sys"

# 创建 /dev 下的核心子目录结构
for D in socket __properties__ pts binderfs dri dma_heap cpuctl cpuset blkio memcg cgroup_info; do
    $BB mkdir -p "$ROOT/dev/$D"
done

# 精确复制内核通用字符设备节点，完整保留其文件权限（mode）、所有者（ownership）与 SELinux 上下文（context）
for NODE in null zero full random urandom ashmem tty fuse; do
    [ ! -e "/dev/$NODE" ] || /system/bin/cp -a --preserve=mode,ownership,context "/dev/$NODE" "$ROOT/dev/$NODE"
done

# 建立标准输入输出与文件描述符软链接
$BB ln -s /proc/self/fd "$ROOT/dev/fd"
$BB ln -s /proc/self/fd/0 "$ROOT/dev/stdin"
$BB ln -s /proc/self/fd/1 "$ROOT/dev/stdout"
$BB ln -s /proc/self/fd/2 "$ROOT/dev/stderr"

# 【内核日志屏蔽】：
# 将 /dev/kmsg 软链接到 /dev/null，防止容器内服务的标准输出/错误污染宿主机内核环形缓冲区（dmesg）；
# Guest 内部的日志由其自建的 logd socket 独立收集管理
$BB ln -s /dev/null "$ROOT/dev/kmsg"

# 挂载独立实例专用的伪终端文件系统 devpts（使用 newinstance 隔离）
$BB mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "$ROOT/dev/pts"
$BB ln -s pts/ptmx "$ROOT/dev/ptmx"

# 挂载专属于该容器的 binderfs，并建立 binder/hwbinder/vndbinder 符号链接，实现 Binder IPC 彻底隔离
$BB mount -t binder binder "$ROOT/dev/binderfs"
for NODE in binder hwbinder vndbinder; do
    $BB ln -s binderfs/$NODE "$ROOT/dev/$NODE"
    $BB chmod 0666 "$ROOT/dev/binderfs/$NODE"
done

# 透传物理 GPU 与内存分配堆节点（保留原厂属性复制 /dev/dri 与 /dev/dma_heap）
for DIR in dri dma_heap; do
    for NODE in /dev/$DIR/*; do
        [ ! -e "$NODE" ] || /system/bin/cp -a --preserve=mode,ownership,context "$NODE" "$ROOT/dev/$DIR/"
    done
done

# 【受托子 cgroup 挂载】：
# 将已委托给本实例的 managed 子 cgroup 目录绑定到容器的 /dev/cpuctl、/dev/cpuset、/dev/blkio、/dev/memcg，
# 确保容器内部的资源限制（配额）完全生效，且杜绝容器访问宿主机的根控制器
for CONTROL in cpuctl cpuset blkio; do
    $BB mount -o bind "/dev/$CONTROL/rootvmos_$ID/managed" "$ROOT/dev/$CONTROL"
done
$BB mount -o bind "$MEM/managed" "$ROOT/dev/memcg"

[ -d "$ROOT/sys/fs/cgroup" ] && [ -d "$ROOT/sys/fs/selinux" ]
[ -d "$ROOT/sys/fs/fuse/connections" ]

# 挂载 cgroup v2 pids 子树到 /sys/fs/cgroup，并复制 cgroup.rc 配置
$BB mount -o bind "$PIDS/managed" "$ROOT/sys/fs/cgroup"
$BB cp /dev/cgroup_info/cgroup.rc "$ROOT/dev/cgroup_info/cgroup.rc"

# ------------------------------------------------------------------------------
# 9. SELinux 共享查询与全局篡改屏蔽
# ------------------------------------------------------------------------------
# 【SELinux 共享查询与全局篡改屏蔽】：
# 将 /sys/fs/selinux 绑定挂载到容器中以保留策略查询能力；
# 但对所有可能修改内核全局策略的接口（如 enforce, load, disable, avc, ss 等）逐一执行
# mount -o remount,bind,ro 设为只读；严禁直接对宿主机文件系统做全局只读 remount！
$BB mount -o bind /sys/fs/selinux "$ROOT/sys/fs/selinux"
for F in enforce load disable checkreqprot commit_pending_bools booleans avc ss; do
    $BB mount -o bind "/sys/fs/selinux/$F" "$ROOT/sys/fs/selinux/$F"
    $BB mount -o remount,bind,ro "$ROOT/sys/fs/selinux/$F"
done

# 绑定挂载实例独立的数据分区和元数据分区
$BB mount -o bind "$RUN/data" "$ROOT/data"
$BB mount -o bind "$RUN/metadata" "$ROOT/metadata"

# ------------------------------------------------------------------------------
# 10. 与 Host 握手完成网络布线与专有 BPF UID 表绑定
# ------------------------------------------------------------------------------
# 【从本 Shell 的 NSpid 提取宿主机真实 PID】：
# 当前进程是 Guest 的 PID 1，其在宿主机视图下的 PID 记录在 /proc/self/status 的 NSpid 行中；
# 获取 Host PID 与启动单调时刻 ticks，组装不可伪造的会话凭证
HOST_PID=
while IFS= read -r LINE; do
    case "$LINE" in NSpid:*) set -- ${LINE#NSpid:}; HOST_PID=$1; [ "$2" = 1 ]; break ;; esac
done < /proc/self/status
START=$(ticks "$HOST_PID")
NETWORK_SESSION=$HOST_PID:$START
NETWORK_NET=$($BB readlink /proc/$HOST_PID/ns/net)

# Refresh only adapter-owned files after the previous Guest has stopped.
AD=$RUN/data/rootvmos_audio
[ ! -L "$AD" ] || exit 3
$BB mkdir -p "$AD"
$BB chmod 0700 "$AD"
for F in token session pcm.sock guest.status guest.status.tmp; do [ ! -L "$AD/$F" ] || exit 3; done
if [ -e "$AD/pcm.sock" ]; then [ -S "$AD/pcm.sock" ] || exit 3; $BB rm -f "$AD/pcm.sock"; fi
$BB od -An -N32 -tx1 /dev/urandom | $BB tr -d ' \n' > "$AD/token"
printf '%s\n' "$NETWORK_SESSION" > "$AD/session"
$BB chmod 0600 "$AD/token" "$AD/session"
$BB sed "s/ROOTVMOS_SESSION/$NETWORK_SESSION/g" "$BASE/audio/guest.rc" > "$ROOT/system/etc/init/zz_rootvmos_audio.rc"
$BB chmod 0644 "$ROOT/system/etc/init/zz_rootvmos_audio.rc"
/system/bin/chcon u:object_r:system_file:s0 "$ROOT/system/etc/init/zz_rootvmos_audio.rc"

# 【发布网络准备握手请求】：
# 向宿主机日志目录发布网络布线请求 network.request，由 Host 侧看门狗负责创建 veth 对并接入网桥
echo "$NETWORK_SESSION" > "$IRIS_RUNTIME_LOG/network.request.tmp"
$BB mv "$IRIS_RUNTIME_LOG/network.request.tmp" "$IRIS_RUNTIME_LOG/network.request"

# 轮询等待 Host 看门狗完成布线并回显相同的会话凭证 network.ready（超时上限 25 秒）
NETWORK_READY=0
for WAIT in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25; do
    if [ -f "$IRIS_RUNTIME_LOG/network.ready" ] &&
       [ "$(cat "$IRIS_RUNTIME_LOG/network.ready")" = "$NETWORK_SESSION" ]; then
        NETWORK_READY=1; break
    fi
    [ ! -f "$IRIS_RUNTIME_LOG/network.error" ] || exit 7
    $BB sleep 1
done
[ "$NETWORK_READY" = 1 ] || { echo 'Network preparation deadline exceeded' >&2; exit 7; }

# 【读取 Host 签发的专有 BPF 权限表信息】：
# cookie 标识 netns、map ID 标识内核表；
# 只给 Guest 原厂框架属于它自己的 UID 权限表，严禁暴露宿主机表或其他实例的权限表
read INET_COOKIE INET_MAP_ID INET_MAP_PATH < "$IRIS_RUNTIME_LOG/network/permission"
case "$INET_COOKIE:$INET_MAP_ID" in ''|*[!0-9:]*|:*|*:) exit 3 ;; esac
[ "$INET_MAP_PATH" = "/sys/fs/bpf/iris_inet_v1/guest_${ID}_${INET_COOKIE}" ] || exit 3

# 【BPF UID 权限表私有挂载】：
# 新建的 sysfs 是只读的；在已有的 bpf 挂载点上私有挂载一个 tmpfs，
# 并将宿主机固化（pinned）的单张专有 UID 权限表绑定至 map_netd_uid_permission_map，
# 满足 Android 原生 libnetd_client 对网络权限核验的要求
$BB mount -t tmpfs -o mode=0755,nosuid,nodev,noexec tmpfs "$ROOT/sys/fs/bpf"
$BB mkdir -p "$ROOT/sys/fs/bpf/netd_shared"
INET_TARGET=$ROOT/sys/fs/bpf/netd_shared/map_netd_uid_permission_map
: > "$INET_TARGET"
$BB mount -o bind "$INET_MAP_PATH" "$INET_TARGET"

# 【bpffs 内核标签说明】：
# bpffs 中的固化 inode 使用内核原生标签，对 chcon 会返回 EOPNOTSUPP；
# 当前容器运行时依赖宿主机 SELinux 保持宽容模式（Permissive），请勿擅自修改
echo "$HOST_PID $START" > "$RUN/init.identity"
echo "$HOST_PID $START" > "$IRIS_RUNTIME_LOG/init.identity"

# ------------------------------------------------------------------------------
# 11. 切根（pivot_root）并终极跃迁进入 Android 原厂 init 第二阶段
# ------------------------------------------------------------------------------
# 【换根、卸载并物理销毁旧根】：
# 1. 进入新的根文件系统目录；
# 2. 调用 pivot_root 将当前目录设为根，旧宿主机根置于 old_root；
# 3. 懒卸载（umount -l）并删除 old_root，彻底将宿主机根文件系统从容器中剥离；
# 4. 挂载专属于该 PID Namespace 的纯净 /proc，并将 /proc/sys 重新设为只读
cd "$ROOT"
$BB pivot_root . old_root
# Host paths are no longer reachable; use the verified Guest copy.
BB=/system/bin/busybox
$BB umount -l /old_root
$BB rmdir /old_root
cd /
$BB mount -t proc proc /proc
$BB mount -o bind /proc/sys /proc/sys
$BB mount -o remount,bind,ro /proc/sys

# 调用 Guest 内部网络脚本，配置网卡 IP 地址与默认网关路由
$BB sh /system/bin/iris_network.sh guest "$ID" "$NETWORK_SESSION" "$NETWORK_NET"

# 导出 Guest 运行时标志位与 no_bpf 网络栈环境变量
export IRIS_GUEST_INIT=1
unset IRIS_GUEST_INIT_PROBE
export IRIS_NETD_NO_BPF=1 IRIS_GUEST_NO_BPF=1

# 【无依赖 SELinux 标签跃迁与最终引导】：
# 由于此时系统 APEX 尚未激活，外部的 runcon 二进制不可用；
# 利用 mksh 内建的 print -rn -- u:r:init:s0 > /proc/self/attr/exec 直接通过 procfs 接口
# 预设下一个执行进程的 SELinux 上下文为 u:r:init:s0；
# 随后通过 exec 彻底替换当前 Shell 进程，正式进入原厂 Android 的 init 第二阶段！
boot_step "进入原厂init第二阶段"
print -rn -- u:r:init:s0 > /proc/self/attr/exec
exec /system/bin/iris_init second_stage

