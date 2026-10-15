#!/system/bin/sh
# 仅保留本轮第一次QA安装，非用户升级器。无Guest成功启动、无挂载才允许归档。
set -eu
BASE=/data/rootvmos/runtime
BACKUP=/data/rootvmos/qa-e1-20261015
BB=/system/bin/busybox
[ "$($BB readlink -f "$BASE")" = /data/rootvmos/runtime ] && [ ! -L /data/rootvmos ] || exit 3
[ "$(cat "$BASE/engine.version")" = 0.1.0-e1 ] || exit 3
[ ! -e "$BACKUP" ] && [ ! -L "$BACKUP" ] || exit 3
sh "$BASE/display/buffer_viewer/manager.sh" stop
for F in "$BASE"/config/instances_v1/*.record; do
    [ -f "$F" ] || continue
    I=${F##*/}; I=${I%.record}
    . "$BASE/common.sh" "$I"
    host_only && groups_empty || exit 3
    [ ! -e "$RUN/init.identity" ] && [ ! -e "$RUN/network.current" ] && [ ! -e "$RUN/start.lock" ] || exit 3
done
! "$BB" awk -v p="$BASE" '$5==p || index($5,p "/")==1 {found=1} END {exit !found}' /proc/self/mountinfo || exit 3
"$BB" mv "$BASE" "$BACKUP"
echo 'QA e1 archived with ROM and test configuration; old Iris project untouched'
