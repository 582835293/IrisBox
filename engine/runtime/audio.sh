#!/system/bin/sh
# One Host mixer per verified Guest session. A closed Guest socket ends playback.
set -eu
. /data/irisbox/runtime/common.sh "$1"
host_only || exit 3
EXPECTED=${2:?Expected session}
LOG=${3:?Attempt log}
read PID TICK < "$RUN/init.identity"
[ "$PID:$TICK" = "$EXPECTED" ] && verify_init "$PID" "$TICK" || exit 3
[ "$LOG" = "$(cat "$RUN/latest.logdir")" ] && [ ! -L "$LOG" ] || exit 3
case "$LOG" in "$RUN"/[0-9]*_[0-9]*) ;; *) exit 3 ;; esac
D=$LOG/audio
[ ! -L "$D" ] || exit 3
mkdir -p "$D"
chmod 0700 "$D"
if [ "${IRISBOX_AUDIO_LOCKED:-}" != 1 ]; then
    exec "$BB" flock -n "$RUN/audio.lock" "$BB" env IRISBOX_AUDIO_LOCKED=1 sh "$BASE/audio.sh" "$@"
fi
printf '%s %s\n' "$$" "$(ticks $$)" > "$D/supervisor.identity"
G=$RUN/data/irisbox_audio
CHILD=
CHILD_TICK=
finish() {
    trap - EXIT
    if [ -n "$CHILD" ] && alive "$CHILD" "$CHILD_TICK" && "$BB" grep -aqF com.irisbox.audio.AudioBridge "/proc/$CHILD/cmdline"; then
        kill -TERM "$CHILD" 2>/dev/null || true
    fi
}
trap finish EXIT
trap 'exit 0' HUP INT TERM
while verify_init "$PID" "$TICK" && [ "$(cat "$RUN/init.identity")" = "$PID $TICK" ]; do
    if [ -S "$G/pcm.sock" ] && [ ! -L "$G/token" ] && [ -f "$G/token" ]; then
        CLASSPATH="$BASE/audio/audio.jar" /system/bin/app_process /system/bin com.irisbox.audio.AudioBridge \
            host-mapped "$ID" - "$D/host.status" 0 "$EXPECTED" > "$D/player.log" 2>&1 < /dev/null &
        CHILD=$!
        CHILD_TICK=$(ticks "$CHILD")
        printf '%s %s\n' "$CHILD" "$CHILD_TICK" > "$D/player.identity"
        wait "$CHILD" || true
        CHILD=
        CHILD_TICK=
    fi
    "$BB" sleep 2
done
