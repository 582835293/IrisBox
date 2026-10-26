#!/system/bin/sh
# ==============================================================================
# Host管理入口：负责启动/停止BufferBroker守护进程、唤起查看器App、清理旧版显示助手。
# 【设计原则】：
# 1. 仅负责宿主侧的前台展示与管理服务，操作它绝不会点火或关闭Guest虚拟机本身。
# 2. App（界面查看器）与 Broker（后台缓冲区代理）解耦运行；排查故障先核验进程凭据，不重置实例。
# ==============================================================================
set -eu

# 虚拟机根目录与公共环境初始化
BASE=/data/irisbox/runtime
. "$BASE/common.sh" 7

# 环境自检：必须在宿主环境（Host 6大Namespace与Root权限）下执行
host_only || exit 3

# Buffer 查看器的工作目录与默认动作
D=$BASE/display/buffer_viewer
ACTION=${1:-status}

# ------------------------------------------------------------------------------
# 【文件锁互斥机制】
# 防止多个终端或外部调用并发执行 start / stop 导致状态撕裂。
# status 和 open 是只读/拉起界面的轻量操作，无需加排他锁。
# ------------------------------------------------------------------------------
if [ "${IRIS_BUFFER_MANAGER_LOCKED:-}" != 1 ] && [ "$ACTION" != status ] && [ "$ACTION" != open ]; then
    # 使用 busybox flock 获取非阻塞独占锁，持有锁后以标记环境变量重入执行本脚本
    exec "$BB" flock -n "$D/manager.lock" "$BB" env IRIS_BUFFER_MANAGER_LOCKED=1 sh "$D/manager.sh" "$@"
fi

# 记录当前进程持有锁的文件描述符（FD），以便退出时主动释放
MANAGER_FD=
if [ "${IRIS_BUFFER_MANAGER_LOCKED:-}" = 1 ]; then
    for F in /proc/$$/fd/*; do
        [ "$("$BB" readlink "$F" 2>/dev/null || true)" != "$D/manager.lock" ] || MANAGER_FD=${F##*/}
    done
    [ -n "$MANAGER_FD" ] || exit 3
fi

# 退出清理钩子：无论脚本正常结束或异常退出，都确保释放文件锁
release_lock(){ [ -z "$MANAGER_FD" ] || "$BB" flock -u "$MANAGER_FD"; }
trap 'release_lock' EXIT

# ------------------------------------------------------------------------------
# 【辅助函数：broker_alive】
# 检查 BufferBroker 守护进程是否存活并严格核验其身份（防 PID 复用与逃逸）
# ------------------------------------------------------------------------------
broker_alive() {
    [ -f "$D/broker.identity" ] || return 1
    read BP BT < "$D/broker.identity"
    # 结合 PID 与进程启动 Tick 校验，防止因 PID 循环复用而把其他进程误判为 Broker
    alive "$BP" "$BT" || return 1
    # 核验进程命令行确实是 BufferBroker
    "$BB" grep -aq 'com.irisbox.buffer.viewer.broker.BufferBroker' "/proc/$BP/cmdline" || return 1
    # 核验 6 个命名空间与宿主 1 号进程完全一致，确保该进程始终运行在 Host 侧
    for N in pid mnt net ipc uts cgroup; do
        [ "$("$BB" readlink /proc/$BP/ns/$N)" = "$("$BB" readlink /proc/1/ns/$N)" ] || return 1
    done
}

# ------------------------------------------------------------------------------
# 【辅助函数：stop_current_display】
# 停止当前活动的屏幕缓冲区捕获/显示辅助会话
# ------------------------------------------------------------------------------
stop_current_display() {
    [ -f "$D/current" ] || return 0
    CURRENT=$(cat "$D/current")
    case "$CURRENT" in "$D"/runs/*) ;; *) return 3 ;; esac
    CURRENT_ID=$(cat "$CURRENT/instance")
    read CURRENT_PID CURRENT_TICK < "$CURRENT/init.identity"
    # 附带期望的会话特征调用 display.sh 停止特定实例的显示管线
    IRIS_EXPECT_RUN="$CURRENT" IRIS_EXPECT_SESSION="$CURRENT_PID:$CURRENT_TICK" \
        sh "$D/display.sh" stop "$CURRENT_ID"
}

# ------------------------------------------------------------------------------
# 【核心命令分支处理】
# ------------------------------------------------------------------------------
case "$ACTION" in
start)
    # 确保实例配置及运行目录准备就绪
    sh "$BASE/instances.sh" init
    if broker_alive; then echo "Buffer manager already running: $BP:$BT"; exit 0; fi

    stop_current_display

    # 通过 Android 原生 Java 运行时（app_process）在后台静默启动 BufferBroker 服务
    "$BB" nohup "$BB" env CLASSPATH="$D/broker.jar" /system/bin/app_process /system/bin \
        com.irisbox.buffer.viewer.broker.BufferBroker > "$D/broker.log" 2>&1 < /dev/null &
    BP=$!
    # 记录该进程的 PID 及其启动 Tick，作为唯一的不可伪造身份凭据
    echo "$BP $(ticks "$BP")" > "$D/broker.identity"

    # 循环轮询最多 5 秒，等待 Broker 完成初始化并输出就绪标志
    for N in 1 2 3 4 5; do
        if broker_alive && grep -q 'Iris buffer broker ready' "$D/broker.log"; then
            echo "Buffer manager started: $BP:$BT; Guests unchanged"; exit 0
        fi
        "$BB" sleep 1
    done
    echo 'Buffer manager did not start; see broker.log' >&2; exit 7
    ;;

open)
    # 打开宿主上的 Android 前台查看器 App
    broker_alive || { echo 'Start manager first' >&2; exit 3; }
    TOKEN=$(cat "$D/broker.token")
    [ "${#TOKEN}" = 64 ] || exit 3
    # 通过 ActivityManager 携带认证 Token 与通信端口拉起实例列表页面
    am start -n com.irisbox.viewer/.ListActivity --es brokerToken "$TOKEN" --ei brokerPort 19762 >/dev/null 2>&1
    echo 'Buffer instance list opened'
    ;;

stop)
    # 停止 Broker 进程：先优雅 SIGTERM，超时仍未退出再强制 SIGKILL
    if broker_alive; then
        kill -TERM "$BP"
        for N in 1 2 3 4 5 6 7 8; do alive "$BP" "$BT" || break; "$BB" sleep 1; done
        if alive "$BP" "$BT"; then broker_alive || exit 3; kill -KILL "$BP"; fi
    fi
    stop_current_display
    echo 'Buffer manager and its checked display/input helpers stopped; Guests preserved'
    ;;

status)
    # 查看 Broker 守护进程与显示辅助管线的运行状态
    if broker_alive; then echo "Buffer manager running: $BP:$BT"; else echo 'Buffer manager not running'; fi
    sh "$D/display.sh" status
    ;;

*)
    echo 'Use start, open, stop or status' >&2; exit 2
    ;;
esac
