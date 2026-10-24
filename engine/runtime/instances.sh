#!/system/bin/sh
# 阅读入口：Host持久登记和停止后的数据生命周期；不代替Android init。
# 永久ID定位目录/网段；界面序号由排序后的登记表计算，删除卡片不重命名旧数据。
# REVISION是数据代次的随机令牌，重置后换新，用于拒绝旧确认弹窗和旧请求。
# One registry and one stopped-instance data lifecycle. Android still owns boot.
set -eu
ACTION=${1:-list}
ID=${2:-7}
case "$ACTION" in create) ID=7 ;; esac
. /data/rootvmos/runtime/common.sh "$ID"
host_only || exit 3
C=$BASE/config/instances_v1
RECORD=$C/$ID.record
EXPECTED=${3:-}
GOOGLE=${2:-0}
ROOT_PROFILE=${3:-off}
# 管理文件用077；点火wrapper独立恢复022，否则Android普通服务读不到属性区。
umask 077
for D in "$BASE/config" "$C" "$BASE/instances"; do
    [ ! -L "$D" ] || exit 3
    "$BB" mkdir -p "$D"
    [ "$($BB readlink -f "$D")" = "$D" ] || exit 3
done
[ ! -L "$C/registry.lock" ] && [ ! -L "$C/initialized" ] || exit 3
if [ "${IRIS_REGISTRY_LOCKED:-}" != 1 ]; then
    exec "$BB" timeout -s TERM 150 "$BB" flock -x "$C/registry.lock" \
        "$BB" env IRIS_REGISTRY_LOCKED=1 sh "$BASE/instances.sh" "$@"
fi
token() { "$BB" head -c 32 /dev/urandom | "$BB" sha256sum | "$BB" cut -d ' ' -f 1; }
record() {
    [ ! -L "$RECORD" ] && [ -f "$RECORD" ] || return 3
    REVISION=$(cat "$RECORD")
    [ "${#REVISION}" = 64 ] || return 3
    case "$REVISION" in *[!a-f0-9]*) return 3 ;; esac
    [ "$REVISION" = "$EXPECTED" ] || { echo 'Instance revision changed; refresh first' >&2; return 4; }
}
write_record() {
    token > "$C/$1.record.tmp.$$"
    "$BB" mv "$C/$1.record.tmp.$$" "$C/$1.record"
}
# 除了任务组空，还核旧init、网络、显示、Host挂载；防止删数据时跨入共享挂载。
stopped() {
    groups_empty || { echo 'Stop this instance before clearing data' >&2; return 4; }
    if [ -f "$RUN/init.identity" ]; then
        read P T < "$RUN/init.identity"
        ! alive "$P" "$T" || { echo 'Instance identity is still live' >&2; return 4; }
    fi
    [ ! -e "/sys/class/net/rv${ID}h" ] && [ ! -e "$RUN/network.current" ] || {
        echo 'Instance network cleanup is still pending' >&2; return 4;
    }
    D=$BASE/display/buffer_viewer
    if [ -f "$D/current" ]; then
        CURRENT=$(cat "$D/current")
        case "$CURRENT" in "$D"/runs/*) ;; *) return 3 ;; esac
        [ "$($BB readlink -f "$CURRENT")" = "$CURRENT" ] || return 3
        if [ "$(cat "$CURRENT/instance")" = "$ID" ]; then
            [ "$(cat "$CURRENT/state")" = STOPPED ] || { echo 'Wait for display cleanup' >&2; return 4; }
        fi
    fi
    [ ! -L "$RUN" ] && [ -d "$RUN" ] && [ "$($BB readlink -f "$RUN")" = "$RUN" ] || return 3
    # Refuse any Host mount below the data path; never cross mount points on erase.
    ! "$BB" awk -v p="$RUN" '$5==p || index($5,p "/")==1 {found=1} END {exit !found}' /proc/self/mountinfo || return 3
}
# 递归删除只接收本实例受控.erase路径，不接收App传来的任意目录。
erase_tree() {
    TARGET=$1
    case "$TARGET" in "$BASE"/instances/.erase_"$ID"_*) ;; *) return 3 ;; esac
    [ ! -L "$TARGET" ] && [ "$($BB readlink -f "$TARGET")" = "$TARGET" ] || return 3
    ! "$BB" awk -v p="$TARGET" '$5==p || index($5,p "/")==1 {found=1} END {exit !found}' /proc/self/mountinfo || return 3
    "$BB" rm -rf -- "$TARGET"
    [ ! -e "$TARGET" ] || return 7
}
case "$ACTION" in
init)
    if [ ! -f "$C/initialized" ]; then
        [ ! -L "$C/next_id" ] || exit 3
        printf '1007\n' > "$C/next_id"
        printf '1\n' > "$C/initialized"
    fi
    echo INSTANCE_REGISTRY_READY
    ;;
list)
    for F in "$C"/*.record; do
        [ -f "$F" ] || continue
        N=${F##*/};N=${N%.record}
        printf '%s %s\n' "$N" "$(cat "$F")"
    done
    ;;
create)
    case "$GOOGLE" in 0|1) ;; *) exit 2 ;; esac
    case "$ROOT_PROFILE" in off|magisk) ;; *) exit 2 ;; esac
    if [ "$ROOT_PROFILE" = magisk ]; then
        [ "$(getenforce)" = Permissive ] || exit 4
        sh "$BASE/root.sh" verify 7 >/dev/null
    fi
    [ -f "$C/initialized" ] && [ ! -L "$C/next_id" ] || exit 3
    NEXT=$(cat "$C/next_id")
    valid_instance_id "$NEXT" || exit 3
    while [ -e "$BASE/instances/$NEXT" ] || [ -e "$C/$NEXT.record" ]; do
        NEXT=$((NEXT+1));valid_instance_id "$NEXT" || { echo 'Instance ID space exhausted' >&2; exit 4; }
    done
    if [ "$GOOGLE" = 1 ]; then sh "$BASE/google/a13_arm64/guest_layer.sh" verify 7 >/dev/null; fi
    for K in resolution_v1 orientation_v1 memory_v1 graphics_v1 root_v1 rom_v1; do
        [ ! -L "$BASE/config/$K" ] || exit 3
        "$BB" mkdir -p "$BASE/config/$K"
    done
    for F in "$BASE/config/resolution_v1/$NEXT.profile" "$BASE/config/orientation_v1/$NEXT.mode" "$BASE/config/memory_v1/$NEXT.mib" "$BASE/config/graphics_v1/$NEXT.profile" "$BASE/config/root_v1/$NEXT.profile"; do
        [ ! -e "$F" ] && [ ! -L "$F" ] || { echo 'Reserved instance configuration already exists' >&2; exit 3; }
    done
    [ -f "$ROM/rom.json" ] && [ -f "$ROM/system.img" ] && [ -f "$ROM/vendor.img" ] || {
        echo 'Import a supported ROM in the App first' >&2; exit 4;
    }
    [ ! -e "$BASE/config/rom_v1/$NEXT.id" ] && [ ! -L "$BASE/config/rom_v1/$NEXT.id" ] || exit 3
    # 先消耗内部ID再写配置，失败后不复用半成品；显示序号仍按当前数量连续。
    # Reserve the ID first: a failed create never reuses a partially written ID.
    printf '%s\n' "$((NEXT+1))" > "$C/next_id.tmp.$$"
    "$BB" mv "$C/next_id.tmp.$$" "$C/next_id"
    "$BB" mkdir -m 0755 "$BASE/instances/$NEXT"
    : > "$BASE/instances/$NEXT/userns.pending"
    printf '960x540\n' > "$BASE/config/resolution_v1/$NEXT.profile"
    DEFAULT_MEMORY=1280
    [ "$GOOGLE" != 1 ] || DEFAULT_MEMORY=1536
    printf '%s\n' "$DEFAULT_MEMORY" > "$BASE/config/memory_v1/$NEXT.mib"
    printf 'opengl\n' > "$BASE/config/graphics_v1/$NEXT.profile"
    printf '%s\n' "$ROOT_PROFILE" > "$BASE/config/root_v1/$NEXT.profile"
    if [ "$GOOGLE" = 1 ]; then cp "$COMPAT/google/component.id" "$BASE/instances/$NEXT/google.core.enabled"; fi
    printf '%s\n' "$ROM_ID" > "$BASE/config/rom_v1/$NEXT.id"
    write_record "$NEXT"
    "$BASE/rootvmos_userns" enable "$NEXT" >/dev/null
    "$BB" rm -f "$BASE/instances/$NEXT/userns.pending"
    echo INSTANCE_CREATED=$NEXT
    ;;
reset|delete)
    record
    [ ! -L "$RUN" ] && [ -d "$RUN" ] && [ ! -L "$RUN/lifecycle.lock" ] || exit 3
    for K in resolution_v1 orientation_v1 memory_v1 graphics_v1 root_v1 rom_v1; do [ ! -L "$BASE/config/$K" ] || exit 3; done
    for F in "$BASE/config/resolution_v1/$ID.profile" "$BASE/config/orientation_v1/$ID.mode" "$BASE/config/memory_v1/$ID.mib" "$BASE/config/graphics_v1/$ID.profile" "$BASE/config/root_v1/$ID.profile" "$RUN/google.core.enabled"; do [ ! -L "$F" ] || exit 3; done
    if [ "${IRIS_INSTANCE_DATA_LOCKED:-}" != 1 ]; then
        exec "$BB" flock -x "$RUN/lifecycle.lock" "$BB" env IRIS_INSTANCE_DATA_LOCKED=1 sh "$BASE/instances.sh" "$@"
    fi
    record
    stopped
    # Each destructive path is fixed under instances and identified by its revision.
    # 先记操作及旧revision再搬暂存目录，中断后只续同一操作。
    # 原lifecycle.lock inode留在原位，避免等待的start拿到另一份锁。
    PENDING=$RUN/cleanup.pending
    [ ! -L "$PENDING" ] || exit 3
    ERASE_REVISION=$REVISION
    if [ -f "$PENDING" ]; then
        [ "$($BB wc -c < "$PENDING")" -le 80 ] || exit 3
        read PENDING_ACTION ERASE_REVISION < "$PENDING"
        [ "$PENDING_ACTION" = "$ACTION" ] || { echo 'Retry the unfinished data operation first' >&2; exit 4; }
        [ "${#ERASE_REVISION}" = 64 ] || exit 3
        case "$ERASE_REVISION" in *[!a-f0-9]*) exit 3 ;; esac
    else
        printf '%s %s\n' "$ACTION" "$REVISION" > "$PENDING"
    fi
    ERASE=$BASE/instances/.erase_${ID}_${ERASE_REVISION}
    [ ! -L "$ERASE" ] || exit 3
    if [ ! -e "$ERASE" ]; then "$BB" mkdir -m 0700 "$ERASE"; fi
    [ -d "$ERASE" ] && [ "$($BB readlink -f "$ERASE")" = "$ERASE" ] || exit 3
    # Keep lifecycle.lock's inode in place until the operation exits. A waiting
    # start must not acquire a new lock merely because the data was reset.
    for F in "$RUN"/* "$RUN"/.[!.]* "$RUN"/..?*; do
        [ -e "$F" ] || [ -L "$F" ] || continue
        NAME=${F##*/}
        case "$NAME" in lifecycle.lock|cleanup.pending) continue ;; esac
        if [ "$ACTION" = reset ] && [ "$NAME" = google.core.enabled ]; then
            [ ! -L "$F" ] || exit 3
            continue
        fi
        [ ! -e "$ERASE/$NAME" ] && [ ! -L "$ERASE/$NAME" ] || exit 3
        "$BB" mv "$F" "$ERASE/$NAME"
    done
    erase_tree "$ERASE"
    if [ "$ACTION" = reset ]; then
        : > "$RUN/userns.pending"
        write_record "$ID"
        "$BASE/rootvmos_userns" enable "$ID" >/dev/null
        "$BB" rm -f "$RUN/userns.pending"
        "$BB" rm -f "$PENDING"
        echo INSTANCE_RESET=$ID
    else
        for F in "$BASE/config/resolution_v1/$ID.profile" "$BASE/config/orientation_v1/$ID.mode" "$BASE/config/memory_v1/$ID.mib" "$BASE/config/graphics_v1/$ID.profile" "$BASE/config/root_v1/$ID.profile" "$BASE/config/rom_v1/$ID.id"; do
            [ ! -L "$F" ] || exit 3
            "$BB" rm -f "$F"
        done
        "$BB" rm -f "$RECORD"
        "$BB" rm -f "$RUN/lifecycle.lock" "$PENDING"
        "$BB" rmdir "$RUN"
        echo INSTANCE_DELETED=$ID
    fi
    ;;
*) echo 'Use init, list, create GOOGLE(0|1) ROOT(off|magisk), reset ID REVISION, delete ID REVISION' >&2;exit 2 ;;
esac
