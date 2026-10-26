#!/system/bin/sh
# ==============================================================================
# Host统一控制总线：提供 start / stop / exec / status 四大生命周期操作。
# 【设计与架构原则】：
# 1. 统一调度：BufferBroker 或外部管理接口统一调用此脚本，绝不手动逐个拉起 Android 服务。
# 2. 身份严核：start 成功仅代表已通过内核与真实 Namespace 验证 Guest init 身份；
#    Android 系统的 boot_completed、桌面渲染就绪等业务状态由上层另行核验。
# 3. 轮次绑定：每轮启动创建独立的 ATTEMPT 时间戳目录与日志，不复用旧日志，避免状态混淆。
# 4. 数据解耦：$RUN 路径指向该实例私有数据树，启停只回收进程与网络，不破坏应用与数据层。
# ==============================================================================
set -eu

# 提取操作指令，默认动作为 status 查询
ACTION=${1:-status}
shift || true

# 导入公共定义（传入实例 ID，默认为 7），$BASE、$RUN、$BB 等变量在此被声明
. /data/irisbox/runtime/common.sh "${1:-7}"
[ $# -eq 0 ] || shift

# 环境安全自检：必须在宿主环境（Host 6大Namespace与Root权限）下执行
host_only || exit 3

# start 操作前的前置防呆校验
if [ "$ACTION" = start ]; then
    # 实例必须已在 config 中完成注册（存在 record 记录）
    registered_instance || { echo 'Instance was deleted or has not been created' >&2; exit 3; }
    # 若有未完成的异步重置或删除任务，禁止直接点火
    [ ! -e "$RUN/cleanup.pending" ] && [ ! -L "$RUN/cleanup.pending" ] || { echo 'Finish the pending reset or delete first' >&2; exit 4; }
fi

# ------------------------------------------------------------------------------
# 【生命周期排他锁：lifecycle.lock】
# start 和 stop 会修改实例关键运行状态，必须使用文件锁严格串行化，防止并发启停导致死锁或状态撕裂。
# 采用“获取锁后以标记重入执行本脚本”的执行模式。
# ------------------------------------------------------------------------------
CTL_LOCK_FD=
case "$ACTION" in start|stop)
    $BB mkdir -p "$RUN"
    if [ "${IRIS_CTL_LOCKED:-}" != 1 ]; then
        # 获取排他锁（超时 75 秒），拿到锁后设置 IRIS_CTL_LOCKED=1 重入当前脚本
        exec "$BB" timeout -s TERM 75 "$BB" flock -x "$RUN/lifecycle.lock" \
            "$BB" env IRIS_CTL_LOCKED=1 /system/bin/sh "$BASE/ctl.sh" "$ACTION" "$ID" "$@"
    fi
    # 检索当前进程持有 lifecycle.lock 的具体文件描述符（FD）编号
    for F in /proc/$$/fd/*; do
        [ "$("$BB" readlink "$F" 2>/dev/null || true)" != "$RUN/lifecycle.lock" ] || CTL_LOCK_FD=${F##*/}
    done
    case "$CTL_LOCK_FD" in ''|*[!0-9]*) exit 3 ;; esac

    # 若指定了特定的启动轮次（ATTEMPT），核验当前实例最新运行目录是否与之吻合
    if [ -n "${IRIS_EXPECT_ATTEMPT:-}" ]; then
        [ "$(cat "$RUN/latest.logdir")" = "$IRIS_EXPECT_ATTEMPT" ] || exit 3
    fi
    ;;
esac

# ------------------------------------------------------------------------------
# 【辅助函数：close_child_lock】
# 在 fork 出的后台长驻进程（如 watchdog、wrapper）中主动关闭继承的锁 FD。
# 若不关闭，子进程会持续持有该 FD，导致外部控制器即使退出也永远无法释放该排他锁。
# ------------------------------------------------------------------------------
close_child_lock() {
    case "$CTL_LOCK_FD" in ''|*[!0-9]*) return 3 ;; esac
    eval "exec $CTL_LOCK_FD>&-"
    unset IRIS_CTL_LOCKED
}

# ------------------------------------------------------------------------------
# 【辅助函数：expect_session】
# 会话防呆校验：校验当前实例的 PID:TICK 是否与外部请求中携带的 EXPECT_SESSION 一致。
# 避免客户端拿着旧会话的请求，去误操作用户后来重新启动的新实例。
# ------------------------------------------------------------------------------
expect_session() {
    [ -z "${IRIS_EXPECT_SESSION:-}" ] || [ "$PID:$TICK" = "$IRIS_EXPECT_SESSION" ] || {
        echo 'Instance session changed: no action performed' >&2
        return 3
    }
}

# ------------------------------------------------------------------------------
# 【辅助函数：cleanup_network】
# 清理本会话分配的虚拟网络环境（调用 network.sh 回收 veth 虚拟网卡、路由规则及网关会话）
# ------------------------------------------------------------------------------
cleanup_network() {
    [ -f "$RUN/network.current" ] || return 0
    read NETWORK_SESSION NETWORK_STATE < "$RUN/network.current"
    [ -z "${IRIS_EXPECT_SESSION:-}" ] || [ "$NETWORK_SESSION" = "$IRIS_EXPECT_SESSION" ] || return 3
    NETWORK_LOG=${NETWORK_STATE%/network}
    /system/bin/sh "$BASE/network.sh" cleanup "$ID" "$NETWORK_SESSION" "$NETWORK_LOG"
}

# ------------------------------------------------------------------------------
# 【辅助函数：stop_preinit_attempt】
# 异常点火兜底回收：用于在 init 正式点火前（wrapper 早期阶段）发生卡死或崩溃时的强制清理。
# 利用 cgroup v2 的 cgroup.kill 将组内所有残留半生进程一次性全部终结。
# ------------------------------------------------------------------------------
stop_preinit_attempt() {
    [ -n "${IRIS_EXPECT_ATTEMPT:-}" ] || return 3
    [ "$(cat "$RUN/latest.logdir")" = "$IRIS_EXPECT_ATTEMPT" ] || return 3
    if [ -f "$IRIS_EXPECT_ATTEMPT/network.request" ]; then
        REQUEST_SESSION=$(cat "$IRIS_EXPECT_ATTEMPT/network.request")
        [ -z "${IRIS_EXPECT_SESSION:-}" ] || [ "$REQUEST_SESSION" = "$IRIS_EXPECT_SESSION" ] || return 3
    fi
    [ ! -L "$PIDS" ] && [ -f "$PIDS/cgroup.kill" ] || return 7
    [ "$(cat "$PIDS/pids.max")" = "$MAX_TASKS" ] || return 7
    # 向 cgroup.kill 写入 1，内核会自动向组内全部进程发送 SIGKILL
    echo 1 > "$PIDS/cgroup.kill"
    for WAIT in 1 2 3 4 5; do groups_empty && break; $BB sleep 1; done
    groups_empty || return 7
    cleanup_network
}

# ------------------------------------------------------------------------------
# 【核心函数：stop_guest】
# 优雅停止虚拟机实例：
# 1. 严格核验目标 PID:TICK 与真实空间，绝不盲目发信号；
# 2. 先发送 SIGTERM（优雅退出），等待 10 秒；
# 3. 超时未退出则复核身份并升级为 SIGKILL（强制杀死）；
# 4. 确认所有 cgroup 任务归零并清理网络，保留可写层数据与日志。
# ------------------------------------------------------------------------------
stop_guest() {
    # 若无身份记录，检查是否为残留异常或已经完全停止
    if [ ! -f "$RUN/init.identity" ]; then
        if groups_empty; then cleanup_network; else stop_preinit_attempt; fi
        return
    fi
    read PID TICK < "$RUN/init.identity"
    expect_session || return 3

    # 若进程已不在，确认 cgroup 清空后直接清理网络返回
    if ! alive "$PID" "$TICK"; then groups_empty || return 7; cleanup_network; return; fi

    # 严核 init 真实性（PID 1、命名空间、OverlayFS、cgroup 归属）
    verify_init "$PID" "$TICK" || {
        stop_preinit_attempt || { echo 'Live identity mismatch: no signal sent' >&2; return 3; }
        return 0
    }

    # 第一阶段：发送 SIGTERM 优雅退出信号
    kill -TERM "$PID"
    for N in 1 2 3 4 5 6 7 8 9 10; do
        alive "$PID" "$TICK" || break
        $BB sleep 1
    done

    # 第二阶段：若仍未退出，复核身份后升级为 SIGKILL 强杀
    if alive "$PID" "$TICK"; then
        verify_init "$PID" "$TICK" || return 3
        kill -KILL "$PID"
    fi

    # 轮询 5 秒等待 cgroup 彻底归零，绝不假装成功
    for N in 1 2 3 4 5; do groups_empty && break; $BB sleep 1; done
    groups_empty || { echo 'Instance still has cgroup members' >&2; return 7; }
    cleanup_network
    echo "Guest $ID stopped; data and logs preserved"
}

# ------------------------------------------------------------------------------
# 【核心命令路由处理】
# ------------------------------------------------------------------------------
case "$ACTION" in
    stop)
        stop_guest
        ;;

    status)
        # 查询运行状态与资源统计指标
        if [ -f "$RUN/init.identity" ]; then
            read PID TICK < "$RUN/init.identity"
            if verify_init "$PID" "$TICK"; then echo "Guest $ID running: Host PID $PID / Guest PID 1"; fi
        fi
        # 输出 cgroup 内存使用量、峰值、OOM失败次数与任务数事件
        for F in "$MEM/memory.usage_in_bytes" "$MEM/memory.max_usage_in_bytes" "$MEM/memory.failcnt" \
            "$PIDS/pids.current" "$PIDS/pids.events" "$PIDS/managed/pids.events"; do
            [ ! -f "$F" ] || { echo "$F"; cat "$F"; }
        done
        ;;

    exec)
        # 容器注入执行命令（类似 docker exec 或 adb shell）
        read PID TICK < "$RUN/init.identity"
        expect_session || exit 3
        verify_init "$PID" "$TICK" || exit 3
        [ $# -gt 0 ] || set -- /system/bin/sh
        # 先加入该实例的 cgroup 资源组，避免注入的调试进程脱离配额
        join_groups
        # 使用 irisbox_ns 工具切换进入该实例的 6 大命名空间执行命令
        exec "$BASE/irisbox_ns" enter "$PID" "$TICK" -- "$@"
        ;;

    start)
        # 再次复核注册状态与未决清理任务（避免在排队拿锁期间实例已被删除）
        registered_instance || exit 3
        [ ! -e "$RUN/cleanup.pending" ] && [ ! -L "$RUN/cleanup.pending" ] || { echo 'Finish the pending reset or delete first' >&2; exit 4; }

        [ ! -e "$RUN/userns.pending" ] && [ ! -L "$RUN/userns.pending" ] || exit 4
        "$BASE/irisbox_userns" profile "$ID" >/dev/null

        # 获取运行超时期限（0 代表永不自动超时，或 180/300/600/1800 秒）
        DEADLINE=${1:-0}
        case "$DEADLINE" in 0|180|300|600|1800) ;; *) echo 'Deadline must be 0 (no expiry), 180, 300, 600 or 1800 seconds' >&2; exit 2 ;; esac

        # 检查是否已存在存活进程或 cgroup 非空
        if [ -f "$RUN/init.identity" ]; then
            read PID TICK < "$RUN/init.identity"
            if alive "$PID" "$TICK"; then echo 'Instance already has a live process' >&2; exit 4; fi
        fi
        groups_empty || exit 4
        # 宿主重启/异常退出会留下SSD上的网络账本；任务组确认空后，
        # 沿旧会话的精确回收入口处理，再创建新attempt。失败则停止，不覆盖旧归属。
        cleanup_network || { echo 'Previous network cleanup failed; records preserved' >&2; exit 7; }

        # 检查宿主可用物理内存（至少需 1280 MiB 可用空间，防止宿主 OOM 崩溃）
        load_memory_limit
        AVAILABLE=$($BB awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
        REQUIRED=$(((MEMORY_MIB + 256) * 1024))
        [ "$REQUIRED" -ge 786432 ] || REQUIRED=786432
        [ "$AVAILABLE" -ge "$REQUIRED" ] || { echo "Insufficient free memory: need ${REQUIRED} KiB; existing instances preserved" >&2; exit 4; }

        # 核验 ROM 底包与关键 ELF 二进制完整性
        for F in system.img vendor.img; do [ -f "$ROM/$F" ] || exit 3; done
        [ -x "$BASE/irisbox_ns" ] && [ -x "$COMPAT/iris_init" ] || exit 3

        # 创建启动防重入目录锁
        $BB mkdir -p "$RUN"
        $BB mkdir "$RUN/start.lock" || exit 4
        trap '$BB rmdir "$RUN/start.lock" 2>/dev/null || true' EXIT

        # 准备 ROM 镜像挂载与 cgroup 资源树
        print -r -- "[启动阶段] uptime=$($BB awk '{print $1}' /proc/uptime) 开始准备ROM和资源组"
        /system/bin/sh "$BASE/prepare_rom.sh" "$ID"
        /system/bin/sh "$BASE/prepare_groups.sh" "$ID"
        print -r -- "[启动阶段] uptime=$($BB awk '{print $1}' /proc/uptime) ROM和资源组准备完成"

        # 生成本轮点火唯一时间戳，创建独立日志目录并固化当前配置快照
        ATTEMPT=$($BB date +%Y%m%d_%H%M%S)
        LOG=$RUN/$ATTEMPT
        $BB mkdir "$LOG"
        sh "$BASE/resolution.sh" get "$ID" > "$LOG/resolution.profile"
        echo "$MEMORY_MIB" > "$LOG/memory.limit.mib"
        cat "$PIDS/managed/pids.events" > "$LOG/pids.events.before"
        echo "$LOG" > "$RUN/latest.logdir"
        $BB rm -f "$RUN/init.identity"

        # 启动后台看门狗（watchdog.sh）：在子进程中关闭继承的锁 FD，轮询等待就绪
        ( close_child_lock; exec $BB nohup /system/bin/sh "$BASE/watchdog.sh" "$ID" "$DEADLINE" "$LOG" ) > "$LOG/watchdog.log" 2>&1 < /dev/null &
        WPID=$!
        for N in 1 2 3 4 5; do [ ! -f "$LOG/watchdog.ready" ] || break; $BB sleep 1; done
        [ -f "$LOG/watchdog.ready" ] || exit 3

        # 启动后台点火包装器（wrapper.sh）：同样关闭继承的锁 FD，记录 wrapper 身份
        ( close_child_lock; exec $BB nohup /system/bin/sh "$BASE/wrapper.sh" "$ID" "$LOG" ) > "$LOG/init.log" 2>&1 < /dev/null &
        PARENT=$!
        echo "$PARENT $(ticks "$PARENT")" > "$LOG/wrapper.identity"

        # 轮询最多 45 秒：等待 Guest init 生成身份并完成内核级严格验证
        for N in $($BB seq 1 45); do
            if [ -f "$RUN/init.identity" ]; then
                read PID TICK < "$RUN/init.identity"
                if verify_init "$PID" "$TICK"; then
                    if [ "$DEADLINE" = 0 ]; then
                        echo "Guest $ID started: Host PID $PID / Guest PID 1; no automatic expiry"
                    else
                        echo "Guest $ID started: Host PID $PID / Guest PID 1; deadline $DEADLINE seconds"
                    fi
                    echo "Logs: $LOG"
                    echo "Memory ceiling: $MEMORY_MIB MiB; Host resource guard remains enabled"
                    exit 0
                fi
            fi
            kill -0 "$PARENT" 2>/dev/null || break
            $BB sleep 1
        done

        echo "Startup did not reach verified init; watchdog retained; see $LOG/init.log" >&2
        exit 7
        ;;

    *)
        echo 'Use start, stop, exec or status' >&2; exit 2
        ;;
esac
