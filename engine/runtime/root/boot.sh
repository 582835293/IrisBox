#!/system/bin/sh
# 仅在Guest执行，由root/guest.rc按core/service/manager三个阶段调用。
# core完成daemon/post-fs-data后init才继续zygote；service跑late_start；manager安装并发boot-complete。
# /data/adb是Guest私有数据，关闭Root不删除模块/授权，重置实例才清这些数据。
# Stock init runs Magisk's three normal stages inside one Guest. Modules and
# Zygisk use its private mounts, properties, /data and inherited cgroups.
set -eu
BB=/system/bin/busybox
M=/sbin/magisk
[ "$(cat /system/etc/iris-root/component.id)" = iris-magisk-30.7-extensions-v2 ] || exit 3
[ "$(getenforce)" = Permissive ] || exit 3
[ "$(cat /proc/1/comm)" = iris_init ] || exit 3
for P in /data/adb /data/adb/magisk /data/adb/iris.status; do [ ! -L "$P" ] || exit 3;done
umask 077
mkdir -p /data/adb/magisk
chmod 0700 /data/adb
if [ "${1:-core}" = service ]; then
    [ -S /sbin/.magisk/device/socket ] || exit 4
    exec "$BB" timeout 10 "$M" --service
fi
if [ "${1:-core}" = manager ]; then
    DONE=0
    trap 'if [ "$DONE" != 1 ]; then printf "FAILED\n" > /data/adb/iris.status;fi' EXIT
    [ -S /sbin/.magisk/device/socket ] && [ -x /sbin/.magisk/busybox/busybox ] || exit 4
    APK=/system/etc/iris-root/Magisk.apk
    INSTALLED=$(pm path com.topjohnwu.magisk | "$BB" sed -n 's/^package://p')
    # Magisk validates its manager only in /data/app. A system-app copy is not
    # sufficient, and a stub would lose the full offline manager interface.
    case "$INSTALLED" in /data/app/*/base.apk) ;; *) INSTALLED= ;; esac
    if [ -z "$INSTALLED" ] || [ "$("$BB" sha256sum "$INSTALLED" | "$BB" cut -d ' ' -f 1)" != "$("$BB" sha256sum "$APK" | "$BB" cut -d ' ' -f 1)" ]; then
        # First install can compile dex while another Guest is running. Keep
        # it bounded, but allow that work rather than reporting a false failure
        # after 30 seconds while PackageManager finishes in the background.
        RESULT=$("$BB" timeout 120 pm install -r --user 0 "$APK")
        [ "$RESULT" = Success ] || { echo "$RESULT" >&2;exit 4; }
    fi
    INSTALLED=$(pm path com.topjohnwu.magisk | "$BB" sed -n 's/^package://p')
    case "$INSTALLED" in /data/app/*/base.apk) ;; *) exit 4 ;; esac
    [ "$("$BB" sha256sum "$INSTALLED" | "$BB" cut -d ' ' -f 1)" = "$("$BB" sha256sum "$APK" | "$BB" cut -d ' ' -f 1)" ] || exit 4
    "$BB" timeout 5 pm enable --user 0 com.topjohnwu.magisk >/dev/null
    "$BB" timeout 10 "$M" --boot-complete
    printf 'READY\n' > /data/adb/iris.status
    DONE=1
    echo 'Iris Magisk manager installed; authorization/modules/Zygisk ready'
    exit 0
fi
[ "${1:-core}" = core ] || exit 2
printf 'STARTING\n' > /data/adb/iris.status
trap 'if [ "${DONE:-0}" != 1 ]; then printf "FAILED\n" > /data/adb/iris.status;fi' EXIT
cp /system/etc/iris-root/busybox /data/adb/magisk/busybox
cp /system/etc/iris-root/util_functions.sh /data/adb/magisk/util_functions.sh
chmod 0755 /data/adb/magisk/busybox
chmod 0644 /data/adb/magisk/util_functions.sh
"$BB" timeout 10 "$M" --daemon
# New instances default to Zygisk off. Keep the user's settings on later boots.
"$BB" timeout 6 "$M" --sqlite "INSERT OR IGNORE INTO settings (key,value) VALUES ('zygisk',0);"
if [ "$(cat /system/etc/iris-root/profile)" = magisk-safe ]; then
    # Native safe mode disables all modules and Zygisk before starting apps.
    # Keep module files and application data so the manager can repair them.
    "$BB" timeout 6 "$M" --sqlite "REPLACE INTO settings (key,value) VALUES ('bootloop',2);"
fi
# Native post-fs-data scripts have a 35-second bound; allow the whole stage to
# complete before stock init starts zygote. Client timeout must exceed that.
"$BB" timeout 50 "$M" --post-fs-data
[ "$("$BB" timeout 4 "$M" -V)" = 30700 ]
[ -S /sbin/.magisk/device/socket ] && [ -x /sbin/.magisk/busybox/busybox ]
DONE=1
echo 'Iris Magisk post-fs-data completed; stock init may start zygote'
