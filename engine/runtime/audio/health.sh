#!/system/bin/sh
set -eu
# Observe only. Mutating PackageManager before WindowManager is ready can crash
# system_server; unsupported Traceur was masked in the private mount tree.
for N in $(/system/bin/busybox seq 1 120); do
    if /system/bin/service check window 2>/dev/null | /system/bin/busybox grep -q ': found'; then
        [ ! -e /system/app/Traceur/Traceur.apk ] || exit 4
        echo READY > /data/rootvmos_audio/health.status
        exit 0
    fi
    /system/bin/busybox sleep 1
done
echo 'Guest window health check timed out' >&2
exit 4
