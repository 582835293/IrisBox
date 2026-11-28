#!/system/bin/sh
# ==============================================================================
# 【虹膜空间精准参数配置与生效脚本：tuning.sh】
# 支持按实例独立管理：
#   - pids_max        (auto | 2048..16384，需重启生效)
#   - cpuset_cpus     (all | big | little | <核心列表/掩码>，可实时生效并持久化)
#   - cpu_quota_pct   (0 无限制 | 25..800，换算为 CFS quota/period，可实时生效)
#   - cpu_shares      (256 | 512 | 1024 | 2048 | 4096，可实时生效)
#   - blkio_weight    (100..1000，可实时生效)
#   - display_dpi     (auto | 240 | 320 | 420 | 440，运行中实时 wm density)
#   - dexopt_install  (verify | speed-profile | speed，持久化+运行中 setprop/重启)
#   - dex2oat_threads (0 自动 | 1..8，持久化+运行中 setprop/重启)
# 注：图形模式 (graphics_mode: native/opengl) 复用 graphics.sh（停机生效）。
# ==============================================================================
set -eu

ACTION=${1:-get}
ID=${2:-7}
. /data/irisbox/runtime/common.sh "$ID"

C=$BASE/config/tuning_v1
CONF=$C/$ID.conf

DEF_PIDS_MAX=auto
DEF_CPUSET_CPUS=all
DEF_CPU_QUOTA_PCT=0
DEF_CPU_SHARES=1024
DEF_BLKIO_WEIGHT=500
DEF_DISPLAY_DPI=auto
DEF_DEXOPT_INSTALL=verify
DEF_DEX2OAT_THREADS=4

safe_conf_path() {
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$CONF" ] || return 3
    if [ -e "$CONF" ]; then
        [ -f "$CONF" ] && [ "$("$BB" wc -c < "$CONF")" -le 1024 ] || return 3
    fi
}

reset_defaults() {
    PIDS_MAX=$DEF_PIDS_MAX
    CPUSET_CPUS=$DEF_CPUSET_CPUS
    CPU_QUOTA_PCT=$DEF_CPU_QUOTA_PCT
    CPU_SHARES=$DEF_CPU_SHARES
    BLKIO_WEIGHT=$DEF_BLKIO_WEIGHT
    DISPLAY_DPI=$DEF_DISPLAY_DPI
    DEXOPT_INSTALL=$DEF_DEXOPT_INSTALL
    DEX2OAT_THREADS=$DEF_DEX2OAT_THREADS
}

valid_pids() {
    case "$1" in
        auto) return 0 ;;
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 2048 ] && [ "$1" -le 16384 ] ;;
    esac
}

valid_cpuset() {
    case "$1" in
        all|big|little) return 0 ;;
        ''|*[!0-9,-]*) return 2 ;;
        *)
            case "$1" in
                [0-9]*) [ "${#1}" -le 32 ] ;;
                *) return 2 ;;
            esac
            ;;
    esac
}

valid_quota() {
    case "$1" in
        0) return 0 ;;
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 25 ] && [ "$1" -le 800 ] ;;
    esac
}

valid_shares() {
    case "$1" in
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 100 ] && [ "$1" -le 10000 ] ;;
    esac
}

valid_blkio() {
    case "$1" in
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 100 ] && [ "$1" -le 1000 ] ;;
    esac
}

valid_dpi() {
    case "$1" in
        auto) return 0 ;;
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 120 ] && [ "$1" -le 640 ] ;;
    esac
}

valid_dexopt() {
    case "$1" in
        verify|speed-profile|speed|quicken|everything) return 0 ;;
        *) return 2 ;;
    esac
}

valid_threads() {
    case "$1" in
        ''|*[!0-9]*) return 2 ;;
        *) [ "$1" -ge 0 ] && [ "$1" -le 8 ] ;;
    esac
}

resolve_cpuset_mask() {
    _req=$1
    _host_cpus=$(cat /dev/cpuset/cpus 2>/dev/null || printf '0-7\n')
    case "$_req" in
        all)
            printf '%s\n' "$_host_cpus"
            ;;
        big)
            # 优先识别大核（如 4-7）；若主机核数较少则回退全核心
            _max_cap=0
            _big_list=""
            for _cpu in /sys/devices/system/cpu/cpu[0-9]*; do
                _n=${_cpu##*/cpu}
                case "$_n" in *[!0-9]*) continue ;; esac
                _cap=$(cat "$_cpu/cpu_capacity" 2>/dev/null || echo 0)
                if [ "$_cap" -gt "$_max_cap" ]; then _max_cap=$_cap; fi
            done
            if [ "$_max_cap" -gt 0 ]; then
                _thr=$(( _max_cap * 65 / 100 ))
                for _cpu in /sys/devices/system/cpu/cpu[0-9]*; do
                    _n=${_cpu##*/cpu}
                    case "$_n" in *[!0-9]*) continue ;; esac
                    _cap=$(cat "$_cpu/cpu_capacity" 2>/dev/null || echo 0)
                    if [ "$_cap" -ge "$_thr" ]; then
                        _big_list="${_big_list:+${_big_list},}$_n"
                    fi
                done
            fi
            printf '%s\n' "${_big_list:-$_host_cpus}"
            ;;
        little)
            _max_cap=0
            _lit_list=""
            for _cpu in /sys/devices/system/cpu/cpu[0-9]*; do
                _n=${_cpu##*/cpu}
                case "$_n" in *[!0-9]*) continue ;; esac
                _cap=$(cat "$_cpu/cpu_capacity" 2>/dev/null || echo 0)
                if [ "$_cap" -gt "$_max_cap" ]; then _max_cap=$_cap; fi
            done
            if [ "$_max_cap" -gt 0 ]; then
                _thr=$(( _max_cap * 65 / 100 ))
                for _cpu in /sys/devices/system/cpu/cpu[0-9]*; do
                    _n=${_cpu##*/cpu}
                    case "$_n" in *[!0-9]*) continue ;; esac
                    _cap=$(cat "$_cpu/cpu_capacity" 2>/dev/null || echo 0)
                    if [ "$_cap" -gt 0 ] && [ "$_cap" -lt "$_thr" ]; then
                        _lit_list="${_lit_list:+${_lit_list},}$_n"
                    fi
                done
            fi
            printf '%s\n' "${_lit_list:-$_host_cpus}"
            ;;
        *)
            printf '%s\n' "$_req"
            ;;
    esac
}

load_conf() {
    safe_conf_path || exit 3
    reset_defaults
    if [ -f "$CONF" ]; then
        while IFS='=' read -r K V; do
            case "$K" in ''|\#*) continue ;; esac
            case "$K" in
                pids_max) valid_pids "$V" && PIDS_MAX=$V ;;
                cpuset_cpus) valid_cpuset "$V" && CPUSET_CPUS=$V ;;
                cpu_quota_pct) valid_quota "$V" && CPU_QUOTA_PCT=$V ;;
                cpu_shares) valid_shares "$V" && CPU_SHARES=$V ;;
                blkio_weight) valid_blkio "$V" && BLKIO_WEIGHT=$V ;;
                display_dpi) valid_dpi "$V" && DISPLAY_DPI=$V ;;
                dexopt_install) valid_dexopt "$V" && DEXOPT_INSTALL=$V ;;
                dex2oat_threads) valid_threads "$V" && DEX2OAT_THREADS=$V ;;
            esac
        done < "$CONF"
    fi
}

apply_cgroup_settings() {
    _mask=$(resolve_cpuset_mask "$CPUSET_CPUS")
    _cpuset_g=/dev/cpuset/irisbox_$ID
    if [ -d "$_cpuset_g" ]; then
        printf '%s\n' "$_mask" > "$_cpuset_g/cpus" 2>/dev/null || {
            cat /dev/cpuset/cpus > "$_cpuset_g/cpus" 2>/dev/null || true
        }
        if [ -d "$_cpuset_g/managed" ]; then
            cat "$_cpuset_g/cpus" > "$_cpuset_g/managed/cpus" 2>/dev/null || true
        fi
    fi

    _cpuctl_g=/dev/cpuctl/irisbox_$ID
    if [ -d "$_cpuctl_g" ]; then
        if [ -f "$_cpuctl_g/cpu.shares" ]; then
            printf '%s\n' "$CPU_SHARES" > "$_cpuctl_g/cpu.shares" 2>/dev/null || true
        fi
        if [ -f "$_cpuctl_g/cpu.cfs_quota_us" ] && [ -f "$_cpuctl_g/cpu.cfs_period_us" ]; then
            _period=$(cat "$_cpuctl_g/cpu.cfs_period_us" 2>/dev/null || echo 100000)
            case "$_period" in ''|*[!0-9]*) _period=100000 ;; esac
            if [ "$CPU_QUOTA_PCT" -eq 0 ]; then
                printf '%s\n' -1 > "$_cpuctl_g/cpu.cfs_quota_us" 2>/dev/null || true
            else
                _quota=$(( _period * CPU_QUOTA_PCT / 100 ))
                [ "$_quota" -ge 1000 ] || _quota=1000
                printf '%s\n' "$_quota" > "$_cpuctl_g/cpu.cfs_quota_us" 2>/dev/null || true
            fi
        fi
    fi

    _blkio_g=/dev/blkio/irisbox_$ID
    if [ -d "$_blkio_g" ]; then
        for _w in blkio.bfq.weight blkio.weight; do
            if [ -f "$_blkio_g/$_w" ]; then
                printf '%s\n' "$BLKIO_WEIGHT" > "$_blkio_g/$_w" 2>/dev/null || true
            fi
        done
    fi
}

apply_guest_live() {
    [ -f "$RUN/init.identity" ] || return 0
    read _PID _TICK < "$RUN/init.identity" || return 0
    alive "$_PID" "$_TICK" || return 0

    _NEW_DPI=240
    if [ "$DISPLAY_DPI" = auto ]; then
        _res=1920x1080
        if [ -f "$BASE/config/resolution_v1/$ID.profile" ]; then
            _res=$(cat "$BASE/config/resolution_v1/$ID.profile" 2>/dev/null || echo 1920x1080)
        fi
        case "$_res" in
            legacy|1920x1080)
                "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- wm density reset >/dev/null 2>&1 || true
                _NEW_DPI=240
                ;;
            960x540)
                "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- wm density 120 >/dev/null 2>&1 || true
                _NEW_DPI=120
                ;;
            1280x720)
                "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- wm density 160 >/dev/null 2>&1 || true
                _NEW_DPI=160
                ;;
            1600x900)
                "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- wm density 200 >/dev/null 2>&1 || true
                _NEW_DPI=200
                ;;
        esac
    else
        "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- wm density "$DISPLAY_DPI" >/dev/null 2>&1 || true
        _NEW_DPI=$DISPLAY_DPI
    fi

    # 同步更新运行态 resolution.effective 的第7列 DPI，确保全屏显示预检 resolution.sh check 一致通过
    if [ -f "$RUN/resolution.effective" ] && [ ! -L "$RUN/resolution.effective" ]; then
        read _ES _EP _ELW _ELH _EOW _EOH _EDPI _EEX < "$RUN/resolution.effective" || true
        if [ -z "${_EEX:-}" ] && [ "$_ES" = "$_PID:$_TICK" ]; then
            _ETMP=$RUN/resolution.effective.tmp.$$
            printf '%s %s %s %s %s %s %s\n' "$_ES" "$_EP" "$_ELW" "$_ELH" "$_EOW" "$_EOH" "$_NEW_DPI" > "$_ETMP"
            "$BB" mv -f "$_ETMP" "$RUN/resolution.effective"
            _ELOG=$(cat "$RUN/latest.logdir" 2>/dev/null || true)
            if [ -n "$_ELOG" ] && [ -d "$_ELOG" ] && [ ! -L "$_ELOG/resolution.effective" ]; then
                cp "$RUN/resolution.effective" "$_ELOG/resolution.effective" 2>/dev/null || true
            fi
        fi
    fi

    "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- setprop pm.dexopt.install "$DEXOPT_INSTALL" >/dev/null 2>&1 || true
    if [ "$DEX2OAT_THREADS" -gt 0 ]; then
        "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- setprop dalvik.vm.dex2oat-threads "$DEX2OAT_THREADS" >/dev/null 2>&1 || true
        "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- setprop dalvik.vm.boot-dex2oat-threads "$DEX2OAT_THREADS" >/dev/null 2>&1 || true
    fi
    # 调高 DPI 切入手机模式时，确保 Launcher3 已默认启用允许主屏幕旋转，保留用户后续手改
    "$BASE/irisbox_ns" enter "$_PID" "$_TICK" -- /system/bin/busybox sh -c '
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
    ' >/dev/null 2>&1 || true
}

case "$ACTION" in
    get)
        host_only
        load_conf
        _host_cpus=$(cat /dev/cpuset/cpus 2>/dev/null || printf '0-7\n')
        _big_cpus=$(resolve_cpuset_mask big)
        _lit_cpus=$(resolve_cpuset_mask little)
        _eff_cpuset=$(cat "/dev/cpuset/irisbox_$ID/cpus" 2>/dev/null || printf '%s\n' "$_host_cpus")
        _eff_pids_max=$(cat "$PIDS/pids.max" 2>/dev/null || printf '%s\n' "$MAX_TASKS")
        _eff_pids_cur=$(cat "$PIDS/pids.current" 2>/dev/null || echo 0)
        _eff_shares=$(cat "/dev/cpuctl/irisbox_$ID/cpu.shares" 2>/dev/null || echo 1024)
        _eff_quota=$(cat "/dev/cpuctl/irisbox_$ID/cpu.cfs_quota_us" 2>/dev/null || echo -1)
        printf 'pids_max=%s\n' "$PIDS_MAX"
        printf 'cpuset_cpus=%s\n' "$CPUSET_CPUS"
        printf 'cpu_quota_pct=%s\n' "$CPU_QUOTA_PCT"
        printf 'cpu_shares=%s\n' "$CPU_SHARES"
        printf 'blkio_weight=%s\n' "$BLKIO_WEIGHT"
        printf 'display_dpi=%s\n' "$DISPLAY_DPI"
        printf 'dexopt_install=%s\n' "$DEXOPT_INSTALL"
        printf 'dex2oat_threads=%s\n' "$DEX2OAT_THREADS"
        printf 'host_cpus=%s\n' "$_host_cpus"
        printf 'host_big_cpus=%s\n' "$_big_cpus"
        printf 'host_little_cpus=%s\n' "$_lit_cpus"
        printf 'effective_cpuset=%s\n' "$_eff_cpuset"
        printf 'effective_pids_max=%s\n' "$_eff_pids_max"
        printf 'effective_pids_cur=%s\n' "$_eff_pids_cur"
        printf 'effective_shares=%s\n' "$_eff_shares"
        printf 'effective_quota_us=%s\n' "$_eff_quota"
        printf 'board_default_tasks=%s\n' "$MAX_TASKS"
        ;;
    set)
        host_only
        [ "$#" -ge 10 ] || { echo 'Use set ID PIDS CPUSET QUOTA SHARES BLKIO DPI DEXOPT THREADS' >&2; exit 2; }
        R_PIDS=$3; R_CPUSET=$4; R_QUOTA=$5; R_SHARES=$6; R_BLKIO=$7; R_DPI=$8; R_DEXOPT=$9; shift 9; R_THREADS=$1
        valid_pids "$R_PIDS" || { echo 'Invalid pids_max' >&2; exit 2; }
        valid_cpuset "$R_CPUSET" || { echo 'Invalid cpuset_cpus' >&2; exit 2; }
        valid_quota "$R_QUOTA" || { echo 'Invalid cpu_quota_pct' >&2; exit 2; }
        valid_shares "$R_SHARES" || { echo 'Invalid cpu_shares' >&2; exit 2; }
        valid_blkio "$R_BLKIO" || { echo 'Invalid blkio_weight' >&2; exit 2; }
        valid_dpi "$R_DPI" || { echo 'Invalid display_dpi' >&2; exit 2; }
        valid_dexopt "$R_DEXOPT" || { echo 'Invalid dexopt_install' >&2; exit 2; }
        valid_threads "$R_THREADS" || { echo 'Invalid dex2oat_threads' >&2; exit 2; }
        safe_conf_path || exit 3
        umask 077
        "$BB" mkdir -p "$C"
        TEMP=$C/$ID.conf.$$
        trap '"$BB" rm -f "$TEMP"' EXIT
        {
            printf 'pids_max=%s\n' "$R_PIDS"
            printf 'cpuset_cpus=%s\n' "$R_CPUSET"
            printf 'cpu_quota_pct=%s\n' "$R_QUOTA"
            printf 'cpu_shares=%s\n' "$R_SHARES"
            printf 'blkio_weight=%s\n' "$R_BLKIO"
            printf 'display_dpi=%s\n' "$R_DPI"
            printf 'dexopt_install=%s\n' "$R_DEXOPT"
            printf 'dex2oat_threads=%s\n' "$R_THREADS"
        } > "$TEMP"
        "$BB" mv -f "$TEMP" "$CONF"
        "$BB" chmod 0600 "$CONF"
        load_conf
        if ! groups_empty; then
            apply_cgroup_settings
            apply_guest_live
        fi
        echo "TUNING_SAVED id=$ID"
        ;;
    apply-cgroup)
        host_only
        load_conf
        apply_cgroup_settings
        ;;
    *)
        echo 'Use get|set|apply-cgroup ID ...' >&2
        exit 2
        ;;
esac
