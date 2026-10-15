#!/system/bin/sh
# Guest side only, reached through a verified fixed-session ctl exec.
set -eu
BB=/system/bin/busybox
SESSION=${1:?Expected Guest init PID:TICK}
case "$SESSION" in *[!0-9:]*|:*|*:|*:*:*) exit 2 ;; esac
case "$SESSION" in *:*) ;; *) exit 2 ;; esac
DIR=/data/iris_stream/$(echo "$SESSION" | tr : _)
if [ -f "$DIR/input.identity" ]; then
    read PID TICK < "$DIR/input.identity"
    case "$PID:$TICK" in *[!0-9:]*|:*|*:) exit 3 ;; esac
    if [ -r "/proc/$PID/stat" ]; then
        [ "$($BB awk '{print $22}' /proc/$PID/stat)" = "$TICK" ] || exit 3
        $BB tr '\000' ' ' < "/proc/$PID/cmdline" | $BB grep -F "com.iris.stream.input.GuestInputMain $SESSION " >/dev/null || exit 3
        kill -TERM "$PID"
    fi
fi
for N in $($BB seq 1 5); do
    if "$BB" flock -n "$DIR/input.lock" "$BB" true; then
        rm -f "$DIR/input.sock" "$DIR/input.ready"
        echo "Guest input stopped; session $SESSION"
        exit 0
    fi
    "$BB" sleep 1
done
echo 'Guest input lock did not release' >&2
exit 7
