#!/system/bin/sh
# Host全屏协调：Guest producer产帧 → Host gateway认证/传连接 → Viewer显示并转发输入。
# 显示run另绑Guest PID:TICK；返回列表只清显示/输入助手，Guest继续运行。
# 同一时刻一个进入会话；列表预览走低频采样链，不是此处全屏帧流。
# One interactive display. Zero lifetime means no automatic expiry.
set -eu
ACTION=${1:-status}
ID=${2:-7}
. /data/irisbox/runtime/common.sh "$ID"
host_only || exit 3
D=$BASE/display/buffer_viewer
APP=com.irisbox.viewer
PROCESS=$APP:display
if [ "${IRIS_VIEWER_LOCKED:-}" != 1 ] && [ "$ACTION" != status ]; then
    exec "$BB" flock -n "$D/operation.lock" "$BB" env IRIS_VIEWER_LOCKED=1 IRIS_VIEWER_LOCK_OWNER= sh "$D/display.sh" "$@"
fi
OPERATION_FD=
if [ "${IRIS_VIEWER_LOCKED:-}" = 1 ] && [ -z "${IRIS_VIEWER_LOCK_OWNER:-}" ]; then export IRIS_VIEWER_LOCK_OWNER=$$; fi
if [ "${IRIS_VIEWER_LOCK_OWNER:-}" = "$$" ]; then
    for OPEN_FD in /proc/$$/fd/*; do
        if [ "$("$BB" readlink "$OPEN_FD" 2>/dev/null || true)" = "$D/operation.lock" ]; then OPERATION_FD=${OPEN_FD##*/}; fi
    done
    [ -n "$OPERATION_FD" ] || exit 3
fi
release_operation() { [ -z "$OPERATION_FD" ] || "$BB" flock -u "$OPERATION_FD"; }
trap 'release_operation' EXIT
checked_process() {
    [ -f "$1" ] || return 1
    read PP PT < "$1"
    case "$PP:$PT" in *[!0-9:]*|:*|*:) return 1 ;; esac
    alive "$PP" "$PT" || return 1
    "$BB" grep -aqF "$2" "/proc/$PP/cmdline" || return 1
}
current_run() {
    [ -f "$D/current" ] || return 1
    R=$(cat "$D/current")
    case "$R" in "$D"/runs/*) ;; *) exit 3 ;; esac
    [ "$("$BB" realpath "$R")" = "$R" ] || exit 3
    [ -d "$R" ] || return 1
}
# A failed recheck is harmless only when the exact process is no longer alive.
gone_or_refuse() { if alive "$PP" "$PT"; then return 3; fi; return 0; }
signal_bound_process() {
    if ! checked_process "$1" "$2"; then gone_or_refuse; return $?; fi
    kill -TERM "$PP" 2>/dev/null || { gone_or_refuse; return $?; }
    WAIT_N=0
    while [ "$WAIT_N" -lt 150 ]; do alive "$PP" "$PT" || break; "$BB" usleep 30000; WAIT_N=$((WAIT_N+1)); done
    if alive "$PP" "$PT"; then
        checked_process "$1" "$2" || { gone_or_refuse; return $?; }
        kill -KILL "$PP" 2>/dev/null || { gone_or_refuse; return $?; }
    fi
}
stop_display_process() {
    checked_process "$R/display.identity" "$PROCESS" || return 0
    [ "$("$BB" tr '\000' '\n' < /proc/$PP/cmdline | head -n 1)" = "$PROCESS" ] || { gone_or_refuse; return $?; }
    [ -f "$R/app.uid" ] || return 3
    [ "$("$BB" awk '/^Uid:/ {print $2}' /proc/$PP/status)" = "$(cat "$R/app.uid")" ] || { gone_or_refuse; return $?; }
    # Android APK storage mounts are private; these namespaces must be Host's.
    for NS in pid net ipc uts cgroup; do
        [ "$("$BB" readlink /proc/$PP/ns/$NS)" = "$("$BB" readlink /proc/1/ns/$NS)" ] || { gone_or_refuse; return $?; }
    done
    signal_bound_process "$R/display.identity" "$PROCESS"
}
stop_producer_process() {
    checked_process "$R/producer.identity" /iris_buffer_producer || return 0
    verify_init "$GI" "$GT" || return 3
    [ "$("$BB" readlink /proc/$PP/ns/pid)" = "$("$BB" readlink /proc/$GI/ns/pid)" ] || { gone_or_refuse; return $?; }
    signal_bound_process "$R/producer.identity" /iris_buffer_producer
}
# 回收绑定run+Guest身份，清接收端、producer/input，不跨会话误关。
stop_display() {
    current_run || return 0
    # Callers may bind cleanup to one run and Guest identity. Never reap a later run.
    [ -z "${IRIS_EXPECT_RUN:-}" ] || [ "$IRIS_EXPECT_RUN" = "$R" ] || return 3
    [ "$(cat "$R/instance")" = "$ID" ] || return 3
    read GI GT < "$R/init.identity"
    [ -z "${IRIS_EXPECT_SESSION:-}" ] || [ "$IRIS_EXPECT_SESSION" = "$GI:$GT" ] || return 3
    [ "$(cat "$R/state" 2>/dev/null || true)" != STOPPED ] || return 0
    stop_display_process || return $?
    if checked_process "$R/gateway.identity" com.irisbox.buffer.viewer.gateway.BufferGateway; then
        kill -TERM "$PP" 2>/dev/null || { gone_or_refuse || return $?; }
    fi
    stop_producer_process || return $?
    # An old session must never stop an input service belonging to a new Guest.
    if [ -f "$R/input.started" ] && verify_init "$GI" "$GT"; then
        INPUT_REL=/data/iris_stream/${GI}_${GT}
        INPUT_HOST=$RUN/data/iris_stream/${GI}_${GT}
        if [ -f "$R/input.identity" ]; then
            [ "$(cat "$R/input.identity")" = "$(sh "$BASE/ctl.sh" exec "$ID" cat "$INPUT_REL/input.identity")" ] || return 3
        fi
        IRIS_EXPECT_SESSION="$GI:$GT" "$BB" timeout 15 sh "$BASE/ctl.sh" exec "$ID" /system/bin/busybox timeout 12 \
            sh "$INPUT_REL/stop_input.sh" "$GI:$GT" >> "$R/input-stop.log" 2>&1 || return 7
    fi
    echo STOPPED > "$R/state"
    echo "BUFFER_DISPLAY_STOPPED id=$ID session=$GI:$GT; Guest kept running"
}
case "$ACTION" in
start)
    EXPECTED=${3:?Expected Guest PID:TICK}
    PROBE_DURATION=${4:-0}
    INSTANCE_NUMBER=${5:-}
    # Broker supplies the public ordinal. Direct diagnostic entry uses the same
    # numeric catalog order; neither path renames Guest data or reuses IDs.
    if [ -z "$INSTANCE_NUMBER" ]; then
        INSTANCE_NUMBER=$(for RECORD in "$BASE"/config/instances_v1/*.record; do
            [ -f "$RECORD" ] || continue
            RECORD_ID=${RECORD##*/}; echo "${RECORD_ID%.record}"
        done | "$BB" sort -n | "$BB" awk -v id="$ID" '$1==id {print NR}')
    fi
    case "$INSTANCE_NUMBER" in ''|*[!0-9]*) exit 2 ;; esac
    [ "$INSTANCE_NUMBER" -ge 1 ] && [ "$INSTANCE_NUMBER" -le 65529 ] || exit 2
    # Retain bounded diagnostic sessions; normal entry is explicitly unlimited.
    case "$PROBE_DURATION" in 0|60|120|180|300|900) ;; *) exit 2 ;; esac
    read PID TICK < "$RUN/init.identity"
    verify_init "$PID" "$TICK" || exit 3
    SESSION=$PID:$TICK
    [ "$EXPECTED" = "$SESSION" ] || exit 3
    if [ ! -f "$RUN/resolution.effective" ] || [ -L "$RUN/resolution.effective" ]; then
        "$BB" timeout 18 sh "$BASE/resolution.sh" check "$ID" "$SESSION"
    fi
    read GEOMETRY_SESSION PROFILE LW LH OW OH DPI EXTRA < "$RUN/resolution.effective"
    [ -z "${EXTRA:-}" ] && [ "$GEOMETRY_SESSION" = "$SESSION" ] || {
        "$BB" timeout 18 sh "$BASE/resolution.sh" check "$ID" "$SESSION"
        read GEOMETRY_SESSION PROFILE LW LH OW OH DPI EXTRA < "$RUN/resolution.effective"
    }
    [ -z "${EXTRA:-}" ] && [ "$GEOMETRY_SESSION" = "$SESSION" ] || exit 3
    case "$LW" in ''|*[!0-9]*) exit 3 ;; esac
    case "$LH" in ''|*[!0-9]*) exit 3 ;; esac
    case "$OW" in ''|*[!0-9]*) exit 3 ;; esac
    case "$OH" in ''|*[!0-9]*) exit 3 ;; esac
    case "$DPI" in ''|*[!0-9]*) exit 3 ;; esac
    if current_run; then
        [ "$(cat "$R/state" 2>/dev/null || true)" = STOPPED ] || { echo 'Display busy; close the current instance first' >&2; exit 4; }
    fi
    # A forgotten dedicated display process must not be reused across sessions.
    if ps -A -o NAME | "$BB" grep -qxF "$PROCESS"; then echo 'Display process remains; inspect its identity' >&2; exit 4; fi
    APP_UID=$(cmd package list packages -U "$APP" | "$BB" awk -v p="package:$APP" '$1==p {sub("uid:","",$2); print $2}')
    case "$APP_UID" in ''|*[!0-9]*) echo 'Viewer APK missing' >&2; exit 3 ;; esac
    [ "$APP_UID" -ge 10000 ] || exit 3
    STAMP=$(date +%Y%m%d_%H%M%S)_$$
    R=$D/runs/${STAMP}_${ID}
    umask 077
    mkdir -p "$D/runs"
    mkdir "$R"
    echo "$ID" > "$R/instance"
    echo "$PID $TICK" > "$R/init.identity"
    echo "$APP_UID" > "$R/app.uid"
    cp "$RUN/resolution.effective" "$R/resolution.effective"
    echo STARTING > "$R/state"
    TOKEN=$("$BB" od -An -N32 -tx1 /dev/urandom | "$BB" tr -d ' \n')
    [ "${#TOKEN}" = 64 ] || exit 3
    echo "$TOKEN" > "$R/token"
    NAME=irisbox_viewer_${ID}_${PID}_${STAMP}
    echo "$NAME" > "$R/gateway.name"
    echo "$R" > "$D/current"
    STARTED=0
    RES_CHECK_PID=
    trap 'CODE=$?; [ -z "$RES_CHECK_PID" ] || kill "$RES_CHECK_PID" 2>/dev/null || true; if [ "$STARTED" = 0 ]; then stop_display || true; fi; release_operation; exit "$CODE"' EXIT
    # 异步并行核验 Guest 实时 wm 尺寸与密度，将其耗时折叠进冷启动阶段。
    "$BB" timeout 18 sh "$BASE/resolution.sh" check "$ID" "$SESSION" > "$R/resolution-check.log" 2>&1 &
    RES_CHECK_PID=$!
    # G是Guest路径，GD是Host看到的同一私有data；每轮路径/token绑定会话。
    G=/data/iris_buffer/${PID}_${TICK}
    GD=$RUN/data/iris_buffer/${PID}_${TICK}
    INPUT_REL=/data/iris_stream/${PID}_${TICK}
    INPUT_HOST=$RUN/data/iris_stream/${PID}_${TICK}
    # 仅在 Host /proc 确认前序 GuestInputMain 仍存活时才进容器清理，省去常态无效 exec。
    NEED_INPUT_STOP=0
    if [ ! -L "$INPUT_HOST/input.identity" ] && [ -f "$INPUT_HOST/input.identity" ]; then
        OLD_IP=
        OLD_IT=
        read OLD_IP OLD_IT < "$INPUT_HOST/input.identity" 2>/dev/null || true
        case "$OLD_IP:$OLD_IT" in
            *[!0-9:]*|:*|*:) NEED_INPUT_STOP=1 ;;
            *)
                if alive "$OLD_IP" "$OLD_IT" && "$BB" grep -aqF "com.iris.stream.input.GuestInputMain $SESSION " "/proc/$OLD_IP/cmdline" 2>/dev/null; then
                    NEED_INPUT_STOP=1
                fi
                ;;
        esac
    fi
    if [ "$NEED_INPUT_STOP" = 1 ]; then
        IRIS_EXPECT_SESSION="$SESSION" "$BB" timeout 15 sh "$BASE/ctl.sh" exec "$ID" \
            /system/bin/busybox timeout 12 sh "$INPUT_REL/stop_input.sh" "$SESSION" > "$R/input-previous-stop.log" 2>&1
    fi
    "$BASE/irisbox_userns" stage-display "$ID" "$PID" "$TICK"
    echo 1 > "$R/input.started"
    VIEW_DURATION=0
    if [ "$PROBE_DURATION" -gt 0 ]; then
        VIEW_DURATION=$((PROBE_DURATION-16))
        [ "$VIEW_DURATION" -ge 5 ] || { echo 'Insufficient remaining display time' >&2; exit 7; }
    fi
    BROKER_TOKEN=$(cat "$D/broker.token")
    [ "${#BROKER_TOKEN}" = 64 ] && [ "$(printf '%s' "$BROKER_TOKEN" | "$BB" tr -d 'a-f0-9')" = '' ] || exit 3

    # 四路并行拉起：Guest 输入、Guest 帧生产者、Host 认证网关与 Host :display 界面同步冷启动。
    "$BB" nohup "$BB" env -u CLASSPATH IRIS_EXPECT_SESSION="$SESSION" sh "$BASE/ctl.sh" exec "$ID" \
        sh "$INPUT_REL/start_input.sh" "$SESSION" "$LW" "$LH" "$PROBE_DURATION" > "$R/input.log" 2>&1 < /dev/null &
    IP=$!
    echo "$IP $(ticks "$IP")" > "$R/input-wrapper.identity"

    "$BB" nohup "$BB" env -u CLASSPATH IRIS_EXPECT_SESSION="$SESSION" \
        sh "$BASE/ctl.sh" exec "$ID" "$G/iris_buffer_producer" "$ID" "$SESSION" "$G/output.sock" \
        --mode virtual --seconds "$PROBE_DURATION" --width "$OW" --height "$OH" > "$R/producer.log" 2>&1 < /dev/null &
    WP=$!
    echo "$WP $(ticks "$WP")" > "$R/wrapper.identity"

    "$BB" nohup "$BB" env CLASSPATH="$D/gateway.jar" /system/bin/app_process /system/bin \
        com.irisbox.buffer.viewer.gateway.BufferGateway "$ID" "$SESSION" "$NAME" "$R/token" "$APP_UID" "$PROBE_DURATION" "$LW" "$LH" \
        > "$R/gateway.log" 2>&1 < /dev/null &
    GP=$!
    echo "$GP $(ticks "$GP")" > "$R/gateway.identity"

    # am may echo Intent extras; never expose its output or persist bearer tokens.
    am start --activity-no-animation -n "$APP/.DisplayActivity" --es gatewayName "$NAME" --es gatewayToken "$TOKEN" --es brokerToken "$BROKER_TOKEN" \
        --ei instanceId "$ID" --ei instanceNumber "$INSTANCE_NUMBER" --es session "$SESSION" --ei seconds "$VIEW_DURATION" --ei inputWidth "$LW" --ei inputHeight "$LH" >/dev/null 2>&1

    INPUT_DONE=0
    PRODUCER_DONE=0
    GATEWAY_DONE=0
    DISPLAY_DONE=0
    GUEST_PID_NS=$("$BB" readlink "/proc/$PID/ns/pid")
    STEP=0
    while [ "$STEP" -lt 300 ]; do
        if [ "$INPUT_DONE" = 0 ]; then
            if [ -S "$INPUT_HOST/input.sock" ] && [ -f "$INPUT_HOST/input.ready" ] && [ -f "$INPUT_HOST/input.identity" ]; then
                INPUT_DONE=1
            elif ! kill -0 "$IP" 2>/dev/null; then
                break
            fi
        fi
        if [ "$PRODUCER_DONE" = 0 ]; then
            if [ -S "$GD/output.sock" ] && "$BB" grep -q LISTEN "$R/producer.log" 2>/dev/null; then
                FOUND=0
                for CHILD in $(ps -A -o PID,PPID | "$BB" awk -v parent="$WP" '$2==parent {print $1}'); do
                    if "$BB" grep -aqF "$G/iris_buffer_producer" "/proc/$CHILD/cmdline" 2>/dev/null && \
                       [ "$("$BB" readlink "/proc/$CHILD/ns/pid" 2>/dev/null || true)" = "$GUEST_PID_NS" ]; then
                        echo "$CHILD $(ticks "$CHILD")" > "$R/producer.identity"
                        FOUND=$((FOUND+1))
                    fi
                done
                if [ "$FOUND" = 1 ]; then PRODUCER_DONE=1; fi
            elif ! kill -0 "$WP" 2>/dev/null; then
                break
            fi
        fi
        if [ "$GATEWAY_DONE" = 0 ]; then
            if "$BB" grep -q GATEWAY_READY "$R/gateway.log" 2>/dev/null; then
                GATEWAY_DONE=1
            elif ! kill -0 "$GP" 2>/dev/null; then
                break
            fi
        fi
        if [ ! -f "$R/display.identity" ]; then
            for DP in $(pidof "$PROCESS" 2>/dev/null || true); do
                [ "$("$BB" tr '\000' '\n' < /proc/$DP/cmdline | head -n 1)" = "$PROCESS" ] || continue
                [ "$("$BB" awk '/^Uid:/ {print $2}' /proc/$DP/status)" = "$APP_UID" ] || continue
                HOST_APP=1
                for NS in pid net ipc uts cgroup; do
                    [ "$("$BB" readlink /proc/$DP/ns/$NS)" = "$("$BB" readlink /proc/1/ns/$NS)" ] || HOST_APP=0
                done
                [ "$HOST_APP" = 1 ] || continue
                DT=$(ticks "$DP")
                [ -n "$DT" ] || continue
                echo "$DP $DT" > "$R/display.identity"
            done
        fi
        if [ "$DISPLAY_DONE" = 0 ]; then
            if checked_process "$R/display.identity" "$PROCESS" && "$BB" grep -q GATEWAY_HANDED_OFF "$R/gateway.log" 2>/dev/null; then
                DISPLAY_DONE=1
            fi
        fi
        if [ "$INPUT_DONE" = 1 ] && [ "$PRODUCER_DONE" = 1 ] && [ "$GATEWAY_DONE" = 1 ] && [ "$DISPLAY_DONE" = 1 ]; then
            break
        fi
        "$BB" usleep 30000
        STEP=$((STEP+1))
    done
    [ "$INPUT_DONE" = 1 ] || { echo 'Input did not start' >&2; exit 7; }
    NEW_IP=; NEW_IT=
    if [ ! -L "$INPUT_HOST/input.identity" ] && [ -f "$INPUT_HOST/input.identity" ]; then
        read NEW_IP NEW_IT < "$INPUT_HOST/input.identity" 2>/dev/null || true
    fi
    case "$NEW_IP:$NEW_IT" in
        *[!0-9:]*|:*|*:) sh "$BASE/ctl.sh" exec "$ID" cat "$INPUT_REL/input.identity" > "$R/input.identity" ;;
        *) printf '%s %s\n' "$NEW_IP" "$NEW_IT" > "$R/input.identity" ;;
    esac
    [ "$PRODUCER_DONE" = 1 ] && [ -S "$GD/output.sock" ] || { echo 'Producer did not start' >&2; exit 7; }
    [ "$GATEWAY_DONE" = 1 ] || { echo 'Gateway did not start' >&2; exit 7; }
    [ "$DISPLAY_DONE" = 1 ] || { echo 'Display did not take both connections' >&2; exit 7; }
    wait "$RES_CHECK_PID" || { echo 'Resolution verification failed' >&2; exit 7; }
    RES_CHECK_PID=
    echo ACTIVE > "$R/state"
    STARTED=1
    echo "BUFFER_DISPLAY_STARTED id=$ID session=$SESSION producerSeconds=$PROBE_DURATION viewerSeconds=$VIEW_DURATION logs=$R"
    ;;
stop) stop_display ;;
status)
    current_run || { echo 'No buffer display'; exit 0; }
    echo "BUFFER_DISPLAY_LOGS=$R"
    cat "$R/instance" "$R/init.identity" "$R/state"
    if checked_process "$R/display.identity" "$PROCESS"; then echo "DISPLAY_ALIVE=$PP:$PT"; else echo DISPLAY_STOPPED; fi
    if checked_process "$R/producer.identity" /iris_buffer_producer; then echo "PRODUCER_ALIVE=$PP:$PT"; else echo PRODUCER_STOPPED; fi
    ;;
*) echo 'Use start ID PID:TICK [0|60|120|180|300|900] [display-number], stop ID, status (0 means no expiry)'; exit 2 ;;
esac
