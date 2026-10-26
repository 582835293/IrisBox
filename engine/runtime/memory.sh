#!/system/bin/sh
# Host持久内存配置：get读值，set停机后保存，prepare_groups下次点火时应用。
# PREVIOUS是界面读到的旧值，用来拒绝过期提交；上限不是预留内存，不能相加当物理容量。
# Per-instance ceiling, saved while stopped and applied by prepare_groups.
set -eu
ACTION=${1:-get}
ID=${2:-7}
. /data/irisbox/runtime/common.sh "$ID"
host_only || exit 3
case "$ACTION" in
get) saved_memory_mib; echo "$MEMORY_MIB" ;;
set)
    REQUESTED=${3:?MiB required}
    PREVIOUS=${4:?Previous MiB required}
    valid_memory_mib "$REQUESTED" || { echo 'Memory must be 512..2560 MiB' >&2; exit 2; }
    [ "$PREVIOUS" = -1 ] || valid_memory_mib "$PREVIOUS" || exit 2
    "$BB" mkdir -p "$RUN"
    if [ "${IRIS_MEMORY_LOCKED:-}" != 1 ]; then
        exec "$BB" timeout -s TERM 40 "$BB" flock -x "$RUN/lifecycle.lock" \
            "$BB" env IRIS_MEMORY_LOCKED=1 sh "$BASE/memory.sh" "$@"
    fi
    groups_empty || { echo 'Stop the instance before changing memory' >&2; exit 4; }
    if [ -f "$RUN/init.identity" ]; then
        read PID TICK < "$RUN/init.identity"
        ! alive "$PID" "$TICK" || { echo 'Instance process is still alive' >&2; exit 4; }
    fi
    C=$BASE/config/memory_v1
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$C/$ID.mib" ] || exit 3
    if ! saved_memory_mib; then
        [ "$PREVIOUS" = -1 ] && [ "$REQUESTED" = "$MEM_DEFAULT_MIB" ] || exit 3
        MEMORY_MIB=-1
    fi
    [ "$MEMORY_MIB" = "$PREVIOUS" ] || { echo 'Saved memory changed; refresh the list' >&2; exit 4; }
    D=$BASE/display/buffer_viewer
    if [ -f "$D/current" ]; then
        CURRENT=$(cat "$D/current")
        case "$CURRENT" in "$D"/runs/*) ;; *) exit 3 ;; esac
        [ "$("$BB" realpath "$CURRENT")" = "$CURRENT" ] || exit 3
        if [ "$(cat "$CURRENT/instance")" = "$ID" ]; then
            [ "$(cat "$CURRENT/state")" = STOPPED ] || { echo 'Wait for display cleanup before changing memory' >&2; exit 4; }
        fi
    fi
    umask 077
    "$BB" mkdir -p "$C"
    TEMP=$C/$ID.mib.$$
    trap '"$BB" rm -f "$TEMP"' EXIT
    printf '%s\n' "$REQUESTED" > "$TEMP"
    "$BB" mv -f "$TEMP" "$C/$ID.mib"
    echo "MEMORY_SAVED id=$ID mib=$REQUESTED; applies on next start"
    ;;
*) echo 'Use get ID or set ID MiB PREVIOUS_MiB' >&2; exit 2 ;;
esac
