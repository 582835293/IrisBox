#!/system/bin/sh
# Host只读挂载固定system/vendor镜像，生成所有实例共用的lower。
# 不复制账号，不修改镜像，不借宿主运行中的system/vendor；私有写层由inner准备。
# Mount this project's independent images once, read-only, for both Guests.
# The active Host /system and /vendor are never used as ROM material.
set -eu
. /data/irisbox/runtime/common.sh "${1:-7}"
host_only || exit 3
for NAME in system vendor; do
    IMAGE=$ROM/$NAME.img
    VIEW=$ROM/$NAME.lower
    $BB mkdir -p "$VIEW"
    if $BB mountpoint -q "$VIEW"; then continue; fi
    # Reuse only a device already associated with exactly this image.
    LOOP=$(/system/bin/losetup -j "$IMAGE" | $BB head -1 | $BB cut -d: -f1)
    if [ -z "$LOOP" ]; then LOOP=$(/system/bin/losetup -f -r -s "$IMAGE"); fi
    case "$LOOP" in /dev/block/loop[0-9]*) ;; *) exit 3 ;; esac
    $BB mount -t ext4 -o ro "$LOOP" "$VIEW"
    echo "$LOOP $IMAGE" > "$ROM/$NAME.loop.identity"
done
ls -lZ "$ROM/system.lower/system/bin/init" "$ROM/system.lower/system/bin/apexd"
