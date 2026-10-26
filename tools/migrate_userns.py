"""Stopped legacy IrisBox data -> protected UID range. Backup is never overwritten.

Ownership is prepared on the first mapped boot. An engine rollback alone does
not undo that metadata change: retain the independently verified data snapshot.
"""
import argparse, json, shlex
from device import API, BASE, shell

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');parser.add_argument('--ids',type=int,nargs='+');args=parser.parse_args()
    with API() as api:rows=api.call('/state?offset=0&limit=128')['instances']
    if args.ids:
        assert set(args.ids)<=set(r['id'] for r in rows);rows=[r for r in rows if r['id'] in args.ids]
    assert rows and all(1007<=r['id']<=1134 and not r['running'] and not r['busy'] for r in rows),'Stop the selected instances first'
    assert shell('cat '+BASE+'/engine.version').strip() in ('0.1.3-e17','0.1.4-e18','0.1.4-e19','0.1.4-e20','0.1.4-e21')
    for r in rows:
        id=r['id'];revision=r['revision'];assert len(revision)==64
        print('Stopped instance',id,'revision',revision,'-> independent backup + mapped identity',flush=True)
        if not args.apply:continue
        body='''set -eu
. BASE/common.sh ID
host_only
groups_empty
[ "$(cat "$BASE/config/instances_v1/$ID.record")" = REVISION ]
if "$BASE/irisbox_userns" profile "$ID" >/dev/null 2>&1; then
    [ ! -e "$RUN/userns.pending" ] && [ ! -L "$RUN/userns.pending" ]
    echo USERNS_ALREADY_ENABLED
    exit 0
fi
[ ! -e "$RUN/userns.pending" ] && [ ! -L "$RUN/userns.pending" ]
sh "$BASE/security/userns/backup.sh" "$ID"
: > "$RUN/userns.pending"
"$BASE/irisbox_userns" enable "$ID"
sync
rm "$RUN/userns.pending"
echo USERNS_MAPPING_COMMITTED_DATA_BACKUP_RETAINED
'''.replace('BASE/common.sh',BASE+'/common.sh').replace(' ID\n',' '+str(id)+'\n').replace('REVISION',shlex.quote(revision))
        command='. '+BASE+'/common.sh '+str(id)+'\nhost_only\n'
        command+='"$BB" flock -x "$RUN/lifecycle.lock" "$BB" env IRIS_USERNS_BACKUP_LOCKED=1 sh -c '+shlex.quote(body)
        print(shell(command,timeout=900),flush=True)

if __name__=='__main__':main()
