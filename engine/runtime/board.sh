#!/system/bin/sh
# 宿主板卡 Profile 识别与参数加载公共模块（禁止在此写入板卡专属硬编码）。
# 运行时只信任随 engine.zip 校验落地的 $BASE/boards/<profile>/board.env。

iris_value_in_pipe_list() {
    _needle=$1
    _haystack=$2
    [ -n "$_needle" ] && [ -n "$_haystack" ] || return 1
    case "$_needle" in
        *'|'*) return 1 ;;
    esac
    case "|$_haystack|" in
        *"|$_needle|"*) return 0 ;;
    esac
    return 1
}

iris_load_board_env_file() {
    _file=$1
    [ ! -L "$_file" ] && [ -f "$_file" ] || return 1
    _bytes=$("$BB" wc -c < "$_file" 2>/dev/null || echo 99999)
    [ "$_bytes" -le 16384 ] || return 1

    # 逐行安全解析 KEY=VALUE，严禁 eval/source 执行任意命令
    while IFS= read -r _line || [ -n "$_line" ]; do
        case $_line in
            ''|'#'*) continue ;;
            BOARD_[A-Z0-9_]*=*)
                _key=${_line%%=*}
                _val=${_line#*=}
                case $_val in
                    *'$('*|*'`'*|*';'*|*'&'*|*'>'*|*'<'*|*"'"*|*'"'*)
                        return 1
                        ;;
                esac
                case $_key in
                    BOARD_PROFILE) BOARD_PROFILE=$_val ;;
                    BOARD_DISPLAY_NAME) BOARD_DISPLAY_NAME=$_val ;;
                    BOARD_ALLOWED_DEVICES) BOARD_ALLOWED_DEVICES=$_val ;;
                    BOARD_ABI) BOARD_ABI=$_val ;;
                    BOARD_ANDROID_API) BOARD_ANDROID_API=$_val ;;
                    BOARD_ALLOWED_FINGERPRINTS) BOARD_ALLOWED_FINGERPRINTS=$_val ;;
                    BOARD_REQUIRED_DEVS) BOARD_REQUIRED_DEVS=$_val ;;
                    BOARD_UPLINK_CANDIDATES) BOARD_UPLINK_CANDIDATES=$_val ;;
                    BOARD_BPF_MODE) BOARD_BPF_MODE=$_val ;;
                    BOARD_STOCK_BPF_PROG) BOARD_STOCK_BPF_PROG=$_val ;;
                    BOARD_STOCK_BPF_MAP) BOARD_STOCK_BPF_MAP=$_val ;;
                    BOARD_REQUIRE_EXTERNAL_MOUNT) BOARD_REQUIRE_EXTERNAL_MOUNT=$_val ;;
                    BOARD_HARDWARE) BOARD_HARDWARE=$_val ;;
                    BOARD_FSTAB_NAME) BOARD_FSTAB_NAME=$_val ;;
                    BOARD_GPU_DRIVER) BOARD_GPU_DRIVER=$_val ;;
                    BOARD_GPU_DEV_DIRS) BOARD_GPU_DEV_DIRS=$_val ;;
                    BOARD_GPU_DEV_NODES) BOARD_GPU_DEV_NODES=$_val ;;
                    BOARD_VENDOR_GRALLOC_PAYLOAD) BOARD_VENDOR_GRALLOC_PAYLOAD=$_val ;;
                    BOARD_VENDOR_GRALLOC_TARGET) BOARD_VENDOR_GRALLOC_TARGET=$_val ;;
                    BOARD_VULKAN_REMOVE_FILES) BOARD_VULKAN_REMOVE_FILES=$_val ;;
                    BOARD_EXPECTED_STOCK_GLES) BOARD_EXPECTED_STOCK_GLES=$_val ;;
                    BOARD_OPENGL_MODE_GLES) BOARD_OPENGL_MODE_GLES=$_val ;;
                    BOARD_GLES_OVERRIDE_ENV) BOARD_GLES_OVERRIDE_ENV=$_val ;;
                    BOARD_REMOVE_CODEC_INCLUDES) BOARD_REMOVE_CODEC_INCLUDES=$_val ;;
                    BOARD_MEM_MIN_MIB) BOARD_MEM_MIN_MIB=$_val ;;
                    BOARD_MEM_MAX_MIB) BOARD_MEM_MAX_MIB=$_val ;;
                    BOARD_MEM_DEFAULT_MIB) BOARD_MEM_DEFAULT_MIB=$_val ;;
                    BOARD_HOST_RESERVE_MIB) BOARD_HOST_RESERVE_MIB=$_val ;;
                    BOARD_HOST_STOP_BELOW_KIB) BOARD_HOST_STOP_BELOW_KIB=$_val ;;
                    BOARD_MAX_TASKS) BOARD_MAX_TASKS=$_val ;;
                    BOARD_GOOGLE_MAX_TASKS) BOARD_GOOGLE_MAX_TASKS=$_val ;;
                esac
                ;;
            *)
                return 1
                ;;
        esac
    done < "$_file"
    [ -n "${BOARD_PROFILE:-}" ] && [ -n "${BOARD_HARDWARE:-}" ]
}

iris_reset_board_vars() {
    BOARD_PROFILE=''
    BOARD_DIR=''
    BOARD_DISPLAY_NAME=''
    BOARD_ALLOWED_DEVICES=''
    BOARD_ABI=''
    BOARD_ANDROID_API=''
    BOARD_ALLOWED_FINGERPRINTS=''
    BOARD_REQUIRED_DEVS=''
    BOARD_UPLINK_CANDIDATES=''
    BOARD_BPF_MODE=''
    BOARD_STOCK_BPF_PROG=''
    BOARD_STOCK_BPF_MAP=''
    BOARD_REQUIRE_EXTERNAL_MOUNT=''
    BOARD_HARDWARE=''
    BOARD_FSTAB_NAME=''
    BOARD_GPU_DRIVER=''
    BOARD_GPU_DEV_DIRS=''
    BOARD_GPU_DEV_NODES=''
    BOARD_VENDOR_GRALLOC_PAYLOAD=''
    BOARD_VENDOR_GRALLOC_TARGET=''
    BOARD_VULKAN_REMOVE_FILES=''
    BOARD_EXPECTED_STOCK_GLES=''
    BOARD_OPENGL_MODE_GLES=''
    BOARD_GLES_OVERRIDE_ENV=''
    BOARD_REMOVE_CODEC_INCLUDES=''
    BOARD_MEM_MIN_MIB=''
    BOARD_MEM_MAX_MIB=''
    BOARD_MEM_DEFAULT_MIB=''
    BOARD_HOST_RESERVE_MIB=''
    BOARD_HOST_STOP_BELOW_KIB=''
    BOARD_MAX_TASKS=''
    BOARD_GOOGLE_MAX_TASKS=''
    BOARD_HOST_TOTAL_MIB=''
}

# 按宿主实际 /proc/meminfo 物理内存动态计算安全可配额上限与默认值，避免不同内存版本写死。
_iris_apply_dynamic_memory() {
    _mem_kib=$("$BB" awk '/^MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null || true)
    case "$_mem_kib" in
        ''|*[!0-9]*)
            BOARD_HOST_TOTAL_MIB=0
            return 0
            ;;
    esac
    read BOARD_HOST_TOTAL_MIB BOARD_MEM_MAX_MIB BOARD_MEM_DEFAULT_MIB <<EOF
$("$BB" awk -v k="$_mem_kib" -v bmin="$BOARD_MEM_MIN_MIB" -v bmax="$BOARD_MEM_MAX_MIB" -v bdef="$BOARD_MEM_DEFAULT_MIB" -v res="$BOARD_HOST_RESERVE_MIB" '
BEGIN {
    ht = int(k / 1024)
    if (ht <= 0) { printf "0 %d %d\n", bmax, bdef; exit }
    guard = int(ht * 21 / 100)
    if (res > guard) guard = res
    pcap = int((ht - guard) / 256) * 256
    if (pcap < bmin) pcap = bmin
    dmax = (ht <= 4608) ? ((pcap < bmax) ? pcap : bmax) : ((pcap > bmax) ? pcap : bmax)
    if (dmax < bmin) dmax = bmin
    if (dmax > 32768) dmax = 32768
    ddef = bdef
    if (ht >= 12000 && ddef < 4096) ddef = 4096
    else if (ht >= 6000 && ddef < 2048) ddef = 2048
    if (ddef > dmax) ddef = dmax
    if (ddef < bmin) ddef = bmin
    printf "%d %d %d\n", ht, dmax, ddef
}')
EOF
}

iris_detect_board() {
    _boards_root=${1:-"$BASE/boards"}
    [ ! -L "$_boards_root" ] && [ -d "$_boards_root" ] || { echo 'BOARD_CONFIG_MISSING' >&2; return 4; }
    _host_dev=$(/system/bin/getprop ro.product.device)
    _host_abi=$(/system/bin/getprop ro.product.cpu.abi)
    _host_sdk=$(/system/bin/getprop ro.build.version.sdk)
    _host_fp=$(/system/bin/getprop ro.build.fingerprint)

    for _env in "$_boards_root"/*/board.env; do
        [ -e "$_env" ] || continue
        iris_reset_board_vars
        iris_load_board_env_file "$_env" || continue
        iris_value_in_pipe_list "$_host_dev" "$BOARD_ALLOWED_DEVICES" || continue
        [ "$_host_abi" = "$BOARD_ABI" ] || continue
        [ "$_host_sdk" = "$BOARD_ANDROID_API" ] || continue
        iris_value_in_pipe_list "$_host_fp" "$BOARD_ALLOWED_FINGERPRINTS" || continue
        BOARD_DIR=${_env%/board.env}
        _iris_apply_dynamic_memory
        export BOARD_PROFILE BOARD_DIR BOARD_DISPLAY_NAME BOARD_HARDWARE BOARD_FSTAB_NAME \
            BOARD_GPU_DRIVER BOARD_GPU_DEV_DIRS BOARD_GPU_DEV_NODES \
            BOARD_VENDOR_GRALLOC_PAYLOAD BOARD_VENDOR_GRALLOC_TARGET \
            BOARD_VULKAN_REMOVE_FILES BOARD_EXPECTED_STOCK_GLES BOARD_OPENGL_MODE_GLES \
            BOARD_GLES_OVERRIDE_ENV BOARD_REMOVE_CODEC_INCLUDES \
            BOARD_UPLINK_CANDIDATES BOARD_BPF_MODE BOARD_STOCK_BPF_PROG BOARD_STOCK_BPF_MAP \
            BOARD_REQUIRE_EXTERNAL_MOUNT \
            BOARD_MEM_MIN_MIB BOARD_MEM_MAX_MIB BOARD_MEM_DEFAULT_MIB \
            BOARD_HOST_RESERVE_MIB BOARD_HOST_STOP_BELOW_KIB \
            BOARD_MAX_TASKS BOARD_GOOGLE_MAX_TASKS BOARD_HOST_TOTAL_MIB
        return 0
    done
    iris_reset_board_vars
    echo "BOARD_UNSUPPORTED: device=$_host_dev abi=$_host_abi sdk=$_host_sdk" >&2
    return 4
}

_iris_is_excluded_uplink() {
    case "$1" in
        ''|lo|dummy*|tun*|tap*|rv*|veth*|ip_vti*|ip6_vti*|ip6tnl*|sit*|gre*|ifb*|rmnet_r_*|p2p*|bridge*|br*|docker*|wlan_ap*|*[!a-zA-Z0-9_.-]*)
            return 0
            ;;
    esac
    return 1
}

_iris_iface_has_default_route() {
    _ip=$1
    _iface=$2
    _iris_is_excluded_uplink "$_iface" && return 1
    [ -d "/sys/class/net/$_iface" ] || return 1
    "$_ip" link show dev "$_iface" 2>/dev/null | "$BB" grep -Eq '<[^>]*LOWER_UP[^>]*>|state UP' || return 1
    "$_ip" route show table "$_iface" 2>/dev/null | "$BB" grep -q '^default '
}

_iris_append_unique_iface() {
    _cand=$1
    _current_list=$2
    [ -n "$_cand" ] || { printf '%s' "$_current_list"; return 0; }
    case " $_current_list " in
        *" $_cand "*) printf '%s' "$_current_list" ;;
        *)
            if [ -n "$_current_list" ]; then
                printf '%s %s' "$_current_list" "$_cand"
            else
                printf '%s' "$_cand"
            fi
            ;;
    esac
}

# 按优先级枚举宿主当前所有在线且拥有默认路由的物理上行网卡（空格分隔，去重）。
iris_list_active_physical_uplinks() {
    _ip=${1:-/system/bin/ip}
    _found=''

    # 1. Android Netd 默认物理网络表规则（即便开启 VPN tun0 时 31000 仍指向底层物理默认网卡）
    _netd_default=$("$_ip" rule show 2>/dev/null | "$BB" awk '
        $1=="31000:" && $2=="from" && $3=="all" && $4=="fwmark" && $5=="0x0/0xffff" && $6=="iif" && $7=="lo" && $8=="lookup" && NF>=9 {
            print $9; exit
        }')
    if _iris_iface_has_default_route "$_ip" "$_netd_default"; then
        _found=$(_iris_append_unique_iface "$_netd_default" "$_found")
    fi

    # 2. 非 VPN 场景下 kernel route get 直接命中的出站设备
    _route_dev=$("$_ip" -4 route get 223.5.5.5 2>/dev/null | "$BB" awk '{for(i=1;i<NF;i++) if($i=="dev") {print $(i+1); exit}}')
    if _iris_iface_has_default_route "$_ip" "$_route_dev"; then
        _found=$(_iris_append_unique_iface "$_route_dev" "$_found")
    fi

    # 3. 板卡 Profile 中声明的偏好物理候选网卡
    for _iface in ${BOARD_UPLINK_CANDIDATES:-wlan0 eth0}; do
        if _iris_iface_has_default_route "$_ip" "$_iface"; then
            _found=$(_iris_append_unique_iface "$_iface" "$_found")
        fi
    done

    # 4. 其余动态发现物理接口（先有线/USB，再无线，后其他）
    for _pat in 'eth*' 'en*' 'usb*' 'rndis*' 'wlan*' '*'; do
        for _path in /sys/class/net/$_pat; do
            [ -e "$_path" ] || continue
            _iface=${_path##*/}
            if _iris_iface_has_default_route "$_ip" "$_iface"; then
                _found=$(_iris_append_unique_iface "$_iface" "$_found")
            fi
        done
    done

    [ -n "$_found" ] || return 1
    printf '%s\n' "$_found"
}

iris_probe_active_uplink() {
    _ip=${1:-/system/bin/ip}
    _list=$(iris_list_active_physical_uplinks "$_ip") || return 1
    printf '%s\n' "${_list%% *}"
}

iris_probe_vpn_uplink() {
    _ip=${1:-/system/bin/ip}
    for _path in /sys/class/net/tun*; do
        [ -e "$_path" ] || continue
        _tun=${_path##*/}
        case "$_tun" in *[!a-zA-Z0-9_.-]*) continue ;; esac
        "$_ip" link show dev "$_tun" 2>/dev/null | "$BB" grep -Eq '<[^>]*UP[^>]*>|LOWER_UP' || continue
        if [ -n "$("$_ip" route show table "$_tun" 2>/dev/null | "$BB" head -n 1)" ]; then
            printf '%s\n' "$_tun"
            return 0
        fi
    done
    return 1
}

iris_select_uplink() {
    _ip=${1:-/system/bin/ip}
    iris_probe_active_uplink "$_ip" && return 0
    echo 'NETWORK_MISSING：宿主缺少默认网络路由' >&2
    return 4
}
