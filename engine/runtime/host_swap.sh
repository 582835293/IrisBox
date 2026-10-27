#!/system/bin/sh
# Owned host swap only. Never alter swappiness, foreign swap, or guest data.
set -eu
HS_ACTION=${1:-status}
. /data/irisbox/runtime/common.sh 7
host_only || exit 3
HS_DIR=/data/irisbox/host/swap
HS_HOOK=/data/adb/service.d/irisbox-swap.sh
HS_LOCK=/data/irisbox/host/swap-start.lock
hs_fail() { echo "IRIS_SWAP_ERROR=$1" >&2; exit 4; }
for HS_P in /data/irisbox /data/irisbox/host "$HS_DIR" "$HS_LOCK"; do
    [ ! -L "$HS_P" ] || hs_fail UNSAFE_PATH
done
[ ! -e /data/irisbox/engine-upgrade.pending ] || hs_fail ENGINE_MAINTENANCE
hs_size() { case "$1" in 0|1024|2048|4096) return 0 ;; *) return 1 ;; esac; }
hs_active() { "$BB" awk -v f="$1" 'NR>1 && $1==f {found=1} END {exit !found}' /proc/swaps; }
hs_file() {
    HS_F=$HS_DIR/swap-$1.img
    HS_R=$HS_DIR/swap-$1.record
    [ ! -L "$HS_F" ] && [ ! -L "$HS_R" ] || hs_fail UNSAFE_PATH
    [ -f "$HS_F" ] && [ -f "$HS_R" ] || hs_fail FILE_RECEIPT_MISSING
    [ "$("$BB" stat -c '%u:%g:%a:%h' "$HS_F")" = 0:0:600:1 ] || hs_fail FILE_IDENTITY
    [ "$("$BB" stat -c '%u:%g:%a:%h' "$HS_R")" = 0:0:600:1 ] || hs_fail FILE_IDENTITY
    [ "$("$BB" wc -c < "$HS_R")" -le 100 ] || hs_fail FILE_IDENTITY
    read HS_MAGIC HS_BYTES HS_INODE HS_EXTRA < "$HS_R"
    [ "$HS_MAGIC" = IRISBOX_SWAP_V1 ] && [ -z "$HS_EXTRA" ] || hs_fail FILE_IDENTITY
    [ "$HS_BYTES" = "$("$BB" awk -v m="$1" 'BEGIN {printf "%.0f",m*1048576}')" ] || hs_fail FILE_IDENTITY
    [ "$("$BB" stat -c '%s:%d:%i' "$HS_F")" = "$HS_BYTES:$HS_INODE" ] || hs_fail FILE_IDENTITY
}
hs_read() {
    HS_SAVED=0 HS_AUTO=0
    [ ! -L "$HS_DIR/settings" ] || hs_fail UNSAFE_PATH
    if [ -e "$HS_DIR/settings" ]; then
        [ -f "$HS_DIR/settings" ] && [ "$("$BB" stat -c '%u:%g:%a:%h' "$HS_DIR/settings")" = 0:0:600:1 ] || hs_fail SETTINGS_INVALID
        [ "$("$BB" wc -c < "$HS_DIR/settings")" -le 60 ] || hs_fail SETTINGS_INVALID
        read HS_FORMAT HS_SAVED HS_AUTO HS_EXTRA < "$HS_DIR/settings"
        [ "$HS_FORMAT" = IRISBOX_SWAP_V1 ] && [ -z "$HS_EXTRA" ] && hs_size "$HS_SAVED" || hs_fail SETTINGS_INVALID
        case "$HS_AUTO" in 0|1) ;; *) hs_fail SETTINGS_INVALID ;; esac
        [ "$HS_SAVED" != 0 ] || [ "$HS_AUTO" = 0 ] || hs_fail SETTINGS_INVALID
    fi
}
hs_hook_text() {
    cat <<'IRISBOX_SWAP_BOOT'
#!/system/bin/sh
# IRISBOX_MANAGED_HOST_SWAP_V1
umask 077
for P in /data/irisbox /data/irisbox/runtime /data/irisbox/host /data/irisbox/host/swap /data/irisbox/host/swap/boot.log; do
    [ ! -L "$P" ] || exit 3
done
[ ! -e /data/irisbox/engine-upgrade.pending ] || exit 4
[ -f /data/irisbox/runtime/host_swap.sh ] || exit 3
exec /data/irisbox/host/bin/busybox nsenter -t 1 -m /system/bin/sh /data/irisbox/runtime/host_swap.sh restore > /data/irisbox/host/swap/boot.log 2>&1
IRISBOX_SWAP_BOOT
}
hs_hook_check() {
    for HS_P in /data/adb /data/adb/service.d "$HS_HOOK"; do [ ! -L "$HS_P" ] || hs_fail UNSAFE_BOOT_HOOK; done
    if [ -e "$HS_HOOK" ]; then
        [ -f "$HS_HOOK" ] && [ "$("$BB" stat -c '%u:%g:%a:%h' "$HS_HOOK")" = 0:0:700:1 ] || hs_fail FOREIGN_BOOT_HOOK
        hs_hook_text | "$BB" cmp -s - "$HS_HOOK" || hs_fail FOREIGN_BOOT_HOOK
    fi
}
hs_stopped() {
    for HS_G in /sys/fs/cgroup/irisbox_*; do
        [ ! -e "$HS_G/cgroup.events" ] || "$BB" grep -qx 'populated 0' "$HS_G/cgroup.events" || hs_fail STOP_INSTANCES
    done
    for HS_REC in "$BASE"/config/instances_v1/*.record; do
        [ -f "$HS_REC" ] || continue
        HS_ID=${HS_REC##*/}; HS_ID=${HS_ID%.record}
        ( . "$BASE/common.sh" "$HS_ID"
          groups_empty || exit 4
          if [ -f "$RUN/init.identity" ]; then read HS_PID HS_TICK < "$RUN/init.identity"; ! alive "$HS_PID" "$HS_TICK" || exit 4; fi
        ) || hs_fail STOP_INSTANCES
    done
}
hs_status() {
    hs_read
    HS_ACTIVE=0 HS_USED=0 HS_ALLOCATED=0 HS_BOOT=false
    for HS_M in 1024 2048 4096; do
        if [ -e "$HS_DIR/swap-$HS_M.img" ] || [ -e "$HS_DIR/swap-$HS_M.record" ]; then
            hs_file "$HS_M"
            HS_ALLOCATED=$((HS_ALLOCATED + HS_M))
            if hs_active "$HS_F"; then
                HS_ACTIVE=$((HS_ACTIVE + HS_M))
                HS_U=$("$BB" awk -v f="$HS_F" '$1==f {print $4}' /proc/swaps)
                HS_USED=$((HS_USED + HS_U))
            fi
        fi
    done
    if [ -e "$HS_HOOK" ]; then hs_hook_check; HS_BOOT=true; fi
    HS_TOTAL=$("$BB" awk '/^SwapTotal:/ {print $2}' /proc/meminfo)
    HS_AVAILABLE=$("$BB" awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
    HS_FREE=$("$BB" df -Pk /data | "$BB" awk 'NR==2 {print $4}')
    HS_AUTO_JSON=false; [ "$HS_AUTO" = 0 ] || HS_AUTO_JSON=true
    printf 'IRIS_SWAP_JSON={"configuredMiB":%s,"autoRestore":%s,"activeMiB":%s,"usedKiB":%s,"allocatedMiB":%s,"hostSwapTotalKiB":%s,"memAvailableKiB":%s,"freeDiskKiB":%s,"bootHookInstalled":%s}\n' "$HS_SAVED" "$HS_AUTO_JSON" "$HS_ACTIVE" "$HS_USED" "$HS_ALLOCATED" "$HS_TOTAL" "$HS_AVAILABLE" "$HS_FREE" "$HS_BOOT"
}
case "$HS_ACTION" in
status) hs_status; exit 0 ;;
configure)
    HS_NEW=${2:-}; HS_NEW_AUTO=${3:-}
    hs_size "$HS_NEW" || hs_fail INVALID_SIZE
    case "$HS_NEW_AUTO" in 0|1) ;; *) hs_fail INVALID_AUTO ;; esac
    [ "$HS_NEW" != 0 ] || HS_NEW_AUTO=0
    ;;
restore) ;;
*) hs_fail INVALID_ACTION ;;
esac
umask 077
"$BB" mkdir -p "$HS_DIR"
[ "$("$BB" realpath "$HS_DIR")" = "$HS_DIR" ] || hs_fail UNSAFE_PATH
"$BB" chmod 0700 "$HS_DIR"
[ ! -e "$HS_LOCK" ] || { [ -f "$HS_LOCK" ] && [ "$("$BB" stat -c '%u' "$HS_LOCK")" = 0 ]; } || hs_fail UNSAFE_PATH
exec 8> "$HS_LOCK"
"$BB" timeout -s TERM 5 "$BB" flock -x 8 8>&8 || hs_fail START_IN_PROGRESS
hs_read
hs_hook_check
if [ "$HS_ACTION" = restore ]; then
    if [ "$HS_AUTO" = 1 ] && [ "$HS_SAVED" != 0 ]; then
        hs_file "$HS_SAVED"
        hs_active "$HS_F" || "$BB" swapon "$HS_F" || hs_fail ENABLE_FAILED
    fi
    hs_status; exit 0
fi
hs_stopped
# Validate every owned file before any swapoff. Unknown swap is never touched.
HS_OLD_ACTIVE=
for HS_M in 1024 2048 4096; do
    if [ -e "$HS_DIR/swap-$HS_M.img" ] || [ -e "$HS_DIR/swap-$HS_M.record" ]; then
        hs_file "$HS_M"
        if hs_active "$HS_F"; then HS_OLD_ACTIVE="$HS_OLD_ACTIVE $HS_M"; fi
    fi
done
HS_TMP=
trap '[ -z "$HS_TMP" ] || "$BB" rm -f "$HS_TMP" "$HS_TMP.record"' EXIT
if [ "$HS_NEW" != 0 ] && [ ! -f "$HS_DIR/swap-$HS_NEW.img" ]; then
    HS_FREE=$("$BB" df -Pk /data | "$BB" awk 'NR==2 {print $4}')
    [ "$HS_FREE" -ge "$(((HS_NEW + 2048) * 1024))" ] || hs_fail DISK_SPACE
    HS_TMP=$("$BB" mktemp "$HS_DIR/.build.XXXXXX")
    "$BB" chmod 0600 "$HS_TMP"
    "$BB" dd if=/dev/zero of="$HS_TMP" bs=1048576 count="$HS_NEW" conv=fsync >/dev/null 2>&1 || hs_fail ALLOCATE_FAILED
    "$BB" mkswap "$HS_TMP" >/dev/null 2>&1 || hs_fail FORMAT_FAILED
    printf 'IRISBOX_SWAP_V1 %s %s\n' "$("$BB" stat -c '%s' "$HS_TMP")" "$("$BB" stat -c '%d:%i' "$HS_TMP")" > "$HS_TMP.record"
    "$BB" mv "$HS_TMP.record" "$HS_DIR/swap-$HS_NEW.record"
    "$BB" mv "$HS_TMP" "$HS_DIR/swap-$HS_NEW.img"
    HS_TMP=
    hs_file "$HS_NEW"
fi
# A stopped guest can still leave host pages swapped out: preserve 512 MiB headroom.
HS_RECLAIM=0
for HS_M in $HS_OLD_ACTIVE; do
    [ "$HS_M" != "$HS_NEW" ] || continue
    HS_U=$("$BB" awk -v f="$HS_DIR/swap-$HS_M.img" '$1==f {print $4}' /proc/swaps)
    HS_RECLAIM=$((HS_RECLAIM + HS_U))
done
HS_AVAILABLE=$("$BB" awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
[ "$HS_AVAILABLE" -ge "$((HS_RECLAIM + 524288))" ] || hs_fail RECLAIM_MEMORY
for HS_M in $HS_OLD_ACTIVE; do
    [ "$HS_M" = "$HS_NEW" ] || "$BB" swapoff "$HS_DIR/swap-$HS_M.img" || hs_fail DISABLE_FAILED
done
if [ "$HS_NEW" != 0 ]; then
    hs_file "$HS_NEW"
    if ! hs_active "$HS_F" && ! "$BB" swapon "$HS_F"; then
        for HS_M in $HS_OLD_ACTIVE; do "$BB" swapon "$HS_DIR/swap-$HS_M.img" 2>/dev/null || true; done
        hs_fail ENABLE_FAILED
    fi
fi
if [ "$HS_NEW_AUTO" = 1 ]; then
    "$BB" mkdir -p /data/adb/service.d
    HS_HOOK_TMP=$("$BB" mktemp /data/adb/service.d/.irisbox-swap.XXXXXX)
    hs_hook_text > "$HS_HOOK_TMP"
    "$BB" chmod 0700 "$HS_HOOK_TMP"
    "$BB" mv "$HS_HOOK_TMP" "$HS_HOOK"
elif [ -e "$HS_HOOK" ]; then
    hs_hook_check
    "$BB" rm -f "$HS_HOOK"
fi
HS_CONF_TMP=$("$BB" mktemp "$HS_DIR/.settings.XXXXXX")
printf 'IRISBOX_SWAP_V1 %s %s\n' "$HS_NEW" "$HS_NEW_AUTO" > "$HS_CONF_TMP"
"$BB" mv "$HS_CONF_TMP" "$HS_DIR/settings"
"$BB" sync
hs_status
