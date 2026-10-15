#!/system/bin/sh
# Host保存native/opengl选项，inner在Guest私有vendor写层apply。
# opengl隐藏Vulkan载荷/声明并限制GLES3.0，是游戏兼容退路，不是修复Vulkan驱动。
# Instance graphics capability selection. Stock EGL/GLES and Host stay intact.
set -eu
ACTION=${1:-get}
ID=${2:-7}
. /data/rootvmos/runtime/common.sh "$ID"
C=$BASE/config/graphics_v1
valid() { case "$1" in native|opengl) ;; *) echo 'Graphics mode must be native or opengl' >&2;return 2 ;; esac; }
saved() {
    PROFILE=native
    [ ! -L "$BASE/config" ] && [ ! -L "$C" ] && [ ! -L "$C/$ID.profile" ] || return 3
    if [ -e "$C/$ID.profile" ]; then
        [ -f "$C/$ID.profile" ] && [ "$("$BB" wc -c < "$C/$ID.profile")" -le 16 ] || return 3
        PROFILE=$(cat "$C/$ID.profile")
    fi
    valid "$PROFILE"
}
case "$ACTION" in
get) host_only;saved;echo "$PROFILE" ;;
set)
    host_only
    REQUESTED=${3:?Mode required};valid "$REQUESTED"
    PREVIOUS=${4:?Previous mode required};valid "$PREVIOUS"
    if [ "${IRIS_GRAPHICS_LOCKED:-}" != 1 ]; then
        "$BB" mkdir -p "$RUN"
        exec "$BB" timeout -s TERM 20 "$BB" flock -x "$RUN/lifecycle.lock" \
            "$BB" env IRIS_GRAPHICS_LOCKED=1 sh "$BASE/graphics.sh" "$@"
    fi
    groups_empty || { echo 'Stop this instance before changing graphics mode' >&2;exit 4; }
    if [ -f "$RUN/init.identity" ]; then
        read P T < "$RUN/init.identity"
        ! alive "$P" "$T" || exit 4
    fi
    saved;[ "$PROFILE" = "$PREVIOUS" ] || { echo 'Graphics setting changed; read it again' >&2;exit 4; }
    umask 077;"$BB" mkdir -p "$C"
    TEMP=$C/$ID.profile.$$
    trap '"$BB" rm -f "$TEMP"' EXIT
    printf '%s\n' "$REQUESTED" > "$TEMP";"$BB" mv "$TEMP" "$C/$ID.profile"
    echo "GRAPHICS_SAVED id=$ID profile=$REQUESTED; applies on next start"
    ;;
apply)
    # Called by inner.sh before pivot_root, in the instance's private mounts.
    [ "$(id -u)" = 0 ] && [ "$($BB readlink /proc/self/ns/mnt)" != "$($BB readlink /proc/1/ns/mnt)" ] || exit 3
    ROOT=$RUN/rootfs
    "$BB" grep -F "upperdir=$RUN/vendor.upper," /proc/self/mountinfo >/dev/null || exit 3
    saved
    # Removing files here creates only private overlay whiteouts. Native mode
    # restores those same files from the fixed ROM, including the XML features.
    for F in lib/hw/vulkan.broadcom.so lib64/hw/vulkan.broadcom.so \
        etc/permissions/android.hardware.vulkan.level.xml \
        etc/permissions/android.hardware.vulkan.version.xml \
        etc/permissions/android.software.vulkan.deqp.level.xml; do
        SOURCE=$ROM/vendor.lower/$F;TARGET=$ROOT/vendor/$F
        [ -f "$SOURCE" ] && [ ! -L "$SOURCE" ] && [ ! -L "$TARGET" ] || exit 3
        if [ "$PROFILE" = opengl ]; then "$BB" rm -f "$TARGET"
        elif [ ! -f "$TARGET" ]; then
            /system/bin/cp -a --preserve=mode,ownership,context "$SOURCE" "$TARGET"
        fi
    done
    # The tested compatibility path is GLES 3.0. Stock 3.1 auto-selection
    # produces Unity texture-unit errors and white text in this game.
    PROP=$ROOT/vendor/build.prop
    STOCK_COUNT=$($BB grep -c '^ro.opengles.version=' "$ROM/vendor.lower/build.prop")
    [ "$STOCK_COUNT" = 1 ] && [ ! -L "$PROP" ] && [ ! -L "$RUN/graphics.env" ] || exit 3
    STOCK_VERSION=$($BB sed -n 's/^ro.opengles.version=//p' "$ROM/vendor.lower/build.prop")
    [ "$STOCK_VERSION" = 196609 ] || { echo 'Unsupported ROM GLES declaration' >&2;exit 3; }
    VERSION=$STOCK_VERSION
    if [ "$PROFILE" = opengl ]; then
        VERSION=196608
        printf 'export MESA_GLES_VERSION_OVERRIDE=3.0\n' > "$RUN/graphics.env"
    else
        printf 'unset MESA_GLES_VERSION_OVERRIDE\n' > "$RUN/graphics.env"
    fi
    "$BB" chmod 0600 "$RUN/graphics.env"
    "$BB" sed -i "s/^ro.opengles.version=.*/ro.opengles.version=$VERSION/" "$PROP"
    CONTEXT=$(ls -Zd "$ROM/vendor.lower/build.prop" | "$BB" awk '{print $1}')
    /system/bin/chcon "$CONTEXT" "$PROP"
    printf '%s\n' "$PROFILE" > "$RUN/graphics.effective"
    echo "GRAPHICS_APPLIED id=$ID profile=$PROFILE"
    ;;
*) echo 'Use get ID, set ID MODE PREVIOUS, or apply ID' >&2;exit 2 ;;
esac
