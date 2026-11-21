#!/system/bin/sh
# 阅读入口：Host prepare/cleanup与切根后的Guest guest三个分支。
# Host接veth、DHCP、限定转发/NAT和私有UID表；Guest框架/netd负责自己的默认Network。
# SESSION=Host PID:start tick；STATE是本次资源账本，失败/停止时据此精确回收。
# 有IP/ready不证明普通UID能建socket，更不证明Google登录或Play认证通过。
# One instance's network lifecycle; no Guest ndc/routectrl workarounds.
# Host: prepare ID PID:TICK LOG SECONDS | cleanup ID PID:TICK LOG | reconcile ID PID:TICK LOG
# Config/Probe: host-probe | config-get ID | config-set ID MODE IFACE PORT FALLBACK | probe ID | test-egress ID
# Guest after pivot + private proc: guest ID PID:TICK 'net:[NUMBER]'
# inner writes LOG/network.request before init and waits for identical network.ready.
# Existing watchdog owns lifetime; its every exit and ctl stop call exact cleanup.
set -eu
BB=/data/irisbox/host/bin/busybox
ACTION=${1:-}
ID=${2:-}
valid_instance_id() {
    case "$1" in ''|*[!0-9]*|0*) return 2 ;; esac
    [ "${#1}" -eq 4 ] && [ "$1" -ge 1007 ] && [ "$1" -le 1134 ]
}
check_session_arg() {
    case "$1" in *[!0-9:]*|:*|*:|*:*:*) return 2 ;; esac
    PID=${1%:*}
    TICK=${1#*:}
    [ "$PID" != "$1" ] && [ "$PID" -gt 1 ] && [ "$TICK" -gt 0 ]
}

# This branch cannot source Host common.sh: the old root has been detached.
# 切根后common的Host路径已消失，此分支只开放私有eth0参数。
if [ "$ACTION" = guest ]; then
    BB=/system/bin/busybox
    valid_instance_id "$ID" || { echo 'Invalid instance ID' >&2; exit 2; }
    SESSION=${3:-}
    check_session_arg "$SESSION" || exit 2
    [ $# -eq 4 ] && [ "$($BB id -u)" = 0 ] || exit 2
    EXPECTED_NET=$4
    [ "$($BB readlink /proc/self/ns/net)" = "$EXPECTED_NET" ] || exit 3
    [ "$($BB readlink /proc/1/ns/net)" = "$EXPECTED_NET" ] || exit 3
    [ "$($BB awk '{print $22}' /proc/1/stat)" = "$TICK" ] || exit 3
    [ "$($BB awk '/^NSpid:/ {print $NF}' /proc/1/status)" = 1 ] || exit 3
    # Fresh sysfs must show the same private eth0 as iproute; never Host's eth0.
    SYS_INDEX=$($BB cat /sys/class/net/eth0/ifindex)
    IP_INDEX=$($BB ip link show dev eth0 | $BB awk -F: 'NR==1 {print $1}')
    [ "$SYS_INDEX" = "$IP_INDEX" ] || exit 3
    for FAMILY in ipv4 ipv6; do
        CONF=/proc/sys/net/$FAMILY/conf/eth0
        [ -d "$CONF" ] || exit 3
        # The trusted Host preparer exposed only this private-netns exception.
        [ "$($BB awk '{print $2}' /proc/self/uid_map)" -ge 2000000000 ] || exit 3
    done
    echo "Guest $ID private eth0 configuration ready"
    exit 0
fi

# 宿主机全局物理与 VPN 出口只读探测（供 Broker 展示顶部宿主网络状态）
if [ "$ACTION" = host-probe ]; then
    [ $# -eq 1 ] || exit 2
    . /data/irisbox/runtime/common.sh 7
    host_only || exit 3
    IP=/system/bin/ip
    PHYS_LIST=$(iris_list_active_physical_uplinks "$IP" || true)
    PHYS_PRIMARY=${PHYS_LIST%% *}
    VPN_IFACE=$(iris_probe_vpn_uplink "$IP" || true)
    printf 'host_physical=%s\nhost_physical_list=%s\nhost_vpn=%s\n' \
        "$PHYS_PRIMARY" "$PHYS_LIST" "$VPN_IFACE"
    exit 0
fi

case "$ACTION" in
    config-get|probe|test-egress) [ $# -eq 2 ] || exit 2 ;;
    config-set) [ $# -eq 6 ] || [ $# -eq 7 ] || exit 2 ;;
    prepare) [ $# -eq 5 ] || exit 2 ;;
    cleanup|reconcile) [ $# -eq 4 ] || exit 2 ;;
    *) exit 2 ;;
esac

valid_instance_id "$ID" || { echo 'Invalid instance ID' >&2; exit 2; }
. /data/irisbox/runtime/common.sh "$ID"
host_only || exit 3

IP=/system/bin/ip
IPT=/system/bin/iptables
NDC=/system/bin/ndc
NET_CONFIG_DIR=$BASE/config/network_v1
NET_CONFIG_FILE=$NET_CONFIG_DIR/$ID.conf
OCTET3=$((ID - 1000))
SUBNET=172.30.$OCTET3.0/24
GUEST_IP=172.30.$OCTET3.2
GATEWAY=172.30.$OCTET3.1
IFACE=rv${ID}h
CURRENT=$RUN/network.current
PREF_RETURN=10909
PREF_OUT=12009
PREF_OUT_FALLBACK=12010
case "$ID" in
    7) PREF_RETURN=10907; PREF_OUT=12007; PREF_OUT_FALLBACK=12017 ;;
    8) PREF_RETURN=10908; PREF_OUT=12008; PREF_OUT_FALLBACK=12018 ;;
esac

valid_net_mode() {
    case "$1" in follow_host_uplink|fixed_uplink|host_vpn_tun|host_proxy) return 0 ;; *) return 1 ;; esac
}

valid_net_iface() {
    case "$1" in '') return 0 ;; esac
    ! _iris_is_excluded_uplink "$1" && [ "${#1}" -le 16 ]
}

valid_net_port() {
    case "$1" in ''|*[!0-9]*|0*) return 1 ;; esac
    [ "${#1}" -le 5 ] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

valid_vpn_fallback() {
    case "$1" in fallback_physical|fail_closed) return 0 ;; *) return 1 ;; esac
}

valid_ipv4() {
    case "$1" in
        ''|*[!0-9.]*|.*|*.) return 1 ;;
    esac
    _oifs=$IFS; IFS=.; set -- $1; IFS=$_oifs
    [ $# -eq 4 ] || return 1
    for _oct in "$1" "$2" "$3" "$4"; do
        case "$_oct" in ''|*[!0-9]*) return 1 ;; esac
        [ "${#_oct}" -le 3 ] && [ "$_oct" -ge 0 ] && [ "$_oct" -le 255 ] || return 1
    done
    [ "$1.$2.$3.$4" != 0.0.0.0 ]
}

valid_dns_spec() {
    case "$1" in
        auto|domestic|overseas) return 0 ;;
        *,*)
            _d1=${1%%,*}; _d2=${1#*,}
            case "$_d2" in *,*) return 1 ;; esac
            valid_ipv4 "$_d1" && valid_ipv4 "$_d2"
            ;;
        *) valid_ipv4 "$1" ;;
    esac
}

load_network_config() {
    NET_MODE=follow_host_uplink
    NET_IFACE=''
    NET_PORT=7890
    NET_VPN_FALLBACK=fallback_physical
    NET_DNS=auto
    [ ! -L "$BASE/config" ] && [ ! -L "$NET_CONFIG_DIR" ] && [ ! -L "$NET_CONFIG_FILE" ] || return 3
    [ -e "$NET_CONFIG_FILE" ] || return 0
    [ -f "$NET_CONFIG_FILE" ] || return 3
    _bytes=$("$BB" wc -c < "$NET_CONFIG_FILE" 2>/dev/null || echo 9999)
    [ "$_bytes" -le 1024 ] || return 3
    while IFS= read -r _line || [ -n "$_line" ]; do
        case $_line in
            ''|'#'*) continue ;;
            mode=*) _v=${_line#mode=}; valid_net_mode "$_v" || return 3; NET_MODE=$_v ;;
            iface=*) _v=${_line#iface=}; valid_net_iface "$_v" || return 3; NET_IFACE=$_v ;;
            port=*) _v=${_line#port=}; valid_net_port "$_v" || return 3; NET_PORT=$_v ;;
            vpn_fallback=*) _v=${_line#vpn_fallback=}; valid_vpn_fallback "$_v" || return 3; NET_VPN_FALLBACK=$_v ;;
            dns=*) _v=${_line#dns=}; valid_dns_spec "$_v" || return 3; NET_DNS=$_v ;;
            *) return 3 ;;
        esac
    done < "$NET_CONFIG_FILE"
    if [ "$NET_MODE" = fixed_uplink ] && [ -z "$NET_IFACE" ]; then
        return 3
    fi
    return 0
}

resolve_target_dns() {
    case "$NET_DNS" in
        domestic)
            TARGET_DNS='223.5.5.5 119.29.29.29'
            TARGET_PRIVATE_DNS='off'
            ;;
        overseas)
            TARGET_DNS='1.1.1.1 8.8.8.8'
            TARGET_PRIVATE_DNS='auto'
            ;;
        auto)
            _is_vpn_like=0
            if [ "$NET_MODE" = host_proxy ]; then
                _is_vpn_like=1
            else
                case "$TARGET_UPLINK" in tun*|tap*|wg*|ppp*) _is_vpn_like=1 ;; esac
            fi
            if [ "$_is_vpn_like" = 1 ]; then
                TARGET_DNS='1.1.1.1 8.8.8.8'
                TARGET_PRIVATE_DNS='auto'
            else
                TARGET_DNS='223.5.5.5 119.29.29.29'
                TARGET_PRIVATE_DNS='off'
            fi
            ;;
        *,*)
            TARGET_DNS="${NET_DNS%%,*} ${NET_DNS#*,}"
            TARGET_PRIVATE_DNS='off'
            ;;
        *)
            TARGET_DNS="$NET_DNS"
            TARGET_PRIVATE_DNS='off'
            ;;
    esac
}

compute_target_egress() {
    PHYS_LIST=$(iris_list_active_physical_uplinks "$IP" || true)
    PHYS_PRIMARY=${PHYS_LIST%% *}
    [ -n "$PHYS_PRIMARY" ] || PHYS_PRIMARY=none
    VPN_IFACE=$(iris_probe_vpn_uplink "$IP" || true)

    TARGET_UPLINK=none
    TARGET_RULES=''
    TARGET_PROXY_PORT=''
    case "$NET_MODE" in
        follow_host_uplink)
            TARGET_UPLINK=$PHYS_PRIMARY
            if [ "$PHYS_PRIMARY" != none ]; then
                TARGET_RULES="${PREF_OUT}:${PHYS_PRIMARY}"
            fi
            ;;
        fixed_uplink)
            if [ -n "$NET_IFACE" ] && _iris_iface_has_default_route "$IP" "$NET_IFACE"; then
                TARGET_UPLINK=$NET_IFACE
                TARGET_RULES="${PREF_OUT}:${NET_IFACE}"
            fi
            ;;
        host_vpn_tun)
            if [ -n "$VPN_IFACE" ]; then
                TARGET_UPLINK=$VPN_IFACE
                TARGET_RULES="${PREF_OUT}:${VPN_IFACE}"
                if [ "$PHYS_PRIMARY" != none ] && [ "$PHYS_PRIMARY" != "$VPN_IFACE" ]; then
                    TARGET_RULES="${TARGET_RULES} ${PREF_OUT_FALLBACK}:${PHYS_PRIMARY}"
                fi
            elif [ "$NET_VPN_FALLBACK" = fallback_physical ]; then
                TARGET_UPLINK=$PHYS_PRIMARY
                if [ "$PHYS_PRIMARY" != none ]; then
                    TARGET_RULES="${PREF_OUT}:${PHYS_PRIMARY}"
                fi
            fi
            ;;
        host_proxy)
            TARGET_UPLINK=$PHYS_PRIMARY
            if [ "$PHYS_PRIMARY" != none ]; then
                TARGET_RULES="${PREF_OUT}:${PHYS_PRIMARY}"
            fi
            TARGET_PROXY_PORT=$NET_PORT
            ;;
    esac
}

if [ "$ACTION" = config-get ]; then
    load_network_config
    printf 'mode=%s\niface=%s\nport=%s\nvpn_fallback=%s\ndns=%s\n' \
        "$NET_MODE" "$NET_IFACE" "$NET_PORT" "$NET_VPN_FALLBACK" "$NET_DNS"
    exit 0
fi

if [ "$ACTION" = probe ]; then
    load_network_config || {
        printf 'valid=0\nmode=follow_host_uplink\niface=\nport=7890\nvpn_fallback=fallback_physical\ndns=auto\neffective_dns=\n'
        exit 0
    }
    PHYS_LIST=$(iris_list_active_physical_uplinks "$IP" || true)
    PHYS_PRIMARY=${PHYS_LIST%% *}
    VPN_IFACE=$(iris_probe_vpn_uplink "$IP" || true)
    EFF_UPLINK=''
    EFF_MODE=''
    EFF_DNS=''
    PROXY_APPLIED=0
    PROXY_REACH=0
    RUNNING=0
    if [ -f "$CURRENT" ] && [ ! -L "$CURRENT" ]; then
        read _csess _cstate < "$CURRENT" 2>/dev/null || true
        if [ -n "${_cstate:-}" ] && [ -d "$_cstate" ] && [ ! -L "$_cstate" ]; then
            RUNNING=1
            [ -f "$_cstate/uplink" ] && EFF_UPLINK=$($BB cat "$_cstate/uplink" 2>/dev/null || true)
            [ -f "$_cstate/mode" ] && EFF_MODE=$($BB cat "$_cstate/mode" 2>/dev/null || true)
            [ -f "$_cstate/dns" ] && EFF_DNS=$($BB cat "$_cstate/dns" 2>/dev/null || true)
            [ -f "$_cstate/proxy_target" ] && PROXY_APPLIED=1
        fi
    fi
    if [ "$NET_MODE" = host_proxy ]; then
        if [ "$RUNNING" = 1 ]; then
            "$BB" nc -z -w 1 "$GATEWAY" "$NET_PORT" >/dev/null 2>&1 && PROXY_REACH=1 || true
        else
            "$BB" nc -z -w 1 127.0.0.1 "$NET_PORT" >/dev/null 2>&1 && PROXY_REACH=1 || true
        fi
    fi
    printf 'valid=1\nmode=%s\niface=%s\nport=%s\nvpn_fallback=%s\ndns=%s\neffective_dns=%s\nhost_physical=%s\nhost_physical_list=%s\nhost_vpn=%s\neffective_uplink=%s\neffective_mode=%s\nproxy_applied=%s\nproxy_reachable=%s\n' \
        "$NET_MODE" "$NET_IFACE" "$NET_PORT" "$NET_VPN_FALLBACK" "$NET_DNS" "$EFF_DNS" \
        "$PHYS_PRIMARY" "$PHYS_LIST" "$VPN_IFACE" "$EFF_UPLINK" "$EFF_MODE" \
        "$PROXY_APPLIED" "$PROXY_REACH"
    exit 0
fi

if [ "$ACTION" = test-egress ]; then
    load_network_config
    compute_target_egress
    echo "策略模式：$NET_MODE"
    echo "宿主物理网络：${PHYS_LIST:-无默认路由}"
    echo "宿主 VPN (tun0)：${VPN_IFACE:-未开启}"
    echo "预期出口：$TARGET_UPLINK"
    if [ "$NET_MODE" = host_proxy ]; then
        if "$BB" nc -z -w 1 127.0.0.1 "$NET_PORT" >/dev/null 2>&1; then
            echo "宿主代理监听 (127.0.0.1:$NET_PORT)：在线"
        else
            echo "宿主代理监听 (127.0.0.1:$NET_PORT)：未监听"
        fi
    fi
    if [ -f "$RUN/init.identity" ]; then
        read GPID GTICK < "$RUN/init.identity" 2>/dev/null || true
        if [ -n "${GPID:-}" ] && [ -n "${GTICK:-}" ] && verify_init "$GPID" "$GTICK"; then
            if [ "$NET_MODE" = host_proxy ]; then
                if "$BB" nc -z -w 1 "$GATEWAY" "$NET_PORT" >/dev/null 2>&1; then
                    echo "网关代理可达 ($GATEWAY:$NET_PORT)：PASS"
                else
                    echo "网关代理可达 ($GATEWAY:$NET_PORT)：FAIL（请确认宿主代理已开启允许局域网连接）"
                fi
            fi
            _boot=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 4 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/getprop sys.boot_completed ) 2>/dev/null || true )
            if [ "$_boot" = 1 ]; then
                if [ "$TARGET_UPLINK" = none ] && [ "$NET_MODE" != host_proxy ]; then
                    echo "容器探测 Google 204：离线阻断（当前出口 uplink=none，已按策略拦截出网）"
                elif [ "$NET_MODE" = host_proxy ]; then
                    _gcheck=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 7 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/sh -c "http_proxy=http://$GATEWAY:$NET_PORT /system/bin/busybox wget -T 5 -S --spider http://connectivitycheck.gstatic.com/generate_204 2>&1 | /system/bin/busybox grep -E 'HTTP/|wget:' | head -n 1" ) 2>/dev/null || true )
                    echo "容器探测 Google 204：${_gcheck:-超时/无响应}"
                else
                    _gcheck=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 7 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/sh -c "/system/bin/busybox wget -T 5 -S --spider http://connectivitycheck.gstatic.com/generate_204 2>&1 | /system/bin/busybox grep -E 'HTTP/|wget:' | head -n 1" ) 2>/dev/null || true )
                    echo "容器探测 Google 204：${_gcheck:-超时/无响应}"
                fi
            else
                echo "容器状态：正在开机，暂跳过容器内出网探测"
            fi
        fi
    fi
    exit 0
fi

umask 077
[ ! -L "$RUN/network.lock" ] && [ ! -L "$CURRENT" ] || exit 3

# config-set 也经过同一 instance network.lock，若运行中则保存后立刻就地协调
if [ "$ACTION" = config-set ]; then
    REQ_MODE=$3
    REQ_IFACE=$4
    REQ_PORT=$5
    REQ_FALLBACK=$6
    REQ_DNS=${7:-auto}
    [ "$REQ_IFACE" = '-' ] && REQ_IFACE=''
    valid_net_mode "$REQ_MODE" || { echo 'Invalid network mode' >&2; exit 2; }
    valid_net_iface "$REQ_IFACE" || { echo 'Invalid network interface' >&2; exit 2; }
    if [ "$REQ_MODE" = fixed_uplink ] && [ -z "$REQ_IFACE" ]; then
        echo 'Fixed interface name is required' >&2; exit 2
    fi
    valid_net_port "$REQ_PORT" || { echo 'Proxy port must be 1..65535' >&2; exit 2; }
    valid_vpn_fallback "$REQ_FALLBACK" || { echo 'Invalid vpn fallback option' >&2; exit 2; }
    valid_dns_spec "$REQ_DNS" || { echo 'Invalid DNS configuration' >&2; exit 2; }
    "$BB" mkdir -p "$RUN"
    if [ "${IRIS_NETWORK_LOCKED:-}" != 1 ]; then
        exec "$BB" timeout -s TERM 60 "$BB" flock -x "$RUN/network.lock" \
            "$BB" env IRIS_NETWORK_LOCKED=1 sh "$BASE/network.sh" "$@"
    fi
    [ ! -L "$BASE/config" ] && [ ! -L "$NET_CONFIG_DIR" ] && [ ! -L "$NET_CONFIG_FILE" ] || exit 3
    "$BB" mkdir -p "$NET_CONFIG_DIR"
    TMP_CFG="$NET_CONFIG_FILE.tmp.$$"
    trap '"$BB" rm -f "$TMP_CFG"' EXIT
    printf 'mode=%s\niface=%s\nport=%s\nvpn_fallback=%s\ndns=%s\n' \
        "$REQ_MODE" "$REQ_IFACE" "$REQ_PORT" "$REQ_FALLBACK" "$REQ_DNS" > "$TMP_CFG"
    "$BB" mv -f "$TMP_CFG" "$NET_CONFIG_FILE"
    trap - EXIT
    echo "NETWORK_SAVED id=$ID mode=$REQ_MODE iface=$REQ_IFACE port=$REQ_PORT vpn_fallback=$REQ_FALLBACK dns=$REQ_DNS"
    if [ -f "$CURRENT" ] && [ ! -L "$CURRENT" ]; then
        read CURR_SESS CURR_STATE < "$CURRENT" 2>/dev/null || true
        CURR_LOG=${CURR_STATE%/network}
        if [ -n "${CURR_SESS:-}" ] && [ -n "${CURR_LOG:-}" ] && [ -d "$CURR_LOG" ]; then
            exec sh "$BASE/network.sh" reconcile "$ID" "$CURR_SESS" "$CURR_LOG"
        fi
    fi
    exit 0
fi

SESSION=${3:-}
check_session_arg "$SESSION" || exit 2
LOG=$4
case "$LOG" in "$RUN"/*) ;; *) exit 3 ;; esac
LOG_NAME=${LOG#"$RUN"/}
case "$LOG_NAME" in ''|*[!0-9_]*) exit 3 ;; esac
[ -d "$LOG" ] && [ ! -L "$LOG" ] && [ ! -L "$RUN" ] || exit 3
[ "$($BB readlink -f "$LOG")" = "$LOG" ] || exit 3
STATE=$LOG/network
ALIAS=IRISBOX_NET_${ID}_${PID}_${TICK}
RECORDED_UPLINK=
UPLINK=none
if [ -f "$STATE/uplink" ] && [ ! -L "$STATE/uplink" ]; then
    RECORDED_UPLINK=$($BB cat "$STATE/uplink" 2>/dev/null || true)
    case "$RECORDED_UPLINK" in
        ''|*[!a-zA-Z0-9_.-]*) RECORDED_UPLINK= ;;
        *) UPLINK=$RECORDED_UPLINK ;;
    esac
fi
return_rule() { "$IP" rule show | "$BB" awk -v p="$PREF_RETURN:" -v n="$SUBNET" '$1==p && index(" " $0 " "," to " n " ") {print}'; }
out_rule() { "$IP" rule show | "$BB" awk -v p1="$PREF_OUT:" -v p2="$PREF_OUT_FALLBACK:" -v i="$IFACE" '($1==p1 || $1==p2) && index(" " $0 " "," iif " i " ") {print}'; }
out_rule_for_pref() { "$IP" rule show | "$BB" awk -v p="$1:" -v i="$IFACE" -v t="$2" '$1==p && index(" " $0 " "," iif " i " ") && index(" " $0 " "," lookup " t " ") {print}'; }
TAG=IRISBOX_NET_${ID}_${PID}_${TICK}
REQUESTER=irisbox_${ID}_${PID}_${TICK}
if [ "${IRIS_NETWORK_LOCKED:-}" != 1 ]; then
    # BusyBox opens an inheritable lock FD; mksh-created numbered FDs are CLOEXEC.
    exec "$BB" timeout -s TERM 60 "$BB" flock -x "$RUN/network.lock" \
        "$BB" env IRIS_NETWORK_LOCKED=1 sh "$BASE/network.sh" "$@"
fi
NETWORK_FD=
for LOCK_FILE in /proc/$$/fd/*; do
    if [ "$($BB readlink "$LOCK_FILE" 2>/dev/null || true)" = "$RUN/network.lock" ]; then
        NETWORK_FD=${LOCK_FILE##*/}
    fi
done
case "$NETWORK_FD" in ''|*[!0-9]*) echo 'Inherited network lock FD missing' >&2; exit 3 ;; esac

current_is_ours() {
    [ -f "$CURRENT" ] && [ "$($BB cat "$CURRENT")" = "$SESSION $STATE" ]
}

# prepare在init exec之前，核inner shell/request/overlay/namespace/资源归属。
preinit_is_ours() {
    alive "$PID" "$TICK" || return 3
    [ ! -L "$LOG/network.request" ] &&
        [ "$($BB cat "$LOG/network.request")" = "$SESSION" ] || return 3
    "$BASE/irisbox_userns" verify-preinit "$ID" "$PID" "$TICK" || return 3
    $BB tr '\000' '\n' < "/proc/$PID/cmdline" | $BB grep -qx "$BASE/irisbox_userns" || return 3
    [ "$($BB awk '/^NSpid:/ {print $NF}' "/proc/$PID/status")" = 1 ] || return 3
    $BB grep -F "upperdir=$RUN/upper," "/proc/$PID/mountinfo" >/dev/null || return 3
    for NS in pid mnt net ipc uts cgroup; do
        [ "$($BB readlink /proc/$PID/ns/$NS)" != "$($BB readlink /proc/1/ns/$NS)" ] || return 3
    done
    $BB grep -F ":memory:/irisbox_$ID/managed" "/proc/$PID/cgroup" >/dev/null || return 3
    $BB grep -F "0::/irisbox_$ID/managed" "/proc/$PID/cgroup" >/dev/null || return 3
}

# 同名网卡不等于同一资源，核alias/ifindex及必要时互相对应的veth端点。
link_is_ours() {
    LINK_ALIAS=$($BB cat /sys/class/net/$IFACE/ifalias) || return 3
    LINK_INDEX=$($BB cat /sys/class/net/$IFACE/ifindex) || return 3
    if [ -f "$STATE/ifindex" ]; then
        [ "$LINK_INDEX" = "$($BB cat "$STATE/ifindex")" ] || return 3
    fi
    [ "$LINK_ALIAS" != "$ALIAS" ] || return 0
    # Android iproute stops generic parsing at alias. Only the peer can be labelled
    # atomically (alias is its final argument); recover the short Host-label gap
    # using the live pre-init session and exact, reciprocal veth peer identity.
    [ -z "$LINK_ALIAS" ] && [ -f "$STATE/create.intent" ] || return 3
    [ "$($BB cat "$STATE/create.intent")" = "$SESSION" ] || return 3
    preinit_is_ours || return 3
    [ "$($BB readlink /proc/$PID/ns/net)" = "$($BB cat "$STATE/netns")" ] || return 3
    PEER_INFO=$($BB timeout -s KILL 3 "$BB" nsenter -t "$PID" -n \
        "$IP" -d -o link show dev eth0) || return 3
    echo "$PEER_INFO" | $BB awk -v alias="$ALIAS" \
        '{for(i=1;i<NF;i++) if($i=="alias" && $(i+1)==alias) found=1} END {exit !found}' || return 3
    PEER_INDEX=$(echo "$PEER_INFO" | $BB awk -F: 'NR==1 {print $1}')
    PEER_LINK=$(echo "$PEER_INFO" | $BB awk 'NR==1 {print $2}')
    [ "$PEER_LINK" = "eth0@if$LINK_INDEX:" ] || return 3
    [ "$($BB cat /sys/class/net/$IFACE/iflink)" = "$PEER_INDEX" ] || return 3
    preinit_is_ours || return 3
}

dhcp_is_ours() {
    case "$1:$2" in *[!0-9:]*|:*|*:) return 3 ;; esac
    alive "$1" "$2" || return 1
    [ "$($BB awk '/^Uid:/ {print $2}' "/proc/$1/status")" = 0 ] || return 3
    [ "$($BB readlink /proc/$1/ns/net)" = "$($BB readlink /proc/1/ns/net)" ] || return 3
    $BB tr '\000' '\n' < "/proc/$1/cmdline" | $BB grep -qx "$STATE/dhcp.conf" || return 3
    $BB tr '\000' '\n' < "/proc/$1/cmdline" | $BB grep -qx 'udhcpd' || return 3
}

stop_dhcp() {
    for RECORD in dhcp.identity dhcp.actual.identity; do
        [ -f "$STATE/$RECORD" ] || continue
        read DP DT < "$STATE/$RECORD" || return 3
        if alive "$DP" "$DT"; then
            dhcp_is_ours "$DP" "$DT" || return 3
            kill -TERM "$DP" || return 3
            for WAIT in 1 2 3; do alive "$DP" "$DT" || break; $BB sleep 1; done
            if alive "$DP" "$DT"; then
                dhcp_is_ours "$DP" "$DT" || return 3
                kill -KILL "$DP" || return 3
                $BB sleep 1
            fi
            alive "$DP" "$DT" && return 3
        fi
    done
    return 0
}

delete_iptables_rule() {
    TABLE=$1
    shift
    if "$IPT" -w 3 -t "$TABLE" -C "$@" >/dev/null 2>&1; then
        CHECK=0
    else
        CHECK=$?
    fi
    case "$CHECK" in
        0) "$IPT" -w 3 -t "$TABLE" -D "$@" ;;
        1) return 0 ;;
        *) return "$CHECK" ;;
    esac
}

ensure_iptables_rule() {
    OP=$1
    TABLE=$2
    CHAIN=$3
    shift 3
    if "$IPT" -w 3 -t "$TABLE" -C "$CHAIN" "$@" >/dev/null 2>&1; then
        return 0
    else
        CHECK=$?
    fi
    case "$CHECK" in
        1)
            case "$OP" in
                -I) "$IPT" -w 3 -t "$TABLE" -I "$CHAIN" 1 "$@" ;;
                -A) "$IPT" -w 3 -t "$TABLE" -A "$CHAIN" "$@" ;;
                *) return 2 ;;
            esac
            ;;
        *) return "$CHECK" ;;
    esac
}

ensure_ipfwd_enabled() {
    [ ! -L "$STATE/forwarding" ] || return 3
    if [ ! -f "$STATE/forwarding" ]; then
        : > "$STATE/forwarding"
        RESULT=$($BB timeout -s KILL 5 "$NDC" ipfwd enable "$REQUESTER" 2>&1)
        echo "$RESULT" | $BB grep -q '^200 0 '
    fi
}

record_applied_uplink() {
    [ ! -L "$STATE/applied_uplinks" ] || return 3
    if [ ! -f "$STATE/applied_uplinks" ]; then
        : > "$STATE/applied_uplinks"
    fi
    "$BB" grep -qxF "$1" "$STATE/applied_uplinks" 2>/dev/null || printf '%s\n' "$1" >> "$STATE/applied_uplinks"
}

unrecord_applied_uplink() {
    [ -f "$STATE/applied_uplinks" ] && [ ! -L "$STATE/applied_uplinks" ] || return 0
    "$BB" grep -vxF "$1" "$STATE/applied_uplinks" > "$STATE/applied_uplinks.tmp" 2>/dev/null || true
    "$BB" mv "$STATE/applied_uplinks.tmp" "$STATE/applied_uplinks"
}

parse_rule_entry() {
    case "$1" in
        *:*) _ep=${1%%:*}; _eu=${1#*:} ;;
        *) _ep=$PREF_OUT; _eu=$1 ;;
    esac
}

# 幂等写入指定优先级与上行出口的策略路由与转发/NAT规则，先记账本再改内核。
apply_rule_entry() {
    _p=$1
    _u=$2
    case "$_p:$_u" in ''|*[!0-9]*:none|*:*[!a-zA-Z0-9_.-]*|*:''|:*:) return 2 ;; esac
    record_applied_uplink "${_p}:${_u}"
    ensure_ipfwd_enabled
    : > "$STATE/route_out"
    if [ -z "$(out_rule_for_pref "$_p" "$_u")" ]; then
        "$IP" rule add pref "$_p" iif "$IFACE" lookup "$_u"
    fi
    ensure_iptables_rule -I filter FORWARD -i "$IFACE" -s "$GUEST_IP/32" -o "$_u" \
        -m comment --comment "${TAG}_OUT" -j ACCEPT
    ensure_iptables_rule -I filter FORWARD -i "$_u" -o "$IFACE" -d "$GUEST_IP/32" \
        -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment "${TAG}_RETURN" -j ACCEPT
    ensure_iptables_rule -A nat POSTROUTING -s "$GUEST_IP/32" -o "$_u" \
        -m comment --comment "${TAG}_NAT" -j MASQUERADE
}

# 精确回收指定规则条目；若同一接口不再被其他规则条目引用，同时精确回收其转发与NAT规则。
remove_rule_entry() {
    parse_rule_entry "$1"
    case "$_eu" in ''|none|*[!a-zA-Z0-9_.-]*) unrecord_applied_uplink "$1"; return 0 ;; esac
    _rf=0
    if [ -n "$(out_rule_for_pref "$_ep" "$_eu")" ]; then
        "$IP" rule del pref "$_ep" iif "$IFACE" lookup "$_eu" 2>/dev/null || true
        [ -z "$(out_rule_for_pref "$_ep" "$_eu")" ] || _rf=1
    fi
    _still_ref=0
    if [ -f "$STATE/applied_uplinks" ] && [ ! -L "$STATE/applied_uplinks" ]; then
        while IFS= read -r _other || [ -n "$_other" ]; do
            [ "$_other" = "$1" ] && continue
            [ -n "$_other" ] || continue
            case "$_other" in
                *:*) _ou=${_other#*:} ;;
                *) _ou=$_other ;;
            esac
            if [ "$_ou" = "$_eu" ]; then
                _still_ref=1
                break
            fi
        done < "$STATE/applied_uplinks"
    fi
    if [ "$_still_ref" = 0 ]; then
        delete_iptables_rule filter FORWARD -i "$IFACE" -s "$GUEST_IP/32" -o "$_eu" \
            -m comment --comment "${TAG}_OUT" -j ACCEPT || _rf=1
        delete_iptables_rule filter FORWARD -i "$_eu" -o "$IFACE" -d "$GUEST_IP/32" \
            -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment "${TAG}_RETURN" -j ACCEPT || _rf=1
        delete_iptables_rule nat POSTROUTING -s "$GUEST_IP/32" -o "$_eu" \
            -m comment --comment "${TAG}_NAT" -j MASQUERADE || _rf=1
    fi
    if [ "$_rf" = 0 ]; then
        unrecord_applied_uplink "$1"
        return 0
    fi
    return 1
}

# 按当前接口与优先级精确清理不在目标策略里的出站规则（覆盖 tun0 销毁后 Netd 删除 rt_tables 导致表名变成数字 ID 的残留）
prune_orphan_out_rules() {
    _keep=" ${1:-} "
    "$IP" rule show | "$BB" awk -v p1="$PREF_OUT:" -v p2="$PREF_OUT_FALLBACK:" -v i="$IFACE" '
        ($1==p1 || $1==p2) && index(" " $0 " ", " iif " i " ") {
            sub(/:$/, "", $1);
            tbl = "";
            for (k = 2; k <= NF; k++) if ($k == "lookup" && k < NF) { tbl = $(k+1); break; }
            if (tbl != "") print $1 ":" tbl;
        }
    ' | while IFS= read -r _pair || [ -n "$_pair" ]; do
        [ -n "$_pair" ] || continue
        case "$_keep" in
            *" $_pair "*) ;;
            *)
                parse_rule_entry "$_pair"
                "$IP" rule del pref "$_ep" iif "$IFACE" lookup "$_eu" 2>/dev/null || true
                ;;
        esac
    done
}

remove_proxy_host_input() {
    [ -f "$STATE/proxy_input" ] && [ ! -L "$STATE/proxy_input" ] || return 0
    _oldp=$($BB cat "$STATE/proxy_input" 2>/dev/null || true)
    case "$_oldp" in
        ''|*[!0-9]*) "$BB" rm -f "$STATE/proxy_input"; return 0 ;;
    esac
    delete_iptables_rule filter INPUT -i "$IFACE" -s "$GUEST_IP/32" -d "$GATEWAY/32" -p tcp --dport "$_oldp" \
        -m comment --comment "${TAG}_PROXY_IN" -j ACCEPT || return 1
    "$BB" rm -f "$STATE/proxy_input"
}

apply_proxy_host_input() {
    _p=$1
    valid_net_port "$_p" || return 2
    if [ -f "$STATE/proxy_input" ] && [ ! -L "$STATE/proxy_input" ]; then
        _curp=$($BB cat "$STATE/proxy_input" 2>/dev/null || true)
        [ "$_curp" = "$_p" ] || remove_proxy_host_input
    fi
    [ ! -L "$STATE/proxy_input" ] || return 3
    printf '%s\n' "$_p" > "$STATE/proxy_input"
    ensure_iptables_rule -I filter INPUT -i "$IFACE" -s "$GUEST_IP/32" -d "$GATEWAY/32" -p tcp --dport "$_p" \
        -m comment --comment "${TAG}_PROXY_IN" -j ACCEPT
}

sync_guest_proxy() {
    _dmode=$1
    _dport=$2
    [ -f "$RUN/init.identity" ] && [ ! -L "$RUN/init.identity" ] || return 0
    read GPID GTICK < "$RUN/init.identity" 2>/dev/null || return 0
    [ -n "${GPID:-}" ] && [ -n "${GTICK:-}" ] && verify_init "$GPID" "$GTICK" >/dev/null 2>&1 || return 0

    if [ "$_dmode" = host_proxy ]; then
        if [ -f "$STATE/proxy_target" ] && [ ! -L "$STATE/proxy_target" ] &&
           [ "$($BB cat "$STATE/proxy_target" 2>/dev/null || true)" = "${GATEWAY}:${_dport}" ]; then
            return 0
        fi
    else
        if [ ! -f "$STATE/proxy_target" ] && [ -f "$STATE/proxy_clean_verified" ]; then
            return 0
        fi
    fi

    _boot=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 4 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/getprop sys.boot_completed ) 2>/dev/null || true )
    [ "$_boot" = 1 ] || return 0

    if [ "$_dmode" = host_proxy ]; then
        if ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 6 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/settings put global http_proxy "${GATEWAY}:${_dport}" ) >/dev/null 2>&1; then
            printf '%s\n' "${GATEWAY}:${_dport}" > "$STATE/proxy_target.tmp"
            "$BB" mv -f "$STATE/proxy_target.tmp" "$STATE/proxy_target"
            "$BB" rm -f "$STATE/proxy_clean_verified"
        fi
    else
        _cur_host=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 4 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/settings get global global_http_proxy_host ) 2>/dev/null || true )
        _need_clean=0
        [ -f "$STATE/proxy_target" ] && _need_clean=1
        case "$_cur_host" in 172.30.*) _need_clean=1 ;; esac
        if [ "$_need_clean" = 1 ]; then
            if ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 6 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/sh -c 'settings put global http_proxy :0; settings delete global http_proxy; settings delete global global_http_proxy_host; settings delete global global_http_proxy_port; settings delete global global_http_proxy_exclusion_list' ) >/dev/null 2>&1; then
                "$BB" rm -f "$STATE/proxy_target"
                : > "$STATE/proxy_clean_verified"
            fi
        else
            "$BB" rm -f "$STATE/proxy_target"
            : > "$STATE/proxy_clean_verified"
        fi
    fi
}

write_dhcp_conf() {
    _wdns=$1
    cat > "$STATE/dhcp.conf" <<EOF
start $GUEST_IP
end $GUEST_IP
interface $IFACE
max_leases 1
remaining yes
auto_time 60
decline_time 60
conflict_time 60
offer_time 60
min_lease 60
lease_file $STATE/dhcp.leases
pidfile $STATE/dhcp.pid
option subnet 255.255.255.0
option router $GATEWAY
option dns $_wdns
option lease 300
EOF
    printf '%s\n' "$_wdns" > "$STATE/dns.tmp"
    "$BB" mv -f "$STATE/dns.tmp" "$STATE/dns"
    printf '%s\n' "$NET_DNS" > "$STATE/dns_mode.tmp"
    "$BB" mv -f "$STATE/dns_mode.tmp" "$STATE/dns_mode"
}

start_dhcp_daemon() {
    _dsec=${1:-0}
    "$BB" rm -f "$STATE/dhcp.pid"
    (
        # Only the numeric FD discovered above may be interpolated into shell syntax.
        case "$NETWORK_FD" in ''|*[!0-9]*) exit 3 ;; esac
        eval "exec ${NETWORK_FD}>&-"
        unset IRIS_NETWORK_LOCKED
        if [ "$_dsec" = 0 ]; then
            exec "$BB" nohup "$BB" udhcpd -f "$STATE/dhcp.conf"
        fi
        exec "$BB" nohup "$BB" timeout -s TERM "$_dsec" "$BB" udhcpd -f "$STATE/dhcp.conf"
    ) > "$STATE/dhcp.log" 2>&1 < /dev/null &
    DHCP_PID=$!
    echo "$DHCP_PID $(ticks "$DHCP_PID")" > "$STATE/dhcp.identity"
    for WAIT in 1 2 3 4 5; do
        [ ! -f "$STATE/dhcp.pid" ] || break
        $BB sleep 1
    done
    read DP DT < "$STATE/dhcp.identity"
    dhcp_is_ours "$DP" "$DT"
    ACTUAL_PID=$($BB cat "$STATE/dhcp.pid")
    case "$ACTUAL_PID" in ''|*[!0-9]*) exit 3 ;; esac
    ACTUAL_TICK=$(ticks "$ACTUAL_PID")
    dhcp_is_ours "$ACTUAL_PID" "$ACTUAL_TICK"
    echo "$ACTUAL_PID $ACTUAL_TICK" > "$STATE/dhcp.actual.identity"
}

sync_guest_dns() {
    _tpdns=$1
    _refresh_dhcp=$2
    [ -f "$RUN/init.identity" ] && [ ! -L "$RUN/init.identity" ] || return 0
    read GPID GTICK < "$RUN/init.identity" 2>/dev/null || return 0
    [ -n "${GPID:-}" ] && [ -n "${GTICK:-}" ] && verify_init "$GPID" "$GTICK" >/dev/null 2>&1 || return 0
    _boot=$( ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 4 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/getprop sys.boot_completed ) 2>/dev/null || true )
    [ "$_boot" = 1 ] || return 0

    _cur_pdns=$($BB cat "$STATE/private_dns" 2>/dev/null || true)
    if [ "$_cur_pdns" != "$_tpdns" ]; then
        if [ "$_tpdns" = off ]; then
            _pd_cmd='settings put global private_dns_mode off'
        else
            _pd_cmd='settings delete global private_dns_mode'
        fi
        if ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 6 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/sh -c "$_pd_cmd" ) >/dev/null 2>&1; then
            printf '%s\n' "$_tpdns" > "$STATE/private_dns.tmp"
            "$BB" mv -f "$STATE/private_dns.tmp" "$STATE/private_dns"
        fi
    fi

    if [ "$_refresh_dhcp" = 1 ]; then
        ( join_groups >/dev/null 2>&1 && exec "$BB" timeout -s KILL 8 "$BASE/irisbox_ns" enter "$GPID" "$GTICK" -- /system/bin/sh -c '/system/bin/ip link set dev eth0 down && /system/bin/busybox sleep 1 && /system/bin/ip link set dev eth0 up' ) >/dev/null 2>&1 || true
    fi
}

# 按owner/current确认本轮再删，不flush全局规则；权限表最后回收。
# 任何失败留账本供重试，旧watchdog不能删新一轮网络。
cleanup_owned() {
    # Exact current owner is the guard against an old watchdog deleting a new session.
    [ -d "$STATE" ] || return 0
    [ ! -L "$STATE" ] && [ ! -L "$STATE/owner" ] || return 3
    [ "$($BB cat "$STATE/owner")" = "$SESSION" ] || return 3
    [ ! -f "$STATE/cleaned" ] || return 0
    current_is_ours || return 3
    # boot_id只描述宿主这次内核启动，不是实例编号。跨宿主重启后，旧网卡、
    # DHCP进程和BPF对象已消失；旧cookie/map ID可能被新内核复用，不能照旧删。
    # 只结束这个已核owner/current的旧账本，保留日志和所有实例数据。
    if [ -f "$STATE/host.boot_id" ]; then
        [ ! -L "$STATE/host.boot_id" ] || return 3
        OLD_BOOT_ID=$($BB cat "$STATE/host.boot_id") || return 3
        echo "$OLD_BOOT_ID" | $BB grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || return 3
        HOST_BOOT_ID=$($BB cat /proc/sys/kernel/random/boot_id) || return 3
        [ -n "$HOST_BOOT_ID" ] || return 3
        if [ "$OLD_BOOT_ID" != "$HOST_BOOT_ID" ]; then
            echo "$SESSION" > "$STATE/cleaned"
            current_is_ours && $BB rm -f "$CURRENT"
            $BB rm -f "$LOG/network.ready"
            echo "Network $ID old-boot journal retired for $SESSION; current kernel untouched"
            return 0
        fi
    fi
    # 未带boot_id的历史账本仍走已有的精确资源核验，不能猜它属于哪次开机。
    if [ -e "/sys/class/net/$IFACE" ]; then link_is_ours || return 3; fi
    FAILED=0
    stop_dhcp || FAILED=1
    remove_proxy_host_input || FAILED=1
    _clean_entries=''
    if [ -f "$STATE/applied_uplinks" ] && [ ! -L "$STATE/applied_uplinks" ]; then
        while IFS= read -r _entry || [ -n "$_entry" ]; do
            [ -n "$_entry" ] || continue
            _clean_entries="$_clean_entries $_entry"
        done < "$STATE/applied_uplinks"
    fi
    for _entry in $_clean_entries; do
        remove_rule_entry "$_entry" || FAILED=1
    done
    if [ -n "$RECORDED_UPLINK" ] && [ "$RECORDED_UPLINK" != none ] && [ -n "$(out_rule_for_pref "$PREF_OUT" "$RECORDED_UPLINK")" ]; then
        remove_rule_entry "${PREF_OUT}:${RECORDED_UPLINK}" || FAILED=1
    fi
    # Full selectors, never 'rule flush' or deletion by priority alone.
    prune_orphan_out_rules ""
    if [ -f "$STATE/route_out" ] || [ -n "$(out_rule)" ]; then
        [ -z "$(out_rule)" ] || FAILED=1
    fi
    if [ -f "$STATE/route_return" ]; then
        "$IP" rule del pref "$PREF_RETURN" to "$SUBNET" lookup main 2>/dev/null || true
        [ -z "$(return_rule)" ] || FAILED=1
    fi
    if [ -f "$STATE/forwarding" ]; then
        FORWARDING_REPLY=$($BB timeout -s KILL 5 "$NDC" ipfwd disable "$REQUESTER" 2>&1)
        echo "$FORWARDING_REPLY" | $BB grep -q '^200 0 ' || FAILED=1
    fi
    if [ -e "/sys/class/net/$IFACE" ]; then
        link_is_ours && "$IP" link del dev "$IFACE" || FAILED=1
    fi
    # Keep permissions until external networking is removed. The cookie + map ID
    # prevents an old supervisor from removing another Guest session's table.
    if [ -s "$STATE/permission" ]; then
        read COOKIE MAP_ID MAP_PATH < "$STATE/permission" || return 3
        "$BASE/iris_inet_policy" remove "$ID" "$COOKIE" "$MAP_ID" || FAILED=1
    fi
    [ "$FAILED" = 0 ] || { echo "Network $ID cleanup incomplete; records preserved" >&2; return 7; }
    echo "$SESSION" > "$STATE/cleaned"
    current_is_ours && $BB rm -f "$CURRENT"
    $BB rm -f "$LOG/network.ready"
    echo "Network $ID cleaned for $SESSION"
}

if [ "$ACTION" = cleanup ]; then
    set +e
    cleanup_owned
    exit $?
fi

if [ "$ACTION" = reconcile ]; then
    [ -d "$STATE" ] && [ ! -L "$STATE" ] && [ ! -L "$STATE/owner" ] && [ ! -L "$STATE/uplink" ] || exit 0
    [ -f "$LOG/network.ready" ] || exit 0
    current_is_ours || exit 0
    [ "$($BB cat "$STATE/owner")" = "$SESSION" ] || exit 0
    [ ! -f "$STATE/cleaned" ] || exit 0
    if [ -f "$STATE/host.boot_id" ]; then
        [ ! -L "$STATE/host.boot_id" ] || exit 3
        OLD_BOOT_ID=$($BB cat "$STATE/host.boot_id") || exit 3
        HOST_BOOT_ID=$($BB cat /proc/sys/kernel/random/boot_id) || exit 3
        [ "$OLD_BOOT_ID" = "$HOST_BOOT_ID" ] || exit 0
    fi
    [ -e "/sys/class/net/$IFACE" ] && link_is_ours || exit 3

    OLD_UPLINK=$UPLINK
    OLD_MODE=$( [ -f "$STATE/mode" ] && [ ! -L "$STATE/mode" ] && $BB cat "$STATE/mode" || echo '' )
    OLD_DNS=$( [ -f "$STATE/dns" ] && [ ! -L "$STATE/dns" ] && $BB cat "$STATE/dns" || echo '' )
    OLD_DNS_MODE=$( [ -f "$STATE/dns_mode" ] && [ ! -L "$STATE/dns_mode" ] && $BB cat "$STATE/dns_mode" || echo '' )
    OLD_PDNS=$( [ -f "$STATE/private_dns" ] && [ ! -L "$STATE/private_dns" ] && $BB cat "$STATE/private_dns" || echo '' )
    load_network_config
    compute_target_egress
    resolve_target_dns

    # 校验当前内核规则集合、代理端口、DNS 配置与 Guest 状态是否已完全处于期望稳态
    _in_sync=1
    [ "$OLD_UPLINK" = "$TARGET_UPLINK" ] && [ "$OLD_MODE" = "$NET_MODE" ] || _in_sync=0
    [ "$OLD_DNS" = "$TARGET_DNS" ] && [ "$OLD_DNS_MODE" = "$NET_DNS" ] && [ "$OLD_PDNS" = "$TARGET_PRIVATE_DNS" ] || _in_sync=0
    if [ "$_in_sync" = 1 ]; then
        _raw_cur=$( [ -f "$STATE/applied_uplinks" ] && "$BB" tr '\n' ' ' < "$STATE/applied_uplinks" 2>/dev/null || true )
        _cur_rules=''
        for _w in $_raw_cur; do _cur_rules="${_cur_rules:+$_cur_rules }$_w"; done
        _exp_rules=''
        for _w in $TARGET_RULES; do _exp_rules="${_exp_rules:+$_exp_rules }$_w"; done
        [ "$_cur_rules" = "$_exp_rules" ] || _in_sync=0
    fi
    if [ "$_in_sync" = 1 ]; then
        for _rule in $TARGET_RULES; do
            parse_rule_entry "$_rule"
            [ -n "$(out_rule_for_pref "$_ep" "$_eu")" ] || { _in_sync=0; break; }
        done
    fi
    if [ "$_in_sync" = 1 ]; then
        _actual_rule_cnt=$(out_rule | "$BB" wc -l)
        _exp_rule_cnt=$(set -- $TARGET_RULES; echo $#)
        [ "$_actual_rule_cnt" = "$_exp_rule_cnt" ] || _in_sync=0
    fi
    if [ "$_in_sync" = 1 ]; then
        if [ -n "$TARGET_PROXY_PORT" ]; then
            [ -f "$STATE/proxy_input" ] && [ "$($BB cat "$STATE/proxy_input" 2>/dev/null || true)" = "$TARGET_PROXY_PORT" ] || _in_sync=0
            [ -f "$STATE/proxy_target" ] && [ "$($BB cat "$STATE/proxy_target" 2>/dev/null || true)" = "${GATEWAY}:${TARGET_PROXY_PORT}" ] || _in_sync=0
        else
            [ ! -f "$STATE/proxy_input" ] && [ ! -f "$STATE/proxy_target" ] && [ -f "$STATE/proxy_clean_verified" ] || _in_sync=0
        fi
    fi
    [ "$_in_sync" = 0 ] || exit 0

    # 热切换原则：先写入并挂载全部目标策略规则，再精确回收不再使用的旧规则
    for _rule in $TARGET_RULES; do
        parse_rule_entry "$_rule"
        apply_rule_entry "$_ep" "$_eu"
    done
    if [ -f "$STATE/applied_uplinks" ] && [ ! -L "$STATE/applied_uplinks" ]; then
        _to_remove=''
        while IFS= read -r _entry || [ -n "$_entry" ]; do
            [ -n "$_entry" ] || continue
            case " $TARGET_RULES " in
                *" $_entry "*) ;;
                *) _to_remove="$_to_remove $_entry" ;;
            esac
        done < "$STATE/applied_uplinks"
        for _entry in $_to_remove; do
            remove_rule_entry "$_entry"
        done
    fi
    prune_orphan_out_rules "$TARGET_RULES"
    if [ -n "$TARGET_PROXY_PORT" ]; then
        apply_proxy_host_input "$TARGET_PROXY_PORT"
    else
        remove_proxy_host_input
    fi

    printf '%s\n' "$TARGET_UPLINK" > "$STATE/uplink.tmp"
    "$BB" mv -f "$STATE/uplink.tmp" "$STATE/uplink"
    printf '%s\n' "$NET_MODE" > "$STATE/mode.tmp"
    "$BB" mv -f "$STATE/mode.tmp" "$STATE/mode"
    _dns_changed=0
    if [ "$OLD_DNS" != "$TARGET_DNS" ]; then
        _dns_changed=1
        _sec_lim=$( [ -f "$STATE/seconds_limit" ] && [ ! -L "$STATE/seconds_limit" ] && $BB cat "$STATE/seconds_limit" 2>/dev/null || echo 0 )
        case "$_sec_lim" in 0|180|300|600|1800) ;; *) _sec_lim=0 ;; esac
        stop_dhcp
        write_dhcp_conf "$TARGET_DNS"
        start_dhcp_daemon "$_sec_lim"
    elif [ "$OLD_DNS_MODE" != "$NET_DNS" ]; then
        printf '%s\n' "$NET_DNS" > "$STATE/dns_mode.tmp"
        "$BB" mv -f "$STATE/dns_mode.tmp" "$STATE/dns_mode"
    fi
    sync_guest_proxy "$NET_MODE" "$TARGET_PROXY_PORT"
    sync_guest_dns "$TARGET_PRIVATE_DNS" "$_dns_changed"
    if [ "$OLD_UPLINK" != "$TARGET_UPLINK" ] || [ "$OLD_MODE" != "$NET_MODE" ] || [ "$_dns_changed" = 1 ]; then
        echo "Network $ID reconciled for $SESSION: ${OLD_MODE:-init}($OLD_UPLINK) -> $NET_MODE($TARGET_UPLINK) dns=[$TARGET_DNS]"
    fi
    exit 0
fi

SECONDS_LIMIT=$5
case "$SECONDS_LIMIT" in 0|180|300|600|1800) ;; *) exit 2 ;; esac
preinit_is_ours
[ ! -e "$CURRENT" ] && [ ! -e "$STATE" ] || { echo 'Prior network owner requires exact cleanup' >&2; exit 4; }
[ ! -e "/sys/class/net/$IFACE" ] || exit 4
[ -z "$(return_rule)$(out_rule)" ] || exit 4
load_network_config
compute_target_egress
resolve_target_dns
UPLINK=$TARGET_UPLINK
$BB mkdir "$STATE"
printf '%s\n' "$SECONDS_LIMIT" > "$STATE/seconds_limit"
printf '%s\n' "$UPLINK" > "$STATE/uplink"
printf '%s\n' "$NET_MODE" > "$STATE/mode"
: > "$STATE/applied_uplinks"
echo "$SESSION" > "$STATE/owner"
$BB cat /proc/sys/kernel/random/boot_id > "$STATE/host.boot_id"
[ -s "$STATE/host.boot_id" ] || exit 3
echo "$SESSION $STATE" > "$CURRENT"
PREPARED=0
# 先记意图再改内核，未完成时EXIT沿同一账本回收，覆盖半途失败。
prepare_exit() {
    PREPARE_EXIT_STATUS=$?
    trap - EXIT
    if [ "$PREPARED" != 1 ]; then
        set +e
        cleanup_owned
        echo "Network prepare failed (exit $PREPARE_EXIT_STATUS)" > "$LOG/network.error"
    fi
    exit "$PREPARE_EXIT_STATUS"
}
trap prepare_exit EXIT
trap 'exit 7' HUP INT TERM
EXPECTED_NET=$($BB readlink /proc/$PID/ns/net)
echo "$EXPECTED_NET" > "$STATE/netns"
# Register this netns before init. Android PermissionMonitor will update ONLY
# its private UID table, mounted by inner.sh after this preparation completes.
"$BASE/iris_inet_policy" register "$ID" "$PID" "$TICK" > "$STATE/permission"
# This Android iproute ends generic parsing at alias: put it last on the peer.
echo "$SESSION" > "$STATE/create.intent"
"$IP" link add name "$IFACE" type veth peer name eth0 netns "$PID" alias "$ALIAS"
$BB cat "/sys/class/net/$IFACE/ifindex" > "$STATE/ifindex"
"$IP" link set dev "$IFACE" alias "$ALIAS"
link_is_ours
"$IP" addr add "$GATEWAY/24" dev "$IFACE"
"$IP" link set dev "$IFACE" up
# Journal intent before each change, so partial failure can remove an applied change.
: > "$STATE/route_return"
"$IP" rule add pref "$PREF_RETURN" to "$SUBNET" lookup main
if [ -n "$TARGET_RULES" ]; then
    for _rule in $TARGET_RULES; do
        parse_rule_entry "$_rule"
        apply_rule_entry "$_ep" "$_eu"
    done
else
    echo "Network $ID starting in placeholder mode (mode=$NET_MODE uplink=$UPLINK)"
fi
if [ -n "$TARGET_PROXY_PORT" ]; then
    apply_proxy_host_input "$TARGET_PROXY_PORT"
fi
# 下面here-document由 write_dhcp_conf 维护，统一供启动与热切换复用。
write_dhcp_conf "$TARGET_DNS"
start_dhcp_daemon "$SECONDS_LIMIT"
preinit_is_ours
[ "$($BB readlink /proc/$PID/ns/net)" = "$EXPECTED_NET" ]
link_is_ours
echo "$SESSION" > "$LOG/network.ready.tmp"
$BB mv "$LOG/network.ready.tmp" "$LOG/network.ready"
PREPARED=1
echo "Network $ID prepared for $SESSION (uplink=$UPLINK); Guest Ethernet owns DHCP and routes"
