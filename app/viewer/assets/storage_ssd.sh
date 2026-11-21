#!/system/bin/sh
# 外置 ext4 SSD（/data/irisbox）检测、写前拦截、安全挂载与 Magisk 开机恢复脚本。
# 仅使用 Android /system/bin 系统工具，不依赖 /data/irisbox/host/bin/busybox（挂载前尚不可用）。
set -eu

ACTION=${1:-status}
ARG2=${2:-0}
HOME_DIR=/data/irisbox
CONF=/data/adb/irisbox_storage.conf
HOOK=/data/adb/service.d/irisbox-storage.sh
MARKER=$HOME_DIR/.irisbox_ssd_marker

ssd_fail() { echo "IRIS_SSD_ERROR=$1" >&2; exit 4; }

valid_uuid() {
    case "$1" in
        ''|*[!0-9a-fA-F-]*|-*|*-) return 1 ;;
    esac
    [ "${#1}" -ge 8 ] && [ "${#1}" -le 64 ]
}

json_escape() {
    printf '%s' "$1" | /system/bin/sed 's/\\/\\\\/g; s/"/\\"/g'
}

read_conf_uuid() {
    BOUND_UUID=''
    [ ! -L /data/adb ] || ssd_fail UNSAFE_PATH
    [ ! -L "$CONF" ] || ssd_fail UNSAFE_PATH
    if [ -e "$CONF" ]; then
        [ -f "$CONF" ] && [ "$(/system/bin/stat -c '%u:%g:%a:%h' "$CONF")" = 0:0:600:1 ] || ssd_fail CONF_INVALID
        [ "$(/system/bin/wc -c < "$CONF")" -le 128 ] || ssd_fail CONF_INVALID
        read _m _u _e < "$CONF" || ssd_fail CONF_INVALID
        [ "$_m" = IRISBOX_SSD_V1 ] && [ -z "$_e" ] && valid_uuid "$_u" || ssd_fail CONF_INVALID
        BOUND_UUID=$_u
    fi
}

is_mounted_ext4() {
    [ ! -L "$HOME_DIR" ] && [ -d "$HOME_DIR" ] || return 1
    _d_data=$(/system/bin/stat -c '%d' /data 2>/dev/null || echo 0)
    _d_iris=$(/system/bin/stat -c '%d' "$HOME_DIR" 2>/dev/null || echo 0)
    [ "$_d_iris" != 0 ] && [ "$_d_iris" != "$_d_data" ] || return 1
    /system/bin/grep -qE '^[^ ]+ /data/irisbox ext4 ' /proc/mounts
}

mounted_dev() {
    /system/bin/awk '$2=="/data/irisbox" && $3=="ext4" {print $1; exit}' /proc/mounts
}

find_dev_by_uuid() {
    _target=$1
    for _line in $(/system/bin/blkid 2>/dev/null | /system/bin/grep -F 'TYPE="ext4"' | /system/bin/cut -d: -f1); do
        [ -b "$_line" ] || continue
        _info=$(/system/bin/blkid "$_line" 2>/dev/null || true)
        case "$_info" in
            *'UUID="'"$_target"'"'*)
                printf '%s\n' "$_line"
                return 0
                ;;
        esac
    done
    return 1
}

marker_matches() {
    _expected=$1
    [ ! -L "$MARKER" ] && [ -f "$MARKER" ] || return 1
    [ "$(/system/bin/stat -c '%u:%g:%a:%h' "$MARKER")" = 0:0:600:1 ] || return 1
    [ "$(/system/bin/wc -c < "$MARKER")" -le 128 ] || return 1
    read _mm _mu _me < "$MARKER" || return 1
    [ "$_mm" = IRISBOX_SSD_V1 ] && [ "$_mu" = "$_expected" ] && [ -z "$_me" ]
}

hook_text() {
    cat <<'IRISBOX_STORAGE_BOOT'
#!/system/bin/sh
# IRISBOX_MANAGED_STORAGE_V1
umask 077
CONF=/data/adb/irisbox_storage.conf
LOG=/data/adb/irisbox-storage.log
HOME_DIR=/data/irisbox
[ ! -L /data/adb ] && [ ! -L "$CONF" ] && [ -f "$CONF" ] || exit 0
read MAGIC UUID EXTRA < "$CONF" || exit 0
[ "$MAGIC" = IRISBOX_SSD_V1 ] && [ -n "$UUID" ] && [ -z "$EXTRA" ] || exit 0
case "$UUID" in *[!0-9a-fA-F-]*) exit 0 ;; esac

export IRISBOX_SSD_UUID="$UUID"
exec /system/bin/nsenter -t 1 -m /system/bin/sh <<'IRISBOX_MOUNT_INNER' > "$LOG" 2>&1
UUID=${IRISBOX_SSD_UUID:-}
[ -n "$UUID" ] || exit 0
HOME_DIR=/data/irisbox
MARKER=$HOME_DIR/.irisbox_ssd_marker

already_ok() {
    [ ! -L "$HOME_DIR" ] && [ -d "$HOME_DIR" ] || return 1
    D1=$(/system/bin/stat -c '%d' /data 2>/dev/null || echo 0)
    D2=$(/system/bin/stat -c '%d' "$HOME_DIR" 2>/dev/null || echo 0)
    [ "$D2" != 0 ] && [ "$D2" != "$D1" ] || return 1
    /system/bin/grep -qE '^[^ ]+ /data/irisbox ext4 ' /proc/mounts || return 1
    [ -f "$MARKER" ] && [ ! -L "$MARKER" ] || return 1
    read M U E < "$MARKER" || return 1
    [ "$M" = IRISBOX_SSD_V1 ] && [ "$U" = "$UUID" ] && [ -z "$E" ]
}

if already_ok; then
    echo "already mounted: $UUID"
    exit 0
fi

DEV=''
N=0
while [ "$N" -lt 20 ]; do
    for CAND in $(/system/bin/blkid 2>/dev/null | /system/bin/grep -F 'TYPE="ext4"' | /system/bin/cut -d: -f1); do
        case "$CAND" in
            /dev/block/sd*|/dev/block/nvme*|/dev/block/vold/public:*)
                INFO=$(/system/bin/blkid "$CAND" 2>/dev/null || true)
                case "$INFO" in
                    *'UUID="'"$UUID"'"'*) DEV=$CAND; break ;;
                esac
                ;;
        esac
    done
    [ -n "$DEV" ] && break
    N=$((N + 1))
    /system/bin/sleep 1
done

if [ -z "$DEV" ]; then
    echo "SSD_NOT_FOUND: uuid=$UUID"
    exit 4
fi

if [ -L "$HOME_DIR" ]; then
    echo "UNSAFE_MOUNTPOINT_SYMLINK"
    exit 4
fi
if [ -e "$HOME_DIR" ]; then
    [ -d "$HOME_DIR" ] || { echo "MOUNTPOINT_NOT_DIR"; exit 4; }
    if [ -n "$(/system/bin/ls -A "$HOME_DIR" 2>/dev/null || true)" ]; then
        echo "MOUNTPOINT_NOT_EMPTY: refusing to mount over internal /data files"
        exit 4
    fi
else
    /system/bin/mkdir -m 0700 "$HOME_DIR"
fi
/system/bin/chown 0:0 "$HOME_DIR"
/system/bin/chmod 0700 "$HOME_DIR"

/system/bin/e2fsck -p "$DEV" || true
/system/bin/mount -t ext4 -o noatime,errors=remount-ro "$DEV" "$HOME_DIR" || { echo "MOUNT_FAILED: $DEV"; exit 4; }
if already_ok; then
    echo "mounted $DEV -> $HOME_DIR ($UUID)"
    exit 0
fi
echo "MARKER_MISMATCH_AFTER_MOUNT"
/system/bin/umount "$HOME_DIR" || true
exit 4
IRISBOX_MOUNT_INNER
IRISBOX_STORAGE_BOOT
}

hook_check() {
    for _p in /data/adb /data/adb/service.d "$HOOK"; do
        [ ! -L "$_p" ] || ssd_fail UNSAFE_BOOT_HOOK
    done
    if [ -e "$HOOK" ]; then
        [ -f "$HOOK" ] && [ "$(/system/bin/stat -c '%u:%g:%a:%h' "$HOOK")" = 0:0:700:1 ] || ssd_fail FOREIGN_BOOT_HOOK
        [ "$(/system/bin/sed -n '2p' "$HOOK" 2>/dev/null)" = '# IRISBOX_MANAGED_STORAGE_V1' ] || ssd_fail FOREIGN_BOOT_HOOK
        hook_text | /system/bin/cmp -s - "$HOOK" || return 1
        return 0
    fi
    return 1
}

verify_guard() {
    _req=${1:-0}
    read_conf_uuid
    if [ "$_req" != 1 ] && [ -z "$BOUND_UUID" ]; then
        return 0
    fi
    [ -n "$BOUND_UUID" ] || ssd_fail SSD_UNBOUND
    [ ! -L "$HOME_DIR" ] || ssd_fail UNSAFE_PATH
    is_mounted_ext4 || ssd_fail SSD_NOT_MOUNTED
    marker_matches "$BOUND_UUID" || ssd_fail SSD_MARKER_MISMATCH
}

list_candidates_json() {
    _first=1
    printf '['
    for _blk in /dev/block/sd* /dev/block/nvme*; do
        [ -b "$_blk" ] || continue
        case "$_blk" in
            /dev/block/sda|/dev/block/sdb|/dev/block/sdc|/dev/block/sdd)
                [ ! -b "${_blk}1" ] || continue
                ;;
        esac
        _info=$(/system/bin/blkid "$_blk" 2>/dev/null || true)
        case "$_info" in
            *'TYPE="ext4"'*) ;;
            *) continue ;;
        esac
        _uuid=$(printf '%s\n' "$_info" | /system/bin/sed -n 's/.*UUID="\([^"]*\)".*/\1/p')
        valid_uuid "$_uuid" || continue
        _label=$(printf '%s\n' "$_info" | /system/bin/sed -n 's/.*LABEL="\([^"]*\)".*/\1/p')
        _base=${_blk##*/}
        _blocks=$(/system/bin/awk -v d="$_base" '$4==d {print $3; exit}' /proc/partitions 2>/dev/null || echo 0)
        _mib=$(( _blocks / 1024 ))
        [ "$_first" = 1 ] || printf ','
        _first=0
        printf '{"dev":"%s","uuid":"%s","label":"%s","sizeMiB":%s}' \
            "$(json_escape "$_blk")" "$(json_escape "$_uuid")" "$(json_escape "$_label")" "$_mib"
    done
    printf ']'
}

emit_status() {
    _req=${1:-0}
    read_conf_uuid
    _mounted=false
    _marker_ok=false
    _non_empty=false
    _hook_ok=false
    _dev=''
    _free_mib=0
    if [ -e "$HOME_DIR" ] && [ ! -L "$HOME_DIR" ] && [ -d "$HOME_DIR" ]; then
        if is_mounted_ext4; then
            _mounted=true
            _dev=$(mounted_dev || true)
            _free_kib=$(/system/bin/df -Pk "$HOME_DIR" 2>/dev/null | /system/bin/awk 'NR==2 {print $4}')
            case "$_free_kib" in ''|*[!0-9]*) _free_kib=0 ;; esac
            _free_mib=$(( _free_kib / 1024 ))
            if [ -n "$BOUND_UUID" ] && marker_matches "$BOUND_UUID"; then
                _marker_ok=true
            fi
        elif [ -n "$(/system/bin/ls -A "$HOME_DIR" 2>/dev/null || true)" ]; then
            _non_empty=true
        fi
    fi
    if hook_check; then
        _hook_ok=true
    fi
    _req_json=false
    [ "$_req" != 1 ] || _req_json=true
    printf 'IRIS_SSD_JSON={"requireExternal":%s,"boundUuid":"%s","mounted":%s,"markerOk":%s,"hookInstalled":%s,"mountPointNonEmpty":%s,"device":"%s","freeMiB":%s,"candidates":%s}\n' \
        "$_req_json" "$(json_escape "$BOUND_UUID")" "$_mounted" "$_marker_ok" "$_hook_ok" "$_non_empty" "$(json_escape "$_dev")" "$_free_mib" "$(list_candidates_json)"
}

check_no_running_instances() {
    if [ -d "$HOME_DIR/runtime/instances" ]; then
        for _id_file in "$HOME_DIR"/runtime/instances/*/init.identity; do
            [ -f "$_id_file" ] || continue
            read _pid _tick < "$_id_file" || continue
            if [ -n "$_pid" ] && [ -n "$_tick" ] && [ -f "/proc/$_pid/stat" ]; then
                _cur=$(/system/bin/awk '{print $22}' "/proc/$_pid/stat" 2>/dev/null || true)
                [ "$_cur" != "$_tick" ] || ssd_fail STOP_INSTANCES
            fi
        done
    fi
}

case "$ACTION" in
    status)
        emit_status "$ARG2"
        ;;
    verify-guard)
        verify_guard "$ARG2"
        ;;
    mount-bind)
        UUID=${2:-}
        valid_uuid "$UUID" || ssd_fail INVALID_UUID
        read_conf_uuid
        if [ -n "$BOUND_UUID" ] && [ "$BOUND_UUID" != "$UUID" ]; then
            is_mounted_ext4 && ssd_fail ALREADY_BOUND_OTHER
        fi
        if is_mounted_ext4; then
            if [ -e "$MARKER" ] && ! marker_matches "$UUID"; then
                ssd_fail SSD_MARKER_MISMATCH
            fi
        else
            DEV=$(find_dev_by_uuid "$UUID" || true)
            [ -n "$DEV" ] || ssd_fail SSD_NOT_FOUND
            if [ -L "$HOME_DIR" ]; then ssd_fail UNSAFE_PATH; fi
            if [ -e "$HOME_DIR" ]; then
                [ -d "$HOME_DIR" ] || ssd_fail UNSAFE_PATH
                [ -z "$(/system/bin/ls -A "$HOME_DIR" 2>/dev/null || true)" ] || ssd_fail MOUNTPOINT_NOT_EMPTY
            else
                /system/bin/mkdir -m 0700 "$HOME_DIR"
            fi
            /system/bin/chown 0:0 "$HOME_DIR"
            /system/bin/chmod 0700 "$HOME_DIR"
            /system/bin/e2fsck -p "$DEV" >/dev/null 2>&1 || true
            /system/bin/mount -t ext4 -o noatime,errors=remount-ro "$DEV" "$HOME_DIR" || ssd_fail MOUNT_FAILED
            is_mounted_ext4 || ssd_fail MOUNT_FAILED
        fi
        /system/bin/chown 0:0 "$HOME_DIR"
        /system/bin/chmod 0700 "$HOME_DIR"
        if [ -e "$MARKER" ]; then
            marker_matches "$UUID" || ssd_fail SSD_MARKER_MISMATCH
        else
            umask 077
            printf 'IRISBOX_SSD_V1 %s\n' "$UUID" > "$MARKER.tmp"
            /system/bin/chown 0:0 "$MARKER.tmp"
            /system/bin/chmod 0600 "$MARKER.tmp"
            /system/bin/mv "$MARKER.tmp" "$MARKER"
        fi
        umask 077
        [ -d /data/adb/service.d ] || /system/bin/mkdir -p /data/adb/service.d
        if ! hook_check; then
            :
        fi
        hook_text > "$HOOK.tmp"
        /system/bin/chown 0:0 "$HOOK.tmp"
        /system/bin/chmod 0700 "$HOOK.tmp"
        /system/bin/mv "$HOOK.tmp" "$HOOK"
        printf 'IRISBOX_SSD_V1 %s\n' "$UUID" > "$CONF.tmp"
        /system/bin/chown 0:0 "$CONF.tmp"
        /system/bin/chmod 0600 "$CONF.tmp"
        /system/bin/mv "$CONF.tmp" "$CONF"
        emit_status 1
        ;;
    unmount)
        check_no_running_instances
        if is_mounted_ext4; then
            /system/bin/umount "$HOME_DIR" || ssd_fail UNMOUNT_FAILED
        fi
        emit_status "$ARG2"
        ;;
    *)
        ssd_fail INVALID_ACTION
        ;;
esac
