#!/system/bin/sh
# ==============================================================================
# 公共定义与环境基座：Host 脚本通过“.”（source）载入的共享库，非独立点火脚本。
# 【设计与架构原则】：
# 1. 实例标识：ID 为不可变的内部数字编号（例如 7），非界面展示名；$RUN 指向其私有运行时数据树。
# 2. 调用关系：ctl / 配置脚本 → 载入本脚本做公共身份与资源核验 → 执行各自操作；共享规则集中于此维护。
# 3. 共享实例定义，此处不包含具体单个 Android 服务的启动逻辑。
# ==============================================================================

# 基础执行路径与 ROM 底包定义
BB=/data/rootvmos/host/bin/busybox
BASE=/data/rootvmos/runtime
# 每个实例锁定 ROM ID；导入另一个 ROM 不改变已有实例。
ROM_ID=
ROM_RECORD=$BASE/config/rom_v1/${1:-7}.id
if [ -f "$ROM_RECORD" ] && [ ! -L "$ROM_RECORD" ]; then ROM_ID=$(cat "$ROM_RECORD")
elif [ -f "$BASE/config/default_rom" ] && [ ! -L "$BASE/config/default_rom" ]; then ROM_ID=$(cat "$BASE/config/default_rom"); fi
case "$ROM_ID" in ''|*[!a-z0-9._-]*) ROM_ID=unselected ;; esac
[ "${#ROM_ID}" -le 64 ] || exit 3
ROM=$BASE/roms/$ROM_ID
COMPAT=$ROM/compat

# 默认实例编号（若未传入参数则默认为 7）
ID=${1:-7}

# ------------------------------------------------------------------------------
# 【函数：valid_instance_id】
# 7仅供安装/配置自检使用；实际登记的产品实例保留1007～1134。
# ------------------------------------------------------------------------------
valid_instance_id() {
    case "$1" in ''|*[!0-9]*|0*) return 2 ;; esac
    [ "$1" = 7 ] || { [ "${#1}" -eq 4 ] && [ "$1" -ge 1007 ] && [ "$1" -le 1134 ]; }
}
valid_instance_id "$ID" || { echo 'Invalid instance ID' >&2; exit 2; }

# 该实例私有运行时数据根目录（挂载点、日志、配置锁等均位于此处）
RUN=$BASE/instances/$ID

# ------------------------------------------------------------------------------
# 【函数：registered_instance】
# 检查实例是否有效注册：严格防范符号链接攻击，必须存在非符号链接的 .record 文件
# ------------------------------------------------------------------------------
registered_instance() {
    [ ! -L "$BASE/config" ] && [ ! -L "$BASE/config/instances_v1" ] &&
        [ ! -L "$BASE/config/instances_v1/$ID.record" ] && [ -f "$BASE/config/instances_v1/$ID.record" ]
}

# ------------------------------------------------------------------------------
# 【cgroup 资源限制配置（混合模式 Hybrid）】
# 本设备混用 v1 memory 和 v2 pids 接口，路径与监控指标不可当成纯 v2 处理。
# 内存上限是 cgroup 预算限制，不是预先划走物理内存，宿主（Host）与 GPU 仍需保留足够运行余量。
# ------------------------------------------------------------------------------
MEM=/dev/memcg/rootvmos_$ID
PIDS=/sys/fs/cgroup/rootvmos_$ID
MEM_BYTES=1342177280      # 默认 1280 MiB 对应的字节数
MEM_DEFAULT_MIB=1280     # 默认内存配额（MiB）
MEM_MIN_MIB=512          # 允许配置的最小内存（MiB）
MEM_MAX_MIB=2560         # 允许配置的最大内存（MiB）
MAX_TASKS=1536           # 普通实例最大并发任务/线程数上限

# 首次登录 Google 账号同步和 Google Play 会创建大量正常的进程与线程。
# 此处设置一个有限上限（3072）；未启用 Google 核心套件的普通 Guest 保留较小的初始配额（1536）。
[ ! -f "$RUN/google.core.enabled" ] || MAX_TASKS=3072

# ------------------------------------------------------------------------------
# 【内存配置读写与校验函数】
# ------------------------------------------------------------------------------
# 校验内存数值范围：纯数字且介于 512 ~ 2560 MiB 之间
valid_memory_mib() {
    case "$1" in ''|*[!0-9]*|0*) return 2 ;; esac
    [ "${#1}" -le 4 ] && [ "$1" -ge "$MEM_MIN_MIB" ] && [ "$1" -le "$MEM_MAX_MIB" ]
}

# 读取保存的内存配置：严格防御符号链接，防止路径穿越
saved_memory_mib() {
    MEMORY_MIB=$MEM_DEFAULT_MIB
    MEMORY_CONFIG=$BASE/config/memory_v1
    [ ! -L "$BASE/config" ] && [ ! -L "$MEMORY_CONFIG" ] && [ ! -L "$MEMORY_CONFIG/$ID.mib" ] || return 3
    if [ -e "$MEMORY_CONFIG/$ID.mib" ]; then
        [ -f "$MEMORY_CONFIG/$ID.mib" ] && [ "$("$BB" wc -c < "$MEMORY_CONFIG/$ID.mib")" -le 8 ] || return 3
        MEMORY_MIB=$(cat "$MEMORY_CONFIG/$ID.mib")
    fi
    valid_memory_mib "$MEMORY_MIB"
}

# 加载并计算字节数限制：使用 busybox awk 做浮点换算，防止 32 位 Shell 整数运算溢出变负数
load_memory_limit() {
    saved_memory_mib || { echo 'Invalid memory setting; restore the default before starting' >&2; return 3; }
    # 宿主 sh 在此处是 32 位算术运算；2.5 GiB 计算必须避免溢出成为负数。
    MEM_BYTES=$("$BB" awk -v mib="$MEMORY_MIB" 'BEGIN {printf "%.0f", mib * 1048576}')
}

# ------------------------------------------------------------------------------
# 【进程身份与唯一生命周期识别】
# Linux 内核中 PID 会循环复用。仅凭 PID 无法确认是否为同一进程，
# 必须抓取 /proc/PID/stat 的第 22 个字段（start tick，进程启动时钟滴答数）作为唯一防复用凭据。
# ------------------------------------------------------------------------------
ticks() { $BB awk '{print $22}' "/proc/$1/stat"; }

# 判断指定 PID 与启动 tick 的进程是否存活（单次快照，排除僵尸 Z 与死进程 X；不存在或不可读绝不误判为活）
alive() {
    [ "$($BB awk -v expected="$2" 'NF >= 22 && $22 == expected && $3 != "Z" && $3 != "X" {print "alive"}' "/proc/$1/stat" 2>/dev/null)" = alive ]
}

# ------------------------------------------------------------------------------
# 【函数：host_only】
# 宿主全局入口安全检查：
# 1. 必须是 root（UID 0）；
# 2. 当前进程的 6 个命名空间（pid, mnt, net, ipc, uts, cgroup）必须与宿主 1 号进程完全一致。
# 确保绝对是在宿主真机环境执行，防止在子容器中误操作发生套娃点火。
# ------------------------------------------------------------------------------
host_only() {
    [ "$(id -u)" = 0 ] || return 3
    for N in pid mnt net ipc uts cgroup; do
        [ "$($BB readlink /proc/$$/ns/$N)" = "$($BB readlink /proc/1/ns/$N)" ] || return 3
    done
}

# ------------------------------------------------------------------------------
# 【函数：verify_init】
# 严密核验目标进程是否为真正的 Guest init（PID 1）：
# 1. alive 验证：PID 与 start tick 匹配且非僵尸；
# 2. cmdline 验证：命令行必须包含 /system/bin/iris_init；
# 3. NSpid 验证：在其所属 PID Namespace 内必须是第 1 号进程（NSpid: 1）；
# 4. mountinfo 验证：挂载信息中必须挂载了本实例专属的 upperdir（OverlayFS 可写层）；
# 5. 命名空间隔离验证：6 大 Namespace 必须已经全部脱离宿主 1 号进程；
# 6. cgroup 归属验证：内存与统一层级 cgroup 必须归属于该实例的 managed 子组。
# ------------------------------------------------------------------------------
verify_init() {
    alive "$1" "$2" || return 1
    $BB grep -aq '/system/bin/iris_init' "/proc/$1/cmdline" || return 1
    [ "$($BB awk '/^NSpid:/ {print $NF}' "/proc/$1/status")" = 1 ] || return 1
    $BB grep -F "upperdir=$RUN/upper," "/proc/$1/mountinfo" >/dev/null || return 1
    for N in pid mnt net ipc uts cgroup; do
        [ "$($BB readlink /proc/$1/ns/$N)" != "$($BB readlink /proc/1/ns/$N)" ] || return 1
    done
    $BB grep -F ":memory:/rootvmos_$ID/managed" "/proc/$1/cgroup" >/dev/null || return 1
    $BB grep -F "0::/rootvmos_$ID/managed" "/proc/$1/cgroup" >/dev/null || return 1
}

# ------------------------------------------------------------------------------
# 【函数：groups_empty】
# 判断该实例的 cgroup 任务是否已经完全清空（populated 为 0 即表示组内没有任何进程）
# ------------------------------------------------------------------------------
groups_empty() {
    [ ! -f "$PIDS/cgroup.events" ] || $BB grep -qx 'populated 0' "$PIDS/cgroup.events"
}

# ------------------------------------------------------------------------------
# 【函数：join_groups】
# 将当前调用进程加入本实例的 managed 子组：
# 1. 重置 oom_score_adj 为 0；
# 2. 将当前 PID（$$）写入 5 大控制器（memory、pids、cpuctl、cpuset、blkio）的 managed 子组；
# 后续 fork 出的子进程及诊断注入操作将自动继承这些资源限额与调度策略。
# ------------------------------------------------------------------------------
join_groups() {
    echo 0 > /proc/$$/oom_score_adj
    for G in "$MEM/managed" "$PIDS/managed" \
        /dev/cpuctl/rootvmos_$ID/managed /dev/cpuset/rootvmos_$ID/managed \
        /dev/blkio/rootvmos_$ID/managed; do
        echo "$$" > "$G/cgroup.procs" || return 3
    done
}

