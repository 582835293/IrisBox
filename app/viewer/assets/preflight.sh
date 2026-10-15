# 只读宿主检查；不 setenforce、不修改宿主分区/网络/旧项目。
[ "$(id -u)" = 0 ] || { echo 'ROOT_FAIL：请允许 Magisk 授权'; exit 4; }
BB=/data/rootvmos/host/bin/busybox
[ -x "$BB" ] || { echo 'BUSYBOX_MISSING：此宿主缺少运行时所需 BusyBox'; exit 4; }
for NS in pid mnt net ipc uts cgroup; do
    [ "$(readlink /proc/$$/ns/$NS)" = "$(readlink /proc/1/ns/$NS)" ] || { echo "NAMESPACE_FAIL：$NS 必须为宿主空间"; exit 4; }
done
[ "$(getprop ro.product.device)" = rpi4 ] || { echo 'DEVICE_UNSUPPORTED：仅支持树莓派4'; exit 4; }
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || exit 4
[ "$(getprop ro.build.version.sdk)" = 33 ] || { echo 'ANDROID_UNSUPPORTED：需要 AOSP13'; exit 4; }
[ "$(getprop ro.build.fingerprint)" = 'Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys' ] || { echo 'BUILD_UNSUPPORTED：宿主构建指纹尚未适配'; exit 4; }
[ "$(getenforce)" = Permissive ] || { echo 'SELINUX_UNSUPPORTED：首版需要已配置的 Permissive 宿主'; exit 4; }
for FS in overlay binder ext4; do
    "$BB" grep -qw "$FS" /proc/filesystems || { echo "KERNEL_MISSING：$FS"; exit 4; }
done
for F in /dev/memcg/memory.limit_in_bytes /sys/fs/cgroup/cgroup.controllers /dev/cpuctl/cgroup.procs /dev/cpuset/cgroup.procs /dev/blkio/cgroup.procs /dev/dri/renderD128; do
    [ -e "$F" ] || { echo "CAPABILITY_MISSING：$F"; exit 4; }
done
for F in index metacopy xino_auto redirect_dir nfs_export; do
    [ "$(cat /sys/module/overlay/parameters/$F)" = N ] || { echo "OVERLAY_UNSUPPORTED：$F"; exit 4; }
done
"$BB" unshare -m -p -n -u -i -f --propagation private /system/bin/true || { echo 'NAMESPACE_CREATE_FAIL'; exit 4; }
/system/bin/ip route show table wlan0 | "$BB" grep -q '^default ' || { echo 'NETWORK_MISSING：请连接宿主 Wi-Fi'; exit 4; }
echo '宿主检查通过：Magisk root、六种宿主空间、AOSP13 指纹、Permissive、Binder/Overlay、GPU、资源组、Wi-Fi'
echo '支持配置：树莓派4 / ARM64 / rpi4-a13-v1'
echo '内存不足时会拒绝新增启动；不会停止已有实例。'
