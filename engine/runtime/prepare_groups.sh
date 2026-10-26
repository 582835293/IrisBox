#!/system/bin/sh
# Host在实例完全停止时设置预算，wrapper/ctl exec随后加入managed子组。
# memory用v1、pids用v2；父组限制覆盖后代，不能照抄纯v2配置。
set -eu
. /data/irisbox/runtime/common.sh "$1"
host_only && groups_empty || exit 3
load_memory_limit
# A reboot clears the earlier manual root-controller delegation. Restore only
# the available pids controller; preserve all other Host controller settings.
# No Host limit or process membership is changed here.
PIDS_ROOT=/sys/fs/cgroup
$BB grep -qw pids "$PIDS_ROOT/cgroup.controllers" || { echo 'Host pids controller unavailable' >&2; exit 3; }
if ! $BB grep -qw pids "$PIDS_ROOT/cgroup.subtree_control"; then
    builtin echo '+pids' > "$PIDS_ROOT/cgroup.subtree_control"
fi
$BB grep -qw pids "$PIDS_ROOT/cgroup.subtree_control" || exit 3
for G in "$MEM" "$PIDS"; do $BB mkdir -p "$G/managed"; done
echo "$MEM_BYTES" > "$MEM/memory.limit_in_bytes"
echo 0 > "$MEM/memory.max_usage_in_bytes"
echo 0 > "$MEM/memory.failcnt"
[ "$(cat "$MEM/memory.use_hierarchy")" = 1 ] || exit 3
echo 0 > "$MEM/memory.oom_control"
echo "$MAX_TASKS" > "$PIDS/pids.max"
echo +pids > "$PIDS/cgroup.subtree_control"
# The visible root contains init. Enabling pids there makes it a threaded
# domain, so stock UID/PID domain children become invalid. Ancestor pids.max
# still covers every descendant without enabling a controller at this root.
# managed是Guest可见根，留给原厂建UID/PID子组；上限留外层父组。
# 不在已有init的managed根再启pids控制器，以免破坏domain子组语义。
echo -pids > "$PIDS/managed/cgroup.subtree_control"
$BB chown 1000:1000 "$PIDS/managed"
$BB chmod 0775 "$PIDS/managed"
for CONTROL in cpuctl cpuset blkio; do
    G=/dev/$CONTROL/irisbox_$ID
    $BB mkdir -p "$G/managed"
    if [ "$CONTROL" = cpuset ]; then
        for F in cpus mems; do
            cat "/dev/cpuset/$F" > "$G/$F"
            cat "$G/$F" > "$G/managed/$F"
        done
        echo 1 > "$G/managed/cgroup.clone_children"
    fi
    $BB chown 1000:1000 "$G/managed"
    $BB chmod 0755 "$G/managed"
done
[ "$(cat "$MEM/memory.limit_in_bytes")" = "$MEM_BYTES" ]
[ "$(cat "$PIDS/pids.max")" = "$MAX_TASKS" ]
