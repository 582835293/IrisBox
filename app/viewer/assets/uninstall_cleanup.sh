#!/system/bin/sh
# IrisBox 0.3.1 应用内一键彻底清理脚本（随 APK 内存管道执行，不依赖 /data/irisbox/runtime）。
# 边界：
# 1. 严格要求所有实例已由用户手动停止且无活跃全屏会话/挂起任务；
# 2. 仅卸载并清理 IrisBox 自管文件、自管 Swap 与验签过的 Magisk 开机钩子；
# 3. 严守约定：不修改、不卸载、不删除宿主机共享 eBPF INET 分派器及非本应用规则。
set -eu

ACTION=${1:-}
[ "$ACTION" = confirm ] || { echo 'IRIS_CLEANUP_ERROR=INVALID_ACTION' >&2; exit 2; }

fail() {
    echo "IRIS_CLEANUP_ERROR=$1" >&2
    exit 4
}

# 0. 基础宿主环境核验（必须为宿主真 Root 与宿主 7 大 Namespace）
[ "$(/system/bin/id -u)" = 0 ] || fail ROOT_REQUIRED
for NS in user pid mnt net ipc uts cgroup; do
    [ "$(/system/bin/readlink "/proc/$$/ns/$NS")" = "$(/system/bin/readlink "/proc/1/ns/$NS")" ] || fail HOST_NAMESPACE_REQUIRED
done

# 仅使用系统自带或自有 BB；若已不存在 BB 也必须能完整清理
BB=
if [ -x /data/irisbox/host/bin/busybox ] && [ ! -L /data/irisbox/host/bin/busybox ]; then
    BB=/data/irisbox/host/bin/busybox
fi

for P in /data /data/adb /data/adb/service.d /data/irisbox; do
    [ ! -L "$P" ] || fail UNSAFE_PATH
done
[ ! -e /data/irisbox/engine-upgrade.pending ] || fail ENGINE_MAINTENANCE

BASE=/data/irisbox/runtime
CONF_SSD=/data/adb/irisbox_storage.conf
HOOK_SSD=/data/adb/service.d/irisbox-storage.sh
HOOK_SWAP=/data/adb/service.d/irisbox-swap.sh
SWAP_DIR=/data/irisbox/host/swap

alive_pid_tick() {
    _pid=$1
    _tick=$2
    case "$_pid:$_tick" in ''|*[!0-9:]*|:*|*:) return 1 ;; esac
    _res=$(/system/bin/awk -v e="$_tick" 'NF>=22 && $22==e && $3!="Z" && $3!="X" {print "alive"}' "/proc/$_pid/stat" 2>/dev/null || true)
    [ "$_res" = alive ]
}

# 1. 强门禁：必须由用户先手动停止全部实例与活跃显示
for CG in /sys/fs/cgroup/irisbox_*; do
    [ -e "$CG" ] || continue
    [ ! -f "$CG/cgroup.events" ] || /system/bin/grep -qx 'populated 0' "$CG/cgroup.events" || fail STOP_INSTANCES
done

if [ -d "$BASE/config/instances_v1" ]; then
    for REC in "$BASE"/config/instances_v1/*.record; do
        [ -f "$REC" ] || continue
        ID=${REC##*/}; ID=${ID%.record}
        case "$ID" in ''|*[!0-9]*) continue ;; esac
        RUN=$BASE/instances/$ID
        [ ! -e "$RUN/cleanup.pending" ] && [ ! -e "$RUN/userns.pending" ] && [ ! -e "$RUN/start.lock" ] || fail INSTANCE_BUSY
        if [ -f "$RUN/init.identity" ] && [ ! -L "$RUN/init.identity" ]; then
            read P T < "$RUN/init.identity" 2>/dev/null || { P=''; T=''; }
            if alive_pid_tick "${P:-}" "${T:-}"; then
                fail STOP_INSTANCES
            fi
        fi
        [ ! -e "/sys/class/net/rv${ID}h" ] && [ ! -e "$RUN/network.current" ] || fail STOP_INSTANCES
    done
fi

D=$BASE/display/buffer_viewer
if [ -f "$D/current" ] && [ ! -L "$D/current" ]; then
    CUR=$(/system/bin/cat "$D/current" 2>/dev/null || true)
    if [ -n "$CUR" ] && [ -d "$CUR" ] && [ ! -L "$CUR" ]; then
        ST=$(/system/bin/cat "$CUR/state" 2>/dev/null || true)
        if [ "$ST" != STOPPED ] && [ "$ST" != "" ]; then
            if [ -f "$CUR/display.identity" ]; then
                read DP DT < "$CUR/display.identity" 2>/dev/null || { DP=''; DT=''; }
                if alive_pid_tick "${DP:-}" "${DT:-}"; then
                    fail STOP_INSTANCES
                fi
            fi
            if [ -f "$CUR/producer.identity" ]; then
                read PP PT < "$CUR/producer.identity" 2>/dev/null || { PP=''; PT=''; }
                if alive_pid_tick "${PP:-}" "${PT:-}"; then
                    fail STOP_INSTANCES
                fi
            fi
        fi
    fi
fi

# 检查 /data/irisbox 内部是否存在未知残留子挂载点（严格子路径，不包含 /data/irisbox 挂载点自身）
if /system/bin/awk '$5 ~ /^\/data\/irisbox\// {found=1} END {exit !found}' /proc/self/mountinfo; then
    fail SUBMOUNT_ACTIVE
fi

# 2. 优雅停止宿主 BufferBroker 守护进程
if [ -n "$BB" ] && [ -f "$D/manager.sh" ] && [ ! -L "$D/manager.sh" ]; then
    /system/bin/sh "$D/manager.sh" stop >/dev/null 2>&1 || true
fi
if [ -f "$D/broker.identity" ] && [ ! -L "$D/broker.identity" ]; then
    read BP BT < "$D/broker.identity" 2>/dev/null || { BP=''; BT=''; }
    if alive_pid_tick "${BP:-}" "${BT:-}"; then
        if /system/bin/grep -aq 'com.irisbox.buffer.viewer.broker.BufferBroker' "/proc/$BP/cmdline" 2>/dev/null; then
            /system/bin/kill -TERM "$BP" 2>/dev/null || true
            for _W in 1 2 3 4 5; do
                alive_pid_tick "$BP" "$BT" || break
                /system/bin/sleep 1
            done
            if alive_pid_tick "$BP" "$BT"; then
                /system/bin/kill -KILL "$BP" 2>/dev/null || true
            fi
        fi
    fi
fi

# 3. 安全停用并清理 IrisBox 自管 Host Swap（严格校验 512 MiB 回填余量；绝不触碰 zRAM）
RECLAIM_KIB=0
ACTIVE_SWAPS=''
for SZ in 1024 2048 4096; do
    IMG=$SWAP_DIR/swap-$SZ.img
    [ ! -L "$IMG" ] || fail UNSAFE_PATH
    USED=$(/system/bin/awk -v f="$IMG" 'NR>1 && $1==f {print $4}' /proc/swaps 2>/dev/null || true)
    if [ -n "$USED" ]; then
        RECLAIM_KIB=$((RECLAIM_KIB + USED))
        ACTIVE_SWAPS="$ACTIVE_SWAPS $IMG"
    fi
done
if [ -n "$ACTIVE_SWAPS" ]; then
    AVAIL_KIB=$(/system/bin/awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
    [ "$AVAIL_KIB" -ge "$((RECLAIM_KIB + 524288))" ] || fail RECLAIM_MEMORY
    for IMG in $ACTIVE_SWAPS; do
        if [ -n "$BB" ]; then
            "$BB" swapoff "$IMG" || fail SWAPOFF_FAILED
        else
            /system/bin/swapoff "$IMG" || fail SWAPOFF_FAILED
        fi
    done
fi
if [ -d "$SWAP_DIR" ]; then
    for SZ in 1024 2048 4096; do
        for P in "$SWAP_DIR/swap-$SZ.img" "$SWAP_DIR/swap-$SZ.record"; do
            [ ! -L "$P" ] || fail UNSAFE_PATH
            [ ! -e "$P" ] || /system/bin/rm -f "$P"
        done
    done
    [ ! -L "$SWAP_DIR/settings" ] || fail UNSAFE_PATH
    /system/bin/rm -f "$SWAP_DIR/settings" "$SWAP_DIR/boot.log" 2>/dev/null || true
fi

# 4. 验签移除 IrisBox 自管 Magisk 开机恢复脚本（不误删用户其它模块脚本）
check_and_remove_hook() {
    _path=$1
    _magic=$2
    [ ! -L "$_path" ] || fail UNSAFE_BOOT_HOOK
    if [ -e "$_path" ]; then
        [ -f "$_path" ] || fail FOREIGN_BOOT_HOOK
        /system/bin/head -n 5 "$_path" 2>/dev/null | /system/bin/grep -qFx "$_magic" || fail FOREIGN_BOOT_HOOK
        /system/bin/rm -f "$_path" || fail CLEANUP_FAILED
    fi
}
check_and_remove_hook "$HOOK_SWAP" "# IRISBOX_MANAGED_HOST_SWAP_V1"
check_and_remove_hook "$HOOK_SSD" "# IRISBOX_MANAGED_STORAGE_V1"
[ ! -L /data/adb/irisbox-storage.log ] && [ -f /data/adb/irisbox-storage.log ] && /system/bin/rm -f /data/adb/irisbox-storage.log || true

# 5. 彻底清空 /data/irisbox 及外置 SSD 绑定与挂载点（不触碰共享 eBPF 分派器）
SSD_CONF_PRESENT=0
if [ -e "$CONF_SSD" ]; then
    [ ! -L "$CONF_SSD" ] || fail UNSAFE_PATH
    SSD_CONF_PRESENT=1
fi

IS_EXT4_MOUNT=0
if [ -d /data/irisbox ]; then
    D_DATA=$(/system/bin/stat -c '%d' /data 2>/dev/null || echo 0)
    D_IRIS=$(/system/bin/stat -c '%d' /data/irisbox 2>/dev/null || echo 0)
    if [ "$D_IRIS" != 0 ] && [ "$D_IRIS" != "$D_DATA" ] && /system/bin/grep -qE '^[^ ]+ /data/irisbox ext4 ' /proc/mounts; then
        IS_EXT4_MOUNT=1
    fi
fi

SSD_DETACHED=0
if [ "$IS_EXT4_MOUNT" = 1 ]; then
    # 清除外置 SSD 挂载根下的 IrisBox 受控目录与标识文件，然后安全卸载挂载点
    for ENTRY in /data/irisbox/runtime /data/irisbox/host /data/irisbox/roms /data/irisbox/instances /data/irisbox/.irisbox_ssd_marker /data/irisbox/engine-upgrade.pending; do
        [ ! -e "$ENTRY" ] && [ ! -L "$ENTRY" ] || /system/bin/rm -rf -- "$ENTRY"
    done
    # 清理残余隐藏暂存目录（如 .engine-* / .rom-* / .upgrade-*）
    for ENTRY in /data/irisbox/.engine-* /data/irisbox/.rom-* /data/irisbox/.upgrade-*; do
        [ ! -e "$ENTRY" ] && [ ! -L "$ENTRY" ] || /system/bin/rm -rf -- "$ENTRY"
    done
    /system/bin/sync
    /system/bin/nsenter -t 1 -m /system/bin/umount /data/irisbox || fail UNMOUNT_FAILED
    /system/bin/rmdir /data/irisbox 2>/dev/null || true
    /system/bin/rm -f "$CONF_SSD" 2>/dev/null || true
elif [ -e /data/irisbox ]; then
    if [ "$SSD_CONF_PRESENT" = 1 ]; then
        SSD_DETACHED=1
        /system/bin/rm -f "$CONF_SSD" 2>/dev/null || true
    fi
    /system/bin/rm -rf -- /data/irisbox || fail CLEANUP_FAILED
elif [ "$SSD_CONF_PRESENT" = 1 ]; then
    SSD_DETACHED=1
    /system/bin/rm -f "$CONF_SSD" 2>/dev/null || true
fi

/system/bin/sync
echo "IRIS_CLEANUP_SSD_DETACHED=$SSD_DETACHED"
echo "IRIS_CLEANUP_DONE"
