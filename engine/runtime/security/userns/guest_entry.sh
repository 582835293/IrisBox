#!/system/bin/sh
# Runs only AFTER UID mapping, private mount ownership, pivot_root and FD close.
# The stock adapted init still owns Android services; this is not a new init.
set -eu
ID=$1
SESSION=$2
NET=$3
export IRIS_GUEST_INIT=1 IRIS_NETD_NO_BPF=1 IRIS_GUEST_NO_BPF=1
unset IRIS_GUEST_INIT_PROBE
if [ -d /system/etc/iris-root/sbin ]; then
    # Owned by this User namespace, unlike the Host-prepared inherited mounts.
    # Denylist may detach this private mount without changing the sealed ROM.
    /system/bin/busybox mount -t tmpfs -o mode=0755,nosuid magisk /sbin
    /system/bin/busybox cp -a /system/etc/iris-root/sbin/. /sbin/
fi
/system/bin/busybox sh /system/bin/iris_network.sh guest "$ID" "$SESSION" "$NET"
printf '%s' u:r:init:s0 > /proc/self/attr/exec
exec /system/bin/iris_init second_stage
