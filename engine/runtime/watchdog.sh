#!/system/bin/sh
# Host每次启动的看护进程，由ctl先启动；LOG绑定attempt，不绑定卡片显示序号。
# 期限0只取消自动到期，身份、低内存和网络策略检查仍运行。
# 退出/异常按准确会话清理，旧看护不能关闭新一轮实例。
# Existing Host supervisor also owns this attempt's network setup and cleanup.
set -eu
. /data/irisbox/runtime/common.sh "$1"
host_only || exit 3
MAX_SECONDS=$2
case "$MAX_SECONDS" in 0|180|300|600|1800) ;; *) exit 2 ;; esac
LOG=$3
AUDIO_WRAPPER=
AUDIO_TICK=
cleanup_network() {
    [ -f "$LOG/network.request" ] || return 0
    SESSION=$(cat "$LOG/network.request")
    /system/bin/sh "$BASE/network.sh" cleanup "$ID" "$SESSION" "$LOG"
}
# 保留原退出码；若网络回收失败则报告7并留账本，不能假称全停成功。
finish() {
    RESULT=$?
    trap - EXIT
    set +e
    if [ -f "$LOG/audio/supervisor.identity" ] && [ ! -L "$LOG/audio/supervisor.identity" ]; then
        read AP AT < "$LOG/audio/supervisor.identity"
        if alive "$AP" "$AT" && "$BB" grep -aqF "$BASE/audio.sh" "/proc/$AP/cmdline" && "$BB" grep -aqF "$LOG" "/proc/$AP/cmdline"; then
            kill -TERM "$AP" 2>/dev/null
        fi
    fi
    if [ -n "$AUDIO_WRAPPER" ] && alive "$AUDIO_WRAPPER" "$AUDIO_TICK" && "$BB" grep -aqF "$BASE/audio.sh" "/proc/$AUDIO_WRAPPER/cmdline"; then
        kill -TERM "$AUDIO_WRAPPER" 2>/dev/null
    fi
    cleanup_network
    CLEAN_RESULT=$?
    [ "$CLEAN_RESULT" = 0 ] || { echo "Network cleanup failed: $CLEAN_RESULT" >&2; exit 7; }
    exit "$RESULT"
}
trap finish EXIT
trap 'exit 7' HUP INT TERM
echo "$$ $(ticks $$)" > "$LOG/watchdog.ready"
START_SECONDS=$($BB date +%s)
N=0
# Continue checking identity and memory even when the lifetime is unlimited.
while [ "$MAX_SECONDS" = 0 ] || [ "$N" -lt "$MAX_SECONDS" ]; do
    if [ -f "$LOG/network.request" ] && [ ! -f "$LOG/network.ready" ]; then
        SESSION=$(cat "$LOG/network.request")
        if ! /system/bin/sh "$BASE/network.sh" prepare "$ID" "$SESSION" "$LOG" "$MAX_SECONDS" > "$LOG/network.prepare.log" 2>&1; then
            echo 'Network preparation failed; stopping this attempt' >&2
            break
        fi
    fi
    if [ -f "$LOG/init.identity" ]; then
        read PID TICK < "$LOG/init.identity"
        if ! alive "$PID" "$TICK"; then echo 'Recorded Guest exited'; exit 0; fi
        if { [ -z "$AUDIO_WRAPPER" ] || ! alive "$AUDIO_WRAPPER" "$AUDIO_TICK"; } && [ $((N % 5)) = 0 ] && verify_init "$PID" "$TICK"; then
            "$BB" nohup sh "$BASE/audio.sh" "$ID" "$PID:$TICK" "$LOG" > "$LOG/audio-supervisor.log" 2>&1 < /dev/null &
            AUDIO_WRAPPER=$!
            AUDIO_TICK=$(ticks "$AUDIO_WRAPPER")
        fi
        # A Host netd/bpfloader restart may reattach its stock hook. Reconcile
        # only our known original/dispatcher; reject unknown third-party hooks.
        # 每5秒协调已知策略，不是列表预览周期；未知第三方策略拒绝覆盖。
        if [ $((N % 5)) = 0 ] && [ -s "$LOG/network/permission" ]; then
            if ! "$BASE/iris_inet_policy" ensure >> "$LOG/inet_policy.log" 2>&1; then
                echo 'Guest network permission policy unavailable; stopping this attempt' >&2
                break
            fi
        fi
        AVAILABLE=$($BB awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
        if [ "$AVAILABLE" -lt 524288 ]; then echo 'Host available memory below 512 MiB'; break; fi
    elif [ "$N" -gt 30 ] && groups_empty; then
        echo 'Startup failed before init; groups empty'; exit 0
    elif [ "$N" -ge 75 ]; then
        echo 'Startup did not reach init within 75 seconds'; break
    fi
    $BB sleep 1
    N=$(($($BB date +%s) - START_SECONDS))
done
echo "Stopping Guest $ID at elapsed $N seconds"
if [ -f "$LOG/init.identity" ]; then
    [ "$(cat "$LOG/init.identity")" = "$(cat "$RUN/init.identity")" ] || exit 0
fi
SESSION=${SESSION:-}
IRIS_EXPECT_ATTEMPT="$LOG" IRIS_EXPECT_SESSION="$SESSION" \
    /system/bin/sh "$BASE/ctl.sh" stop "$ID"
