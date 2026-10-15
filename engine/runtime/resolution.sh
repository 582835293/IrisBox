#!/system/bin/sh
# Host保存预设并在核验Guest内调用wm，与内存/图形后端分别维护。
# LW/LH是Android逻辑尺寸，OW/OH是传输buffer；legacy逻辑1080p、输出540p，不是原生1080p。
# set存下次配置；apply读ctl的本轮快照；check核对实际wm和会话，供进入显示前使用。
# Host-owned resolution presets. No memory settings or init service changes.
set -eu
ACTION=${1:-get}
ID=${2:-7}
. /data/rootvmos/runtime/common.sh "$ID"
host_only || exit 3
C=$BASE/config/resolution_v1
profile_size() {
    case "$PROFILE" in
        legacy) LW=1920; LH=1080; OW=960; OH=540 ;;
        960x540) LW=960; LH=540; OW=960; OH=540 ;;
        1280x720) LW=1280; LH=720; OW=1280; OH=720 ;;
        1600x900) LW=1600; LH=900; OW=1600; OH=900 ;;
        1920x1080) LW=1920; LH=1080; OW=1920; OH=1080 ;;
        *) echo 'Invalid resolution profile; restore a supported preset' >&2; return 2 ;;
    esac
}
saved_profile() {
    PROFILE=legacy
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$C/$ID.profile" ] || return 3
    if [ -e "$C/$ID.profile" ]; then
        [ -f "$C/$ID.profile" ] && [ "$("$BB" wc -c < "$C/$ID.profile")" -le 32 ] || return 3
        PROFILE=$(cat "$C/$ID.profile")
    fi
    profile_size
}
lock_lifecycle() {
    "$BB" mkdir -p "$RUN"
    if [ "${IRIS_RESOLUTION_LOCKED:-}" != 1 ]; then
        exec "$BB" timeout -s TERM 40 "$BB" flock -x "$RUN/lifecycle.lock" \
            "$BB" env IRIS_RESOLUTION_LOCKED=1 sh "$BASE/resolution.sh" "$@"
    fi
}
session_check() {
    read PID TICK < "$RUN/init.identity"
    [ "$PID:$TICK" = "$EXPECTED" ] && verify_init "$PID" "$TICK" || return 3
}
# 同一次namespace进入里读/改wm，减少重复六空间检查和几十次Shell派生。
# 传入的尺寸/密度来自本文件固定预设；无用户字符串拼进sh命令。
guest_state() {
    IRIS_EXPECT_SESSION="$EXPECTED" "$BB" timeout 10 sh "$BASE/ctl.sh" exec "$ID" /system/bin/busybox timeout 8 sh -c 'set -e; wm size; wm density'
}
guest_apply() {
    IRIS_EXPECT_SESSION="$EXPECTED" "$BB" timeout 12 sh "$BASE/ctl.sh" exec "$ID" /system/bin/busybox timeout 10 sh -c \
        'set -e; wm size "$1"; wm density "$2"; wm size; wm density' iris-resolution "$1" "$2"
}
measure_text() {
    ACTUAL_SIZE=$(printf '%s\n' "$SIZE_TEXT" | "$BB" awk '/^Physical size:/ {v=$3} /^Override size:/ {v=$3} END {print v}')
    ACTUAL_DENSITY=$(printf '%s\n' "$SIZE_TEXT" | "$BB" awk '/^Physical density:/ {v=$3} /^Override density:/ {v=$3} END {print v}')
    [ "$ACTUAL_SIZE" = "${LW}x${LH}" ] && [ "$ACTUAL_DENSITY" = "$DPI" ] || {
        echo 'WindowManager resolution/density does not match this session' >&2; return 7;
    }
}
measure() { SIZE_TEXT=$(guest_state); measure_text; }
# effective绑定已运行会话，不是下次配置；先核身份再用，防重启读旧尺寸。
effective() {
    [ ! -L "$RUN/resolution.effective" ] && [ -f "$RUN/resolution.effective" ] || return 3
    read EFFECTIVE_SESSION PROFILE LW LH OW OH DPI EXTRA < "$RUN/resolution.effective"
    [ -z "$EXTRA" ] && [ "$EFFECTIVE_SESSION" = "$EXPECTED" ] || return 3
    OLD_SIZE="$LW $LH $OW $OH"
    profile_size
    [ "$OLD_SIZE" = "$LW $LH $OW $OH" ] || return 3
    case "$DPI" in ''|*[!0-9]*) return 3 ;; esac
    [ "$DPI" -ge 72 ] && [ "$DPI" -le 1000 ] || return 3
}
case "$ACTION" in
get) saved_profile; echo "$PROFILE" ;;
set)
    PROFILE=${3:?Preset required}; profile_size
    PREVIOUS=${4:?Previous preset required}
    lock_lifecycle "$@"
    groups_empty || { echo 'Stop the instance before changing resolution' >&2; exit 4; }
    if [ -f "$RUN/init.identity" ]; then
        read PID TICK < "$RUN/init.identity"
        ! alive "$PID" "$TICK" || { echo 'Instance process is still alive' >&2; exit 4; }
    fi
    REQUESTED=$PROFILE
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$C/$ID.profile" ] || exit 3
    if ! saved_profile; then
        [ "$PREVIOUS" = invalid ] && [ "$REQUESTED" = legacy ] || exit 3
        PROFILE=invalid
    fi
    [ "$PROFILE" = "$PREVIOUS" ] || { echo 'Saved resolution changed; refresh the list' >&2; exit 4; }
    # Refuse pending display cleanup for this instance, even after Guest exit.
    D=$BASE/display/buffer_viewer
    if [ -f "$D/current" ]; then
        CURRENT=$(cat "$D/current")
        case "$CURRENT" in "$D"/runs/*) ;; *) exit 3 ;; esac
        [ "$("$BB" realpath "$CURRENT")" = "$CURRENT" ] || exit 3
        if [ "$(cat "$CURRENT/instance")" = "$ID" ]; then
            [ "$(cat "$CURRENT/state")" = STOPPED ] || { echo 'Wait for display cleanup before changing resolution' >&2; exit 4; }
        fi
    fi
    umask 077
    "$BB" mkdir -p "$C"
    TEMP=$C/$ID.profile.$$
    trap '"$BB" rm -f "$TEMP"' EXIT
    printf '%s\n' "$REQUESTED" > "$TEMP"
    "$BB" mv -f "$TEMP" "$C/$ID.profile"
    echo "RESOLUTION_SAVED id=$ID profile=$REQUESTED; applies on next start"
    ;;
apply)
    EXPECTED=${3:?Guest session required}
    echo "[启动阶段] uptime=$($BB awk '{print $1}' /proc/uptime) 开始应用分辨率"
    lock_lifecycle "$@"
    session_check
    if [ -f "$RUN/resolution.effective" ] && [ "$("$BB" awk '{print $1}' "$RUN/resolution.effective")" = "$EXPECTED" ]; then
        effective; measure; echo "RESOLUTION_READY $EXPECTED $PROFILE $LW $LH $OW $OH $DPI"; exit 0
    fi
    LOG=$(cat "$RUN/latest.logdir")
    case "$LOG" in "$RUN"/*) ;; *) exit 3 ;; esac
    [ "$("$BB" realpath "$LOG")" = "$LOG" ] || exit 3
    [ -f "$LOG/resolution.profile" ] || { echo 'Session predates resolution configuration; stop and restart it' >&2; exit 4; }
    PROFILE=$(cat "$LOG/resolution.profile"); profile_size
    SIZE_TEXT=$(guest_state)
    DENSITY_TEXT=$SIZE_TEXT
    PHYSICAL_SIZE=$(printf '%s\n' "$SIZE_TEXT" | "$BB" awk '/^Physical size:/ {print $3}')
    PHYSICAL_DENSITY=$(printf '%s\n' "$DENSITY_TEXT" | "$BB" awk '/^Physical density:/ {print $3}')
    [ "$PHYSICAL_SIZE" = 1920x1080 ] || { echo 'Unsupported physical display geometry' >&2; exit 7; }
    case "$PHYSICAL_DENSITY" in ''|*[!0-9]*) exit 7 ;; esac
    DPI=$(( (PHYSICAL_DENSITY*LW+960)/1920 ))
    [ "$DPI" -ge 72 ] && [ "$DPI" -le 1000 ] || exit 7
    if [ "$LW" = 1920 ]; then SIZE_TEXT=$(guest_apply reset reset)
    else SIZE_TEXT=$(guest_apply "${LW}x${LH}" "$DPI"); fi
    measure_text
    session_check
    umask 077
    TEMP=$RUN/resolution.effective.$$
    trap '"$BB" rm -f "$TEMP"' EXIT
    echo "$EXPECTED $PROFILE $LW $LH $OW $OH $DPI" > "$TEMP"
    "$BB" mv -f "$TEMP" "$RUN/resolution.effective"
    cp "$RUN/resolution.effective" "$LOG/resolution.effective"
    echo "[启动阶段] uptime=$($BB awk '{print $1}' /proc/uptime) 分辨率应用完成"
    echo "RESOLUTION_READY $EXPECTED $PROFILE $LW $LH $OW $OH $DPI"
    ;;
check)
    EXPECTED=${3:?Guest session required}
    session_check; effective; measure
    echo "RESOLUTION_READY $EXPECTED $PROFILE $LW $LH $OW $OH $DPI"
    ;;
*) echo 'Use get ID, set ID PRESET PREVIOUS, apply/check ID PID:TICK' >&2; exit 2 ;;
esac
