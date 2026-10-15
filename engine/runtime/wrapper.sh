#!/system/bin/sh
# Host点火包装器：加入资源组 → 建cgroup空间 → unshare其余五个空间 → inner.sh。
# exec替换当前进程，不额外留下常驻管理器；Android服务最终由Guest init管理。
set -eu
# init 的属性区域必须保持对 Android 服务 UID 可读，
# 即使私有备份/维护调用方以 umask 077 启动了管理器。
umask 022
. /data/rootvmos/runtime/common.sh "$1"
host_only || exit 3
export IRIS_RUNTIME_LOG=$2
join_groups
exec "$BASE/rootvmos_ns" new-cgroup -- "$BB" unshare -m -p -n -u -i -f \
    --propagation private /system/bin/sh "$BASE/inner.sh" "$ID"
