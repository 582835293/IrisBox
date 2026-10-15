#!/system/bin/sh
# Host固定54项Google系统文件的可选程序层，inner将其置于ROM lower之前。
# prepare核对来源生成材料，enable存下次开关；Android负责GMS进程和私有账号。
# 不复制Host更新/账号，载荷校验通过不代表联网、登录或设备认证通过。
# Optional shared, immutable Google system layer. Android owns all processes/data.
set -eu
BB=/data/rootvmos/host/bin/busybox
BASE=/data/rootvmos/runtime
MODULE=$BASE/google/a13_arm64
LAYER=
MANIFEST_SHA=e4dc70eaa3278db0e669391fce60949d7610f70b970d42cad390796172872258
LIST_SHA=bfdb7e584e23d2b41a1e40140f327f7bda890a32dd58b0b87e07bf5db9159f6d
COMPONENT=a13_arm64_core_20260204:$MANIFEST_SHA
ACTION=${1:-status}
ID=${2:-7}
. "$BASE/common.sh" "$ID"
MODULE=$COMPAT/google
LAYER=$MODULE/rootfs

inputs() {
    for F in "$MODULE/component_manifest.json" "$MODULE/files.sha256"; do
        [ -f "$F" ] && [ ! -L "$F" ] || return 3
    done
    [ "$($BB sha256sum "$MODULE/component_manifest.json" | $BB cut -d ' ' -f 1)" = "$MANIFEST_SHA" ] || return 3
    [ "$($BB sha256sum "$MODULE/files.sha256" | $BB cut -d ' ' -f 1)" = "$LIST_SHA" ] || return 3
    [ "$(cat "$MODULE/component.id")" = "$COMPONENT" ] || return 3
}
kernel_compatible() {
    # The existing upper trees were created with these features disabled.
    for F in index metacopy xino_auto redirect_dir nfs_export; do
        [ "$(cat /sys/module/overlay/parameters/$F)" = N ] || {
            echo "Google layer: review changed Overlay feature $F" >&2
            return 3
        }
    done
}
verify_at() {
    CHECK=$1
    inputs
    [ -d "$CHECK" ] && [ ! -L "$CHECK" ] || return 3
    [ "$($BB find "$CHECK" -type f | $BB wc -l)" = 54 ] || return 3
    [ -z "$($BB find "$CHECK" -type l)" ] || return 3
    ( cd "$CHECK"; $BB sha256sum -c "$MODULE/files.sha256" ) >/dev/null || return 3
    while read HASH REL; do
        [ "$($BB stat -c '%u:%g:%a' "$CHECK/$REL")" = 0:0:644 ] || return 3
        case "$REL" in system/product/lib/*|system/product/lib64/*) LABEL=system_lib_file ;; *) LABEL=system_file ;; esac
        ls -Z "$CHECK/$REL" | $BB grep -q "u:object_r:$LABEL:s0" || return 3
    done < "$MODULE/files.sha256"
}
stopped() {
    groups_empty || { echo 'Stop the selected instance first' >&2; return 4; }
    if [ -f "$RUN/init.identity" ]; then
        read PID TICK < "$RUN/init.identity"
        if alive "$PID" "$TICK"; then echo 'Instance still has a live identity' >&2; return 4; fi
    fi
}
case "$ACTION" in
    verify)
        verify_at "$LAYER"
        kernel_compatible
        echo 'Google shared layer verified: 54 files'
        ;;
    prepare)
        echo 'Google must be included in the imported ROM compat/google/rootfs; Host files are never copied' >&2
        exit 4
        ;;
    enable|disable)
        host_only || exit 3
        $BB mkdir -p "$RUN"
        if [ "${IRIS_GOOGLE_LOCKED:-}" != 1 ]; then
            exec "$BB" timeout -s TERM 60 "$BB" flock -x "$RUN/lifecycle.lock" \
                "$BB" env IRIS_GOOGLE_LOCKED=1 /system/bin/sh "$BASE/google/a13_arm64/guest_layer.sh" "$ACTION" "$ID"
        fi
        stopped
        FLAG=$RUN/google.core.enabled
        [ ! -L "$FLAG" ] || exit 3
        if [ "$ACTION" = enable ]; then
            verify_at "$LAYER"
            kernel_compatible
            # No private upper replacement may hide this reviewed component set.
            while read HASH REL; do
                [ ! -e "$RUN/upper/$REL" ] && [ ! -L "$RUN/upper/$REL" ] || {
                    echo "Private upper already replaces $REL; review first" >&2; exit 3
                }
            done < "$MODULE/files.sha256"
            echo "$COMPONENT" > "$FLAG.tmp.$$"
            $BB chmod 0644 "$FLAG.tmp.$$"
            $BB mv "$FLAG.tmp.$$" "$FLAG"
            echo "Google system layer enabled for Guest $ID on next boot"
        else
            if [ -f "$FLAG" ]; then $BB mv "$FLAG" "$FLAG.disabled.$($BB date +%s).$$"; fi
            echo "Google system layer disabled for Guest $ID; data/update files retained"
        fi
        ;;
    status)
        host_only || exit 3
        if [ -f "$RUN/google.core.enabled" ]; then
            [ "$(cat "$RUN/google.core.enabled")" = "$COMPONENT" ] || exit 3
            echo "Guest $ID Google layer enabled: $COMPONENT"
        else echo "Guest $ID Google layer disabled"; fi
        ;;
    *) echo 'Use prepare, verify, enable ID, disable ID, status ID' >&2; exit 2 ;;
esac
