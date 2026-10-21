#!/system/bin/sh
# Host-only, stopped-instance snapshot before ownership migration.
# Copies bytes and metadata; never hard-links a backup to the live data.
set -eu
set -o pipefail
. /data/rootvmos/runtime/common.sh "${1:?Instance ID}"
host_only || exit 3
[ -d "$RUN" ] && [ ! -L "$RUN" ] && [ ! -L "$RUN/lifecycle.lock" ] || exit 3
if [ "${IRIS_USERNS_BACKUP_LOCKED:-}" != 1 ]; then
    exec "$BB" flock -x "$RUN/lifecycle.lock" "$BB" env IRIS_USERNS_BACKUP_LOCKED=1 \
        sh "$BASE/security/userns/backup.sh" "$ID"
fi
groups_empty || { echo 'Stop the instance before backup' >&2; exit 4; }
if [ -f "$RUN/init.identity" ]; then
    read P T < "$RUN/init.identity"
    ! alive "$P" "$T" || exit 4
fi
! "$BB" awk -v p="$RUN" '$5==p || index($5,p "/")==1 {found=1} END {exit !found}' /proc/self/mountinfo || exit 3
REV=$(cat "$BASE/config/instances_v1/$ID.record")
[ "${#REV}" = 64 ] || exit 3
case "$REV" in *[!a-f0-9]*) exit 3 ;; esac
PARENT=/data/rootvmos/maintenance/userns_migration_v1
SAVE=$PARENT/${ID}_${REV}
umask 077
[ ! -L "$PARENT" ] || exit 3
mkdir -p "$PARENT"
[ "$("$BB" realpath "$PARENT")" = "$PARENT" ] || exit 3
chmod 0700 "$PARENT"
[ ! -e "$SAVE" ] && [ ! -L "$SAVE" ] || { echo 'Backup exists; verify it instead of overwriting' >&2; exit 4; }
mkdir "$SAVE" "$SAVE/trees" "$SAVE/config"
printf '%s\n' "COPYING id=$ID" > "$SAVE/progress"
manifest() {
    (
        cd "$1"
        for TREE in data upper vendor.upper metadata; do
            [ ! -L "$TREE" ] || exit 3
            if [ -d "$TREE" ]; then "$BB" find "$TREE" -type f -print0; fi
        done
    ) | LC_ALL=C "$BB" sort -z | (cd "$1"; "$BB" xargs -0 "$BB" sha256sum)
}
manifest "$RUN" > "$SAVE/source.sha256"
for TREE in data upper vendor.upper metadata; do
    [ ! -L "$RUN/$TREE" ] || exit 3
    if [ -d "$RUN/$TREE" ]; then
        /system/bin/cp -a --preserve=all "$RUN/$TREE" "$SAVE/trees/$TREE"
        echo "COPIED id=$ID tree=$TREE" > "$SAVE/progress"
    fi
done
for KIND in instances_v1 resolution_v1 memory_v1 graphics_v1 root_v1 rom_v1 userns_v1; do
    [ ! -L "$BASE/config/$KIND" ] || exit 3
    mkdir "$SAVE/config/$KIND"
    for F in "$BASE/config/$KIND/$ID.record" "$BASE/config/$KIND/$ID.profile" \
             "$BASE/config/$KIND/$ID.mib" "$BASE/config/$KIND/$ID.map" "$BASE/config/$KIND/$ID.id"; do
        [ ! -L "$F" ] || exit 3
        if [ -f "$F" ]; then /system/bin/cp -a --preserve=all "$F" "$SAVE/config/$KIND/"; fi
    done
done
if [ -f "$RUN/google.core.enabled" ] && [ ! -L "$RUN/google.core.enabled" ]; then
    /system/bin/cp -a --preserve=all "$RUN/google.core.enabled" "$SAVE/"
fi
echo "VERIFYING id=$ID" > "$SAVE/progress"
manifest "$SAVE/trees" > "$SAVE/backup.sha256"
"$BB" cmp "$SAVE/source.sha256" "$SAVE/backup.sha256"
[ "$REV" = "$(cat "$BASE/config/instances_v1/$ID.record")" ] || exit 3
sync
echo "BACKUP_COMPLETE id=$ID revision=$REV" > "$SAVE/complete"
echo "COMPLETE id=$ID" > "$SAVE/progress"
echo "BACKUP_COMPLETE id=$ID path=$SAVE"
