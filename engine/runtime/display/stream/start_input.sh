#!/system/bin/sh
# Guest-side entry. Copy input.jar and a random root-only input.token into DIR first.
set -eu
BB=/system/bin/busybox
SESSION=${1:?Expected Guest init PID:TICK}
WIDTH=${2:?Native Guest display width}
HEIGHT=${3:?Native Guest display height}
LIFETIME=${4:-0}
case "$SESSION" in *[!0-9:]*|:*|*:|*:*:*) exit 2 ;; esac
case "$SESSION" in *:*) ;; *) exit 2 ;; esac
DIR=/data/iris_stream/$(echo "$SESSION" | tr : _)
[ -d "$DIR" ] && [ -f "$DIR/input.jar" ] && [ -f "$DIR/input.token" ]
chmod 0700 "$DIR"
chmod 0600 "$DIR/input.token"
# BusyBox flock owns a surviving descriptor; Android mksh extra FDs are close-on-exec.
exec "$BB" flock -n "$DIR/input.lock" "$BB" env CLASSPATH="$DIR/input.jar" \
    /system/bin/app_process /system/bin com.iris.stream.input.GuestInputMain "$SESSION" "$WIDTH" "$HEIGHT" "$LIFETIME"
