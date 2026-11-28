#!/system/bin/sh
# Per-instance live orientation. Host persists a mode; only verified Guest wm runs.
set -eu
ACTION=${1:-get}
ID=${2:?Instance required}
case "$ID" in ???? ) ;; *) exit 2;; esac
case "$ID" in *[!0-9]*) exit 2;; esac
[ "$ID" -ge 1007 ] && [ "$ID" -le 1134 ] || exit 2
. /data/irisbox/runtime/common.sh "$ID"
host_only || exit 3
C=$BASE/config/orientation_v1
valid_mode(){ case "$1" in auto|landscape|portrait) return 0;; *) return 2;; esac; }
saved_mode(){
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$C/$ID.mode" ] || return 3
    MODE=auto
    if [ -e "$C/$ID.mode" ]; then
        [ -f "$C/$ID.mode" ] && [ "$("$BB" wc -c < "$C/$ID.mode")" -le 16 ] || return 3
        MODE=$(cat "$C/$ID.mode")
    fi
    valid_mode "$MODE"
}
session_check(){
    read PID TICK < "$RUN/init.identity"
    [ "$PID:$TICK" = "$EXPECTED" ] && verify_init "$PID" "$TICK" || return 3
}
ensure_launcher_rotation(){
    IRIS_EXPECT_SESSION="$EXPECTED" "$BB" timeout 15 sh "$BASE/ctl.sh" exec "$ID" \
        /system/bin/busybox timeout 10 sh -c '
            P=/data/data/com.android.launcher3/shared_prefs/com.android.launcher3.prefs.xml
            for D in /data /data/data /data/data/com.android.launcher3 /data/data/com.android.launcher3/shared_prefs "$P"; do
                [ ! -L "$D" ] || exit 0
            done
            [ -f "$P" ] || exit 0
            [ "$(/system/bin/busybox wc -c < "$P")" -le 65536 ] || exit 0
            /system/bin/busybox grep -q "</map>" "$P" || exit 0
            if /system/bin/busybox grep -q "name=\"pref_allowRotation\"" "$P"; then exit 0; fi
            TMP=/data/local/tmp/iris_l3_prefs.$$
            /system/bin/busybox sed "s|</map>|    <boolean name=\"pref_allowRotation\" value=\"true\" />\n</map>|" "$P" > "$TMP" || { rm -f "$TMP"; exit 0; }
            if [ -s "$TMP" ]; then
                cat "$TMP" > "$P" || true
            fi
            rm -f "$TMP"
            am force-stop com.android.launcher3 >/dev/null 2>&1 || true
        ' 2>/dev/null || true
}
case "$ACTION" in
get) [ $# = 2 ] || exit 2; saved_mode; echo "$MODE"; exit 0;;
set) [ $# = 5 ] || exit 2; EXPECTED=$3; REQUESTED=$4; PREVIOUS=$5; valid_mode "$REQUESTED"; valid_mode "$PREVIOUS";;
apply) [ $# = 3 ] || exit 2; EXPECTED=$3;;
*) exit 2;;
esac
if [ "${IRIS_ORIENTATION_LOCKED:-}" != 1 ]; then
    exec "$BB" timeout -s TERM 40 "$BB" flock -x "$RUN/lifecycle.lock" \
        "$BB" env IRIS_ORIENTATION_LOCKED=1 sh "$BASE/orientation.sh" "$@"
fi
session_check
saved_mode
if [ "$ACTION" = set ]; then
    [ "$MODE" = "$PREVIOUS" ] || { echo 'Saved orientation changed; refresh first' >&2; exit 4; }
else
    REQUESTED=$MODE
    # An existing Guest may have its own rotation preferences. Leave them alone
    # until the owner explicitly selects one of this feature's modes.
    # For Rockchip ROMs defaulting to persist.sys.app.rotation=force_land, clear it when not landscape.
    if [ ! -f "$C/$ID.mode" ]; then
        IRIS_EXPECT_SESSION="$EXPECTED" "$BB" timeout 15 sh "$BASE/ctl.sh" exec "$ID" \
            /system/bin/busybox timeout 10 sh -c '
                RK_ROT=$(getprop persist.sys.app.rotation 2>/dev/null || true)
                [ "$RK_ROT" != force_land ] || setprop persist.sys.app.rotation original || true
            ' 2>/dev/null || true
        ensure_launcher_rotation
        echo "ORIENTATION_DEFAULT $EXPECTED auto"
        exit 0
    fi
fi
IRIS_EXPECT_SESSION="$EXPECTED" "$BB" timeout 25 sh "$BASE/ctl.sh" exec "$ID" \
    /system/bin/busybox timeout 22 sh -c '
set -eu
OLD_ROTATION=$(wm user-rotation -d 0)
OLD_FIXED=$(wm fixed-to-user-rotation -d 0)
OLD_IGNORE=$(wm get-ignore-orientation-request -d 0)
OLD_RK_ROT=$(getprop persist.sys.app.rotation 2>/dev/null || true)
case "$OLD_ROTATION" in free|"lock 0"|"lock 1"|"lock 2"|"lock 3") ;; *) exit 3;; esac
case "$OLD_FIXED" in default|enabled|disabled) ;; *) exit 3;; esac
case "$OLD_IGNORE" in "ignoreOrientationRequest true for displayId=0") IGNORE=true;;
    "ignoreOrientationRequest false for displayId=0") IGNORE=false;; *) exit 3;; esac
DONE=0
finish(){
    RC=$?
    trap - EXIT
    if [ "$DONE" != 1 ]; then
        set +e
        if [ -n "$OLD_RK_ROT" ]; then
            setprop persist.sys.app.rotation "$OLD_RK_ROT" || true
        fi
        wm fixed-to-user-rotation -d 0 "$OLD_FIXED"
        wm set-ignore-orientation-request -d 0 "$IGNORE"
        if [ "$OLD_ROTATION" = free ]; then wm user-rotation -d 0 free
        else wm user-rotation -d 0 lock "${OLD_ROTATION#lock }"; fi
    fi
    exit "$RC"
}
trap finish EXIT
case "$1" in
auto)
    if [ -n "$OLD_RK_ROT" ]; then
        setprop persist.sys.app.rotation original || true
    fi
    wm fixed-to-user-rotation -d 0 default
    wm set-ignore-orientation-request -d 0 false
    wm user-rotation -d 0 free
    [ "$(wm user-rotation -d 0)" = free ]
    ;;
landscape|portrait)
    if [ -n "$OLD_RK_ROT" ] && [ "$OLD_RK_ROT" != original ]; then
        setprop persist.sys.app.rotation original || true
    fi
    R=0; [ "$1" != portrait ] || R=1
    wm set-ignore-orientation-request -d 0 true
    wm user-rotation -d 0 lock "$R"
    wm fixed-to-user-rotation -d 0 enabled
    [ "$(wm user-rotation -d 0)" = "lock $R" ]
    [ "$(wm fixed-to-user-rotation -d 0)" = enabled ]
    ;;
*) exit 2;;
esac
DONE=1
' irisbox-orientation "$REQUESTED"
session_check
ensure_launcher_rotation
umask 077
"$BB" mkdir -p "$C"
TEMP=$C/$ID.mode.$$
EFFECTIVE=$RUN/orientation.effective.$$
trap '"$BB" rm -f "$TEMP" "$EFFECTIVE"' EXIT
printf '%s\n' "$REQUESTED" > "$TEMP"
printf '%s %s\n' "$EXPECTED" "$REQUESTED" > "$EFFECTIVE"
"$BB" mv -f "$TEMP" "$C/$ID.mode"
"$BB" mv -f "$EFFECTIVE" "$RUN/orientation.effective"
echo "ORIENTATION_READY $EXPECTED $REQUESTED"
