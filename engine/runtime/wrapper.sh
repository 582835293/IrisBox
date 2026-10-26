#!/system/bin/sh
# Host supervisor stays outside delegated groups; mapped init is the only child.
set -eu
umask 022
. /data/irisbox/runtime/common.sh "$1"
host_only || exit 3
[ ! -e "$RUN/userns.pending" ] && [ ! -L "$RUN/userns.pending" ] || exit 4
"$BASE/irisbox_userns" profile "$ID" >/dev/null
exec "$BASE/irisbox_userns" launch "$ID" "$2"
