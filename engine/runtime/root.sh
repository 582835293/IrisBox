#!/system/bin/sh
# Host按实例存Root选项，inner在私有overlay准备Magisk，Guest原厂init触发启动。
# 应用su授权与Host调试root不同；默认off，magisk-safe保留数据但禁用模块/Zygisk以救援。
# 仅支持当前Permissive开发宿主，模块不能把共享内核/全局策略当实例私有对象。
# Optional Magisk application authorization. Configuration belongs to the Host;
# all runtime binaries, sockets and authorization data belong to one Guest.
set -eu
ACTION=${1:-get};ID=${2:-7}
. /data/rootvmos/runtime/common.sh "$ID"
C=$BASE/config/root_v1
COMPONENT=$BASE/root/magisk_30_7
valid() { case "$1" in off|magisk|magisk-safe) ;; *) return 2 ;; esac; }
saved() {
    PROFILE=off
    for P in "$BASE/config" "$C" "$C/$ID.profile"; do [ ! -L "$P" ] || return 3; done
    if [ -e "$C/$ID.profile" ]; then
        [ -f "$C/$ID.profile" ] && [ "$($BB wc -c < "$C/$ID.profile")" -le 16 ] || return 3
        PROFILE=$(cat "$C/$ID.profile")
    fi
    valid "$PROFILE"
}
# 清单限定组件和签名配套文件，不按App输入拼任意路径；换版本要同步核验。
verify_component() {
    [ "$(cat "$COMPONENT/component.id")" = iris-magisk-30.7-extensions-v2 ] || return 3
    [ ! -L "$COMPONENT" ] && [ ! -L "$COMPONENT/SHA256SUMS" ] || return 3
    # Fixed manifest: only component basenames, never paths from app input.
    while read HASH FILE EXTRA; do
        case "$FILE" in magisk|magisk32|magiskpolicy|busybox|stub.apk|Magisk.apk|component.id|LICENSE|adaptation.json|apk_assets.patch|util_functions.sh) ;; *) return 3 ;; esac
        [ -z "$EXTRA" ] && [ ! -L "$COMPONENT/$FILE" ] && [ -f "$COMPONENT/$FILE" ] || return 3
    done < "$COMPONENT/SHA256SUMS"
    (cd "$COMPONENT" && "$BB" sha256sum -c SHA256SUMS >/dev/null)
}
case "$ACTION" in
get) host_only;saved;echo "$PROFILE" ;;
verify) host_only;verify_component;echo MAGISK_COMPONENT_VERIFIED ;;
set)
    host_only
    REQUESTED=${3:?Profile required};PREVIOUS=${4:?Previous profile required};EXPECTED=${5:?Data revision required}
    valid "$REQUESTED";valid "$PREVIOUS"
    [ ! -L "$RUN" ] && [ -d "$RUN" ] && [ ! -L "$RUN/lifecycle.lock" ] || exit 3
    if [ "${IRIS_ROOT_LOCKED:-}" != 1 ]; then
        exec "$BB" timeout -s TERM 40 "$BB" flock -x "$RUN/lifecycle.lock" \
            "$BB" env IRIS_ROOT_LOCKED=1 sh "$BASE/root.sh" "$@"
    fi
    [ ! -e "$RUN/cleanup.pending" ] && [ ! -L "$RUN/cleanup.pending" ] || exit 4
    RECORD=$BASE/config/instances_v1/$ID.record
    [ ! -L "$RECORD" ] && [ -f "$RECORD" ] && [ "$(cat "$RECORD")" = "$EXPECTED" ] || exit 4
    groups_empty || { echo 'Stop this instance before changing application root' >&2;exit 4; }
    if [ -f "$RUN/init.identity" ]; then read P T < "$RUN/init.identity";! alive "$P" "$T" || exit 4;fi
    saved;[ "$PROFILE" = "$PREVIOUS" ] || { echo 'Root option changed; refresh first' >&2;exit 4; }
    if [ "$REQUESTED" != off ]; then
        # The adapter supports the existing Permissive developer system only.
        [ "$(getenforce)" = Permissive ] || { echo 'This adapter requires the current Permissive developer system' >&2;exit 4; }
        verify_component
    fi
    umask 077;"$BB" mkdir -p "$C"
    TEMP=$C/$ID.profile.$$
    trap '"$BB" rm -f "$TEMP"' EXIT
    printf '%s\n' "$REQUESTED" > "$TEMP";"$BB" mv "$TEMP" "$C/$ID.profile"
    echo "APPLICATION_ROOT_SAVED id=$ID profile=$REQUESTED; applies on next start"
    ;;
apply)
    # inner.sh calls this after RC copying, before pivot_root and stock init.
    [ "$(id -u)" = 0 ] || exit 3
    for N in pid mnt net ipc uts cgroup; do
        [ "$($BB readlink /proc/self/ns/$N)" != "$($BB readlink /proc/1/ns/$N)" ] || exit 3
    done
    ROOT=$RUN/rootfs
    "$BB" grep -F "upperdir=$RUN/upper," /proc/self/mountinfo >/dev/null || exit 3
    saved
    # Only these adapter-owned private overlay entries may be removed.
    for F in su magisk; do
        TARGET=$ROOT/system/bin/$F
        if [ -L "$TARGET" ]; then
            [ "$($BB readlink "$TARGET")" = "/sbin/$F" ] || exit 3
            "$BB" rm -f "$TARGET"
        else [ ! -e "$TARGET" ] || exit 3;fi
    done
    for F in "$ROOT/system/app/IrisMagisk" "$ROOT/system/etc/iris-root"; do
        [ ! -L "$F" ] || exit 3
        "$BB" rm -rf "$F"
    done
    [ ! -L "$RUN/root.effective" ] || exit 3
    printf '%s\n' "$PROFILE" > "$RUN/root.effective"
    if [ "$PROFILE" = off ]; then echo "APPLICATION_ROOT_APPLIED id=$ID profile=off";exit 0;fi
    [ "$(getenforce)" = Permissive ] || exit 4
    verify_component
    "$BB" mkdir -p "$ROOT/sbin" "$ROOT/system/app/IrisMagisk" "$ROOT/system/etc/iris-root"
    [ ! -L "$ROOT/sbin" ] || exit 3
    # The upstream denylist unmount API identifies its own tmpfs by source
    # "magisk"; preserve that convention so hiding detaches only this mount.
    "$BB" mount -t tmpfs -o mode=0755,nosuid magisk "$ROOT/sbin"
    "$BB" mkdir -p "$ROOT/sbin/.magisk/device" "$ROOT/sbin/.magisk/busybox"
    "$BB" cp "$COMPONENT/magisk" "$ROOT/sbin/magisk"
    "$BB" cp "$COMPONENT/magisk32" "$ROOT/sbin/magisk32"
    "$BB" cp "$COMPONENT/magiskpolicy" "$ROOT/sbin/magiskpolicy"
    "$BB" cp "$COMPONENT/stub.apk" "$ROOT/sbin/stub.apk"
    "$BB" cp "$COMPONENT/busybox" "$ROOT/system/etc/iris-root/busybox"
    "$BB" cp "$COMPONENT/component.id" "$ROOT/system/etc/iris-root/component.id"
    "$BB" cp "$COMPONENT/util_functions.sh" "$ROOT/system/etc/iris-root/util_functions.sh"
    printf '%s\n' "$PROFILE" > "$ROOT/system/etc/iris-root/profile"
    "$BB" cp "$COMPONENT/Magisk.apk" "$ROOT/system/etc/iris-root/Magisk.apk"
    "$BB" cp "$BASE/root/boot.sh" "$ROOT/system/etc/iris-root/boot.sh"
    "$BB" cp "$BASE/root/guest.rc" "$ROOT/system/etc/init/zz_iris_magisk.rc"
    # Stock zygote service definitions stay in place; add only Magisk's own
    # restart notification, like the upstream magiskinit RC injection does.
    ZRC=$ROOT/system/etc/init/hw/init.zygote64_32.rc
    [ -f "$ZRC" ] && [ ! -L "$ZRC" ] || exit 3
    [ "$($BB grep -c '^service zygote' "$ZRC")" = 2 ] || exit 3
    "$BB" sed -i '/^service zygote/a\    onrestart exec u:r:su:s0 0 0 -- /sbin/magisk --zygote-restart' "$ZRC"
    "$BB" ln -s magisk "$ROOT/sbin/su"
    "$BB" ln -s /sbin/su "$ROOT/system/bin/su"
    "$BB" ln -s /sbin/magisk "$ROOT/system/bin/magisk"
    "$BB" chmod 0755 "$ROOT/sbin/magisk" "$ROOT/sbin/magisk32" "$ROOT/sbin/magiskpolicy" "$ROOT/system/etc/iris-root/boot.sh" "$ROOT/system/etc/iris-root/busybox"
    "$BB" chmod 0644 "$ROOT/system/etc/iris-root/profile" "$ROOT/system/etc/iris-root/util_functions.sh"
    "$BB" chmod 0644 "$ROOT/sbin/stub.apk" "$ROOT/system/etc/iris-root/component.id" "$ROOT/system/etc/iris-root/Magisk.apk" "$ROOT/system/etc/init/zz_iris_magisk.rc"
    /system/bin/chcon -R u:object_r:system_file:s0 "$ROOT/sbin" "$ROOT/system/etc/iris-root" "$ROOT/system/app/IrisMagisk" "$ROOT/system/etc/init/zz_iris_magisk.rc"
    echo "APPLICATION_ROOT_APPLIED id=$ID profile=$PROFILE"
    ;;
*) echo 'Use get/set/verify/apply ID; application root is disabled by default' >&2;exit 2 ;;
esac
