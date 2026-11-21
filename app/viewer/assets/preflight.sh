# 只读宿主检查；不 setenforce、不修改宿主分区/网络/旧项目。
[ "$(id -u)" = 0 ] || { echo 'ROOT_FAIL：请允许 Magisk 授权'; exit 4; }
BB=/data/irisbox/host/bin/busybox
[ -x "$BB" ] || { echo 'BUSYBOX_MISSING：此宿主缺少运行时所需 BusyBox'; exit 4; }
for NS in user pid mnt net ipc uts cgroup; do
    [ "$(readlink /proc/$$/ns/$NS)" = "$(readlink /proc/1/ns/$NS)" ] || { echo "NAMESPACE_FAIL：$NS 必须为宿主空间"; exit 4; }
done
# BEGIN IRIS_BOARDS
IRIS_DEV=$(/system/bin/getprop ro.product.device)
IRIS_ABI=$(/system/bin/getprop ro.product.cpu.abi)
IRIS_SDK=$(/system/bin/getprop ro.build.version.sdk)
IRIS_FP=$(/system/bin/getprop ro.build.fingerprint)
IRIS_PROFILE=""
IRIS_DEV_MATCHED=0
IRIS_ABI_MATCHED=0
IRIS_SDK_MATCHED=0
if [ -z "$IRIS_PROFILE" ]; then
    case "$IRIS_DEV" in rk3588s_s|orangepi5|rk3588_s|rk3588)
        IRIS_DEV_MATCHED=1
        if [ "$IRIS_ABI" = "arm64-v8a" ]; then
            IRIS_ABI_MATCHED=1
            if [ "$IRIS_SDK" = "32" ]; then
                IRIS_SDK_MATCHED=1
                case "$IRIS_FP" in rockchip/rk3588s_s/rk3588s_s:12/SQ3A.220705.003.A1/eng.orangepi.20261029:userdebug/release-keys)
                    IRIS_PROFILE="opi5-a12-v1"
                    IRIS_REQ_DEVS="/dev/mali0"
                    IRIS_UPLINKS="eth0 wlan0"
                    IRIS_REQ_EXT_MOUNT="1"
                    IRIS_SUPPORT_LINE="支持配置：香橙派5 / ARM64 / opi5-a12-v1"
                    ;;
                esac
            fi
        fi
        ;;
    esac
fi
if [ -z "$IRIS_PROFILE" ]; then
    case "$IRIS_DEV" in rpi4)
        IRIS_DEV_MATCHED=1
        if [ "$IRIS_ABI" = "arm64-v8a" ]; then
            IRIS_ABI_MATCHED=1
            if [ "$IRIS_SDK" = "33" ]; then
                IRIS_SDK_MATCHED=1
                case "$IRIS_FP" in Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys)
                    IRIS_PROFILE="rpi4-a13-v1"
                    IRIS_REQ_DEVS="/dev/dri/renderD128"
                    IRIS_UPLINKS="wlan0"
                    IRIS_REQ_EXT_MOUNT="0"
                    IRIS_SUPPORT_LINE="支持配置：树莓派4 / ARM64 / rpi4-a13-v1"
                    ;;
                esac
            fi
        fi
        ;;
    esac
fi
[ "$IRIS_DEV_MATCHED" = 1 ] || { echo "DEVICE_UNSUPPORTED：当前设备（$IRIS_DEV）尚未适配"; exit 4; }
[ "$IRIS_ABI_MATCHED" = 1 ] || exit 4
[ "$IRIS_SDK_MATCHED" = 1 ] || { echo "ANDROID_UNSUPPORTED：宿主系统版本（SDK $IRIS_SDK）与板卡要求不符"; exit 4; }
[ -n "$IRIS_PROFILE" ] || { echo "BUILD_UNSUPPORTED：宿主构建指纹尚未适配"; exit 4; }
for _REQ_DEV in $IRIS_REQ_DEVS; do [ -e "$_REQ_DEV" ] || { echo "GPU_MISSING：缺少图形设备节点 $_REQ_DEV"; exit 4; }; done
# END IRIS_BOARDS
if [ "${IRIS_REQ_EXT_MOUNT:-0}" = 1 ] || [ -e /data/adb/irisbox_storage.conf ]; then
    [ ! -L /data/irisbox ] && [ -d /data/irisbox ] || { echo 'SSD_NOT_MOUNTED：请先在外置存储中绑定并挂载 SSD 至 /data/irisbox'; exit 4; }
    [ ! -L /data/adb/irisbox_storage.conf ] && [ -f /data/adb/irisbox_storage.conf ] || { echo 'SSD_UNBOUND：当前板卡要求外置 ext4 SSD，请先在首页“外置存储”绑定并挂载'; exit 4; }
    read _SSD_M _SSD_U _SSD_E < /data/adb/irisbox_storage.conf || { echo 'SSD_CONF_INVALID：外置存储配置损坏'; exit 4; }
    [ "$_SSD_M" = IRISBOX_SSD_V1 ] && [ -n "$_SSD_U" ] && [ -z "$_SSD_E" ] || { echo 'SSD_CONF_INVALID：外置存储配置损坏'; exit 4; }
    [ "$("$BB" stat -c '%d' /data/irisbox)" != "$("$BB" stat -c '%d' /data)" ] && "$BB" grep -qE '^[^ ]+ /data/irisbox ext4 ' /proc/mounts || { echo 'SSD_NOT_MOUNTED：外置 SSD 未挂载至 /data/irisbox，已拦截写入内部存储，请点“外置存储”恢复挂载'; exit 4; }
    [ ! -L /data/irisbox/.irisbox_ssd_marker ] && [ -f /data/irisbox/.irisbox_ssd_marker ] && read _MM _MU _ME < /data/irisbox/.irisbox_ssd_marker && [ "$_MM" = IRISBOX_SSD_V1 ] && [ "$_MU" = "$_SSD_U" ] && [ -z "$_ME" ] || { echo 'SSD_MARKER_MISMATCH：外置 SSD 身份标记不匹配'; exit 4; }
fi
[ "$(getenforce)" = Permissive ] || { echo 'SELINUX_UNSUPPORTED：首版需要已配置的 Permissive 宿主'; exit 4; }
for FS in overlay binder ext4; do
    "$BB" grep -qw "$FS" /proc/filesystems || { echo "KERNEL_MISSING：$FS"; exit 4; }
done
for F in /dev/memcg/memory.limit_in_bytes /sys/fs/cgroup/cgroup.controllers /dev/cpuctl/cgroup.procs /dev/cpuset/cgroup.procs /dev/blkio/cgroup.procs; do
    [ -e "$F" ] || { echo "CAPABILITY_MISSING：$F"; exit 4; }
done
for F in index metacopy xino_auto redirect_dir nfs_export; do
    [ "$(cat /sys/module/overlay/parameters/$F)" = N ] || { echo "OVERLAY_UNSUPPORTED：$F"; exit 4; }
done
"$BB" unshare -m -p -n -u -i -f --propagation private /system/bin/true || { echo 'NAMESPACE_CREATE_FAIL'; exit 4; }
"$BB" unshare -U /system/bin/true || { echo 'USER_NAMESPACE_CREATE_FAIL'; exit 4; }
_NET_UPLINK=''
for _IFACE in $IRIS_UPLINKS; do
    /system/bin/ip link show dev "$_IFACE" 2>/dev/null | "$BB" grep -Eq '<[^>]*LOWER_UP[^>]*>|state UP' || continue
    /system/bin/ip route show table "$_IFACE" 2>/dev/null | "$BB" grep -q '^default ' && { _NET_UPLINK=$_IFACE; break; }
done
if [ -z "$_NET_UPLINK" ]; then
    for _PATH in /sys/class/net/*; do
        [ -e "$_PATH" ] || continue
        _IFACE=${_PATH##*/}
        case "$_IFACE" in ''|lo|dummy*|tun*|tap*|rv*|veth*|ip_vti*|ip6_vti*|ip6tnl*|sit*|gre*|ifb*|rmnet_r_*|p2p*|bridge*|br*|docker*|wlan_ap*) continue ;; esac
        /system/bin/ip link show dev "$_IFACE" 2>/dev/null | "$BB" grep -Eq '<[^>]*LOWER_UP[^>]*>|state UP' || continue
        /system/bin/ip route show table "$_IFACE" 2>/dev/null | "$BB" grep -q '^default ' && { _NET_UPLINK=$_IFACE; break; }
    done
fi
if [ -n "$_NET_UPLINK" ]; then
    echo "宿主检查通过：Magisk root、User namespace、宿主指纹、Permissive、Binder/Overlay、GPU、资源组、上行网络 ($_NET_UPLINK)"
else
    echo '宿主检查通过：Magisk root、User namespace、宿主指纹、Permissive、Binder/Overlay、GPU、资源组（当前无活跃上行网络，实例将以无网占位模式启动，联网后自动接入）'
fi
echo "$IRIS_SUPPORT_LINE"
echo '内存不足时会拒绝新增启动；不会停止已有实例。'
