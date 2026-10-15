#!/system/bin/sh
# 阅读入口：Host prepare/cleanup与切根后的Guest guest三个分支。
# Host接veth、DHCP、限定转发/NAT和私有UID表；Guest框架/netd负责自己的默认Network。
# SESSION=Host PID:start tick；STATE是本次资源账本，失败/停止时据此精确回收。
# 有IP/ready不证明普通UID能建socket，更不证明Google登录或Play认证通过。
# One instance's network lifecycle; no Guest ndc/routectrl workarounds.
# Host: prepare ID PID:TICK LOG SECONDS | cleanup ID PID:TICK LOG
# Guest after pivot + private proc: guest ID PID:TICK 'net:[NUMBER]'
# inner writes LOG/network.request before init and waits for identical network.ready.
# Existing watchdog owns lifetime; its every exit and ctl stop call exact cleanup.
set -eu
BB=/data/rootvmos/host/bin/busybox
ACTION=${1:-}
ID=${2:-}
SESSION=${3:-}
valid_instance_id() {
    case "$1" in ''|*[!0-9]*|0*) return 2 ;; esac
    [ "${#1}" -eq 4 ] && [ "$1" -ge 1007 ] && [ "$1" -le 1134 ]
}
valid_instance_id "$ID" || { echo 'Invalid instance ID' >&2; exit 2; }
case "$SESSION" in *[!0-9:]*|:*|*:|*:*:*) exit 2 ;; esac
PID=${SESSION%:*}
TICK=${SESSION#*:}
[ "$PID" != "$SESSION" ] && [ "$PID" -gt 1 ] && [ "$TICK" -gt 0 ] || exit 2

# This branch cannot source Host common.sh: the old root has been detached.
# 切根后common的Host路径已消失，此分支只开放私有eth0参数。
if [ "$ACTION" = guest ]; then
    BB=/system/bin/busybox
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
        $BB mount -o bind "$CONF" "$CONF"
        $BB mount -o remount,bind,rw "$CONF"
    done
    echo "Guest $ID private eth0 configuration ready"
    exit 0
fi

case "$ACTION:$#" in prepare:5|cleanup:4) ;; *) exit 2 ;; esac
. /data/rootvmos/runtime/common.sh "$ID"
host_only || exit 3
LOG=$4
case "$LOG" in "$RUN"/*) ;; *) exit 3 ;; esac
LOG_NAME=${LOG#"$RUN"/}
case "$LOG_NAME" in ''|*[!0-9_]*) exit 3 ;; esac
[ -d "$LOG" ] && [ ! -L "$LOG" ] && [ ! -L "$RUN" ] || exit 3
[ "$($BB readlink -f "$LOG")" = "$LOG" ] || exit 3
STATE=$LOG/network
CURRENT=$RUN/network.current
IFACE=rv${ID}h
ALIAS=ROOTVMOS_NET_${ID}_${PID}_${TICK}
# 产品保留ID范围对应独立172.30网段，避免与旧Iris的10.*地址重叠。
OCTET3=$((ID - 1000))
SUBNET=172.30.$OCTET3.0/24
GUEST_IP=172.30.$OCTET3.2
GATEWAY=172.30.$OCTET3.1
# Explicit first implementation: Wi-Fi uplink already established by Host Android.
# Interface/table migration is a future feature; do not guess a new uplink at runtime.
UPLINK=wlan0
# Keep rules before Android's later catch-all rules. New instances can share
# priorities because their destination/iif selectors remain unique.
PREF_RETURN=10909
PREF_OUT=12009
case "$ID" in 7|8) PREF_RETURN=1090$ID; PREF_OUT=1200$ID ;; esac
return_rule() { "$IP" rule show | "$BB" awk -v p="$PREF_RETURN:" -v n="$SUBNET" '$1==p && index(" " $0 " "," to " n " ") {print}'; }
out_rule() { "$IP" rule show | "$BB" awk -v p="$PREF_OUT:" -v i="$IFACE" '$1==p && index(" " $0 " "," iif " i " ") {print}'; }
TAG=ROOTVMOS_NET_${ID}_${PID}_${TICK}
REQUESTER=rootvmos_${ID}_${PID}_${TICK}
IP=/system/bin/ip
IPT=/system/bin/iptables
NDC=/system/bin/ndc
umask 077
[ ! -L "$RUN/network.lock" ] && [ ! -L "$CURRENT" ] || exit 3
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
    $BB tr '\000' '\n' < "/proc/$PID/cmdline" | $BB grep -qx "$BASE/inner.sh" || return 3
    [ "$($BB awk '/^NSpid:/ {print $NF}' "/proc/$PID/status")" = 1 ] || return 3
    $BB grep -F "upperdir=$RUN/upper," "/proc/$PID/mountinfo" >/dev/null || return 3
    for NS in pid mnt net ipc uts cgroup; do
        [ "$($BB readlink /proc/$PID/ns/$NS)" != "$($BB readlink /proc/1/ns/$NS)" ] || return 3
    done
    $BB grep -F ":memory:/rootvmos_$ID/managed" "/proc/$PID/cgroup" >/dev/null || return 3
    $BB grep -F "0::/rootvmos_$ID/managed" "/proc/$PID/cgroup" >/dev/null || return 3
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
    "$IPT" -w 3 -t "$TABLE" -C "$@" >/dev/null 2>&1
    CHECK=$?
    case "$CHECK" in
        0) "$IPT" -w 3 -t "$TABLE" -D "$@" ;;
        1) return 0 ;;
        *) return "$CHECK" ;;
    esac
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
    delete_iptables_rule filter FORWARD -i "$IFACE" -s "$GUEST_IP/32" -o "$UPLINK" \
        -m comment --comment "${TAG}_OUT" -j ACCEPT || FAILED=1
    delete_iptables_rule filter FORWARD -i "$UPLINK" -o "$IFACE" -d "$GUEST_IP/32" \
        -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment "${TAG}_RETURN" -j ACCEPT || FAILED=1
    delete_iptables_rule nat POSTROUTING -s "$GUEST_IP/32" -o "$UPLINK" \
        -m comment --comment "${TAG}_NAT" -j MASQUERADE || FAILED=1
    # Full selectors, never 'rule flush' or deletion by priority alone.
    if [ -f "$STATE/route_out" ]; then
        "$IP" rule del pref "$PREF_OUT" iif "$IFACE" lookup "$UPLINK" 2>/dev/null || true
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

SECONDS_LIMIT=$5
case "$SECONDS_LIMIT" in 0|180|300|600|1800) ;; *) exit 2 ;; esac
preinit_is_ours
[ ! -e "$CURRENT" ] && [ ! -e "$STATE" ] || { echo 'Prior network owner requires exact cleanup' >&2; exit 4; }
[ ! -e "/sys/class/net/$IFACE" ] || exit 4
[ -z "$(return_rule)$(out_rule)" ] || exit 4
"$IP" link show dev "$UPLINK" >/dev/null
"$IP" route show table "$UPLINK" | $BB grep -q '^default ' || { echo 'Host wlan0 table has no default route' >&2; exit 4; }
$BB mkdir "$STATE"
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
: > "$STATE/route_out"
"$IP" rule add pref "$PREF_OUT" iif "$IFACE" lookup "$UPLINK"
"$IPT" -w 3 -I FORWARD 1 -i "$IFACE" -s "$GUEST_IP/32" -o "$UPLINK" \
    -m comment --comment "${TAG}_OUT" -j ACCEPT
"$IPT" -w 3 -I FORWARD 1 -i "$UPLINK" -o "$IFACE" -d "$GUEST_IP/32" \
    -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment "${TAG}_RETURN" -j ACCEPT
"$IPT" -w 3 -t nat -A POSTROUTING -s "$GUEST_IP/32" -o "$UPLINK" \
    -m comment --comment "${TAG}_NAT" -j MASQUERADE
: > "$STATE/forwarding"
RESULT=$($BB timeout -s KILL 5 "$NDC" ipfwd enable "$REQUESTER" 2>&1)
echo "$RESULT" | $BB grep -q '^200 0 '
# 下面here-document是DHCP数据，不是Shell代码，勿把流程注释插入其中。
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
option dns 1.1.1.1 8.8.8.8
option lease 300
EOF
(
    # Only the numeric FD discovered above may be interpolated into shell syntax.
    case "$NETWORK_FD" in ''|*[!0-9]*) exit 3 ;; esac
    eval "exec ${NETWORK_FD}>&-"
    unset IRIS_NETWORK_LOCKED
    if [ "$SECONDS_LIMIT" = 0 ]; then
        exec "$BB" nohup "$BB" udhcpd -f "$STATE/dhcp.conf"
    fi
    exec "$BB" nohup "$BB" timeout -s TERM "$SECONDS_LIMIT" "$BB" udhcpd -f "$STATE/dhcp.conf"
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
preinit_is_ours
[ "$($BB readlink /proc/$PID/ns/net)" = "$EXPECTED_NET" ]
link_is_ours
echo "$SESSION" > "$LOG/network.ready.tmp"
$BB mv "$LOG/network.ready.tmp" "$LOG/network.ready"
PREPARED=1
echo "Network $ID prepared for $SESSION; Guest Ethernet owns DHCP and routes"
