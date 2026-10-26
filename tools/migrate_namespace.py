"""Stopped, backed-up IrisBox namespace transition using two verified APKs.

The previous package/root are read from the supplied APK, never guessed. Source
names appear only in private receipts. No account DB, Google ID or UID map edits.
"""
import argparse,hashlib,io,json,os,re,shlex,subprocess,uuid,zipfile
from pathlib import Path
from device import ROOT,BASE,ADB,shell
from upgrade_engine import engine

q=shlex.quote


def package(apk):
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')))
    result=subprocess.check_output([str(sdk/'build-tools/34.0.0/aapt.exe'),'dump','badging',str(apk)],text=True,encoding='utf-8')
    found=re.search(r"^package: name='(com\.[a-z0-9_.]+)'",result,re.M)
    assert found,'Invalid APK identity'
    return found[1]


def apk_root(raw):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        text=archive.read('common.sh').decode()
    found=re.search(r'^BASE=(/data/[a-z0-9_]+/runtime)$',text,re.M)
    assert found,'Invalid APK storage profile'
    return found[1][:-len('/runtime')]


def put(local,remote):
    temp='/data/local/tmp/irisbox-migrate-'+uuid.uuid4().hex
    subprocess.run(ADB+['push',str(local),temp],check=True,stdout=subprocess.DEVNULL,timeout=90)
    shell('cp '+q(temp)+' '+q(remote)+'\nchmod 0600 '+q(remote)+'\nrm '+q(temp))


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--from-apk',type=Path,required=True)
    p.add_argument('--to-apk',type=Path,default=ROOT/'out/IrisBox-0.1.5.apk')
    p.add_argument('--snapshot-parent',help='Already verified stopped-instance snapshot parent')
    p.add_argument('--apply',action='store_true')
    p.add_argument('--receipt',type=Path,default=ROOT/'out/maintenance/namespace-transition.json')
    a=p.parse_args()
    old,old_raw=engine(a.from_apk);new,new_raw=engine(a.to_apk)
    assert old['version']=='0.1.4-e21' and new['version']=='0.1.5-e25'
    source=apk_root(old_raw);target=apk_root(new_raw)
    assert source!=target and target+'/runtime'==BASE and source not in ('/data/guest','/data')
    previous=package(a.from_apk);current=package(a.to_apk)
    assert previous!=current and current=='com.irisbox.viewer'
    b=source+'/runtime';bb=source+'/host/bin/busybox'
    records=json.loads(shell("printf '['\nFIRST=1\nfor F in "+b+"/config/instances_v1/*.record; do\nN=${F##*/}; N=${N%.record}; [ $FIRST = 1 ] || printf ','; FIRST=0; printf '{\"id\":%s,\"revision\":\"%s\"}' \"$N\" \"$(cat \"$F\")\"; done\nprintf ']'"))
    assert records and all(1007<=r['id']<=1134 and re.fullmatch('[a-f0-9]{64}',r['revision']) for r in records)
    assert json.loads(shell('cat '+b+'/engine.json'))==old,'Installed baseline changed'
    installed=shell('P=$(pm path '+previous+'); P=${P#package:}; '+bb+' sha256sum "$P"').split()[0]
    assert installed==hashlib.sha256(a.from_apk.read_bytes()).hexdigest(),'Installed APK differs from supplied baseline'
    old_list=''.join(v['sha256']+'  '+n+'\n' for n,v in sorted(old['files'].items()))
    checks=['( . '+q(b+'/common.sh')+' '+str(r['id'])+'; host_only; groups_empty; if [ -f "$RUN/init.identity" ]; then read P T < "$RUN/init.identity"; ! alive "$P" "$T"; fi; [ ! -e "$RUN/network.current" ]; [ ! -e /sys/class/net/rv'+str(r['id'])+'h ]; )' for r in records]
    shell('\n'.join(checks))
    mounts=shell('cat /proc/self/mountinfo').splitlines();lowers=[]
    for line in mounts:
        fields=line.split();view=fields[4]
        if not (view==source or view.startswith(source+'/')):continue
        match=re.fullmatch(re.escape(b)+r'/roms/([a-z0-9._-]+)/(system|vendor)\.lower',view)
        assert match,'Unexpected source mount: '+view
        sep=fields.index('-');loop=fields[sep+2]
        assert fields[sep+1]=='ext4' and 'ro' in fields[5].split(',') and re.fullmatch('/dev/block/loop[0-9]+',loop)
        image=view[:-len('.lower')]+'.img'
        assert loop+':' in shell('losetup -j '+q(image)),'Loop ownership mismatch'
        lowers.append((view,loop,image))
    print('PRECHECK_OK instances='+str(len(records))+' ownedLowerMounts='+str(len(lowers)),flush=True)
    if not a.apply:return
    assert not shell('if [ -e '+target+' ] || [ -L '+target+' ]; then echo EXISTS; fi').strip(),'Target root already exists'
    suffix='.upgrade-'+uuid.uuid4().hex;stage=source+'/'+suffix;finalstage=target+'/'+suffix
    local=ROOT/'out/maintenance'/suffix;local.mkdir(parents=True)
    a.receipt.parent.mkdir(parents=True,exist_ok=True)
    meta={'source':source,'target':target,'oldPackage':previous,'newPackage':current,'stageSuffix':suffix,'instances':records,'state':'PREPARING'}
    a.receipt.write_text(json.dumps(meta,indent=2),encoding='utf-8')
    shell('umask 077\nmkdir '+q(stage)+'\nmkdir '+q(stage+'/backup')+' '+q(stage+'/retired'))
    (local/'old.sha256').write_text(old_list,encoding='ascii',newline='\n');put(local/'old.sha256',stage+'/old.sha256')
    shell('(cd '+q(b)+' && '+q(bb)+' sha256sum -c '+q(stage+'/old.sha256')+' >/dev/null)')
    snapshots=a.snapshot_parent
    if not snapshots:
        snapshots=stage+'/snapshots';shell('mkdir '+q(snapshots))
        with zipfile.ZipFile(io.BytesIO(old_raw)) as archive:script=archive.read('security/userns/backup.sh').decode()
        script=re.sub(r'^PARENT=.*$',lambda _: 'PARENT='+snapshots,script,flags=re.M)
        script=script.replace('resolution_v1 memory_v1','resolution_v1 orientation_v1 memory_v1').replace('"$BASE/config/$KIND/$ID.mib"','"$BASE/config/$KIND/$ID.mode" "$BASE/config/$KIND/$ID.mib"')
        (local/'snapshot.sh').write_text(script,encoding='utf-8',newline='\n');put(local/'snapshot.sh',stage+'/snapshot.sh')
        for r in records:
            shell(q(bb)+' flock -x '+q(b+'/instances/'+str(r['id'])+'/lifecycle.lock')+' '+q(bb)+' env IRIS_USERNS_BACKUP_LOCKED=1 sh '+q(stage+'/snapshot.sh')+' '+str(r['id']),timeout=1800)
    assert re.fullmatch(re.escape(source)+r'/(?:maintenance/[a-z0-9_/-]+|\.upgrade-[a-f0-9]{32}/snapshots)',snapshots)
    for r in records:
        save=snapshots+'/'+str(r['id'])+'_'+r['revision']
        shell('test ! -L '+q(save)+'\ngrep -qx '+q('BACKUP_COMPLETE id='+str(r['id'])+' revision='+r['revision'])+' '+q(save+'/complete')+'\n'+q(bb)+' cmp '+q(save+'/source.sha256')+' '+q(save+'/backup.sha256')+'\n(cd '+q(save+'/trees')+' && '+q(bb)+' sha256sum -c '+q(save+'/backup.sha256')+' >/dev/null)',timeout=1200)
    print('INSTANCE_SNAPSHOTS_VERIFIED',flush=True)
    backups=sorted(set(old['files'])|{'engine.json','engine.sha256','engine.version','files.sha256'})
    for name in backups:
        shell('test ! -L '+q(b+'/'+name)+'\nmkdir -p '+q(stage+'/backup/'+str(Path(name).parent).replace('\\','/'))+'\n/system/bin/cp -a --preserve=all '+q(b+'/'+name)+' '+q(stage+'/backup/'+name))
    # Preserve owned upper-layer service material for rollback; no Google/game edits.
    prefix=source.rsplit('/',1)[1];retired=[];audio=[];latest={}
    pointer=b+'/display/buffer_viewer/current'
    value=shell('cat '+q(pointer)+' 2>/dev/null || true').strip()
    if value:
        assert re.fullmatch(re.escape(b)+r'/display/buffer_viewer/runs/[0-9_]+',value)
        assert shell('cat '+q(value+'/state')).strip()=='STOPPED','Display cleanup is still pending'
        retired.append((pointer,stage+'/retired/controller/display-current'))
    for r in records:
        run=b+'/instances/'+str(r['id'])
        for relative in ['upper/system/etc/init/zz_'+prefix+'_audio.rc','upper/system/framework/'+prefix+'-audio.jar']:
            path=run+'/'+relative
            if shell('if [ -f '+q(path)+' ] && [ ! -L '+q(path)+' ]; then echo YES; fi').strip():retired.append((path,stage+'/retired/'+str(r['id'])+'/'+relative))
        path=run+'/data/'+prefix+'_audio';dest=run+'/data/irisbox_audio'
        if shell('if [ -d '+q(path)+' ] && [ ! -L '+q(path)+' ]; then echo YES; fi').strip():
            assert not shell('if [ -e '+q(dest)+' ] || [ -L '+q(dest)+' ]; then echo EXISTS; fi').strip()
            audio.append((path,dest))
        path=run+'/latest.logdir'
        value=shell('cat '+q(path)+' 2>/dev/null || true').strip()
        if value:
            assert value.startswith(run+'/') and re.fullmatch('[0-9_]+',value[len(run)+1:])
            latest[path]=value
    # A standalone recovery script uses only fixed verified paths and refuses live tasks.
    recovery=['#!/system/bin/sh','set -eu','OLD='+q(source),'NEW='+q(target),'REL='+q(suffix),
        'IRIS_MIGRATION_HOME=$OLD; [ ! -d "$NEW" ] || IRIS_MIGRATION_HOME=$NEW','S=$IRIS_MIGRATION_HOME/$REL','B=$IRIS_MIGRATION_HOME/runtime','BB=$IRIS_MIGRATION_HOME/host/bin/busybox',
        '[ "$(id -u)" = 0 ]','[ "$("$BB" awk \'{print $1,$2,$3}\' /proc/self/uid_map)" = "0 0 4294967295" ]',
        '[ ! -L "$IRIS_MIGRATION_HOME" ] && [ "$($BB realpath "$IRIS_MIGRATION_HOME")" = "$IRIS_MIGRATION_HOME" ]']
    for r in records:
        recovery+=['if [ -f "$B/instances/'+str(r['id'])+'/init.identity" ]; then read P T < "$B/instances/'+str(r['id'])+'/init.identity"; [ "$("$BB" awk -v t="$T" \'$22==t && $3!="Z" && $3!="X" {print "live"}\' /proc/$P/stat 2>/dev/null)" != live ]; fi']
        for cgprefix in (prefix,'irisbox'):
            cg='/sys/fs/cgroup/'+cgprefix+'_'+str(r['id'])
            recovery+=['[ ! -f '+q(cg+'/cgroup.events')+' ] || "$BB" grep -qx "populated 0" '+q(cg+'/cgroup.events')]
    recovery+=['[ ! -d "$NEW" ] || { [ ! -e "$OLD" ]; mv "$NEW" "$OLD"; }','B=$OLD/runtime','S=$OLD/$REL','BB=$OLD/host/bin/busybox']
    for name in backups:recovery+=['cp -p "$S/backup/'+name+'" "$B/'+name+'.restore"','mv -f "$B/'+name+'.restore" "$B/'+name+'"']
    for name in sorted(set(new['files'])-set(old['files'])):recovery+=['[ ! -L "$B/'+name+'" ]; rm -f "$B/'+name+'"']
    for src,dst in retired:
        rel=dst[len(stage)+1:];recovery+=['[ ! -f "$S/'+rel+'" ] || { mkdir -p '+q(str(Path(src).parent).replace('\\','/'))+'; mv "$S/'+rel+'" '+q(src)+'; }']
    for src,dst in audio:recovery+=['[ ! -d '+q(dst)+' ] || { [ ! -e '+q(src)+' ]; mv '+q(dst)+' '+q(src)+'; }']
    for path,value in latest.items():recovery+=['printf "%s\\n" '+q(value)+' > '+q(path)]
    recovery+=['pm enable --user 0 '+q(previous)+' >/dev/null','sync','rm -f "$OLD/engine-upgrade.pending"','echo NAMESPACE_ROLLBACK_COMPLETE_DATA_RETAINED']
    (local/'recover.sh').write_text('\n'.join(recovery)+'\n',encoding='utf-8',newline='\n');put(local/'recover.sh',stage+'/recover.sh')
    (local/'engine.zip').write_bytes(new_raw);put(local/'engine.zip',stage+'/engine.zip')
    newlist=''.join(v['sha256']+'  '+n+'\n' for n,v in sorted(new['files'].items()))
    (local/'files.sha256').write_text(newlist,encoding='ascii',newline='\n');put(local/'files.sha256',stage+'/files.sha256')
    script=['#!/system/bin/sh','set -eu','set -o pipefail','O='+q(source),'N='+q(target),'B=$O/runtime','S=$O/'+suffix,'BB=$O/host/bin/busybox',
        '[ ! -L "$O" ] && [ "$($BB realpath "$O")" = "$O" ]','[ ! -e "$N" ] && [ ! -L "$N" ]','[ ! -e "$O/engine-upgrade.pending" ]']+checks
    script+=['mkdir "$S/payload"','"$BB" unzip -q "$S/engine.zip" -d "$S/payload"','(cd "$S/payload" && "$BB" sha256sum -c "$S/files.sha256" >/dev/null)',
        'for F in "$S/payload"/*.sh; do sh -n "$F"; done','pm disable-user --user 0 '+q(previous)+' >/dev/null',
        'printf "%s\\n" '+q(finalstage)+' > "$O/engine-upgrade.pending"',
        'trap \'RC=$?; trap - EXIT; if [ "$RC" != 0 ]; then if [ -d "$N" ]; then sh "$N/'+suffix+'/recover.sh"; else sh "$S/recover.sh"; fi; fi; exit "$RC"\' EXIT']
    for view,loop,image in lowers:script+=['[ "$(losetup -j '+q(image)+' | "$BB" cut -d: -f1)" = '+q(loop)+' ]','umount '+q(view),'losetup -d '+q(loop)]
    script+=['! "$BB" awk -v p="$O" \'$5==p || index($5,p "/")==1 {found=1} END {exit !found}\' /proc/self/mountinfo']
    for r in records:
        for controller in ('/sys/fs/cgroup','/dev/memcg','/dev/cpuctl','/dev/cpuset','/dev/blkio'):
            group=controller+'/'+prefix+'_'+str(r['id'])
            script+=['if [ -d '+q(group)+' ]; then "$BB" find '+q(group)+' -depth -type d | while IFS= read -r D; do [ ! -s "$D/cgroup.procs" ] || [ -z "$(cat "$D/cgroup.procs")" ]; rmdir "$D"; done; fi']
    for src,dst in retired:script+=['mkdir -p '+q(str(Path(dst).parent).replace('\\','/')),'mv '+q(src)+' '+q(dst)]
    for src,dst in audio:script+=['mv '+q(src)+' '+q(dst)]
    for path,value in latest.items():script+=['printf "%s\\n" '+q(value.replace(source+'/',target+'/',1))+' > '+q(path)]
    for name in new['files']:
        script+=['[ ! -L "$B/'+name+'" ]','mkdir -p "$B/'+str(Path(name).parent).replace('\\','/')+'"','cp "$S/payload/'+name+'" "$B/'+name+'.next"','chmod 0644 "$B/'+name+'.next"','mv "$B/'+name+'.next" "$B/'+name+'"']
    for name in sorted(set(old['files'])-set(new['files'])):script+=['[ ! -L "$B/'+name+'" ]; mv "$B/'+name+'" "$S/retired/engine-'+Path(name).name+'"']
    executables=('irisbox_ns','irisbox_userns','iris_inet_policy','display/buffer_bridge/iris_buffer_producer','root/magisk_30_7/busybox','root/magisk_30_7/magisk','root/magisk_30_7/magisk32','root/magisk_30_7/magiskpolicy')
    script+=['chmod 0755 '+' '.join('"$B/'+n+'"' for n in executables),'cp "$S/payload/engine.json" "$B/engine.json"','cp "$S/files.sha256" "$B/files.sha256"',
        'printf "%s\\n" '+q(new['version'])+' > "$B/engine.version"','"$BB" sha256sum "$B/files.sha256" | "$BB" cut -d " " -f1 > "$B/engine.sha256"',
        '(cd "$B" && "$BB" sha256sum -c files.sha256 >/dev/null)','sync','[ "$O" = '+q(source)+' ] && [ "$N" = '+q(target)+' ]','mv "$O" "$N"','B=$N/runtime','S=$N/'+suffix,'BB=$N/host/bin/busybox',
        '(cd "$B" && "$BB" sha256sum -c files.sha256 >/dev/null)','sync','rm "$N/engine-upgrade.pending"','trap - EXIT','echo NAMESPACE_TRANSITION_COMMITTED_STOPPED']
    # Sourced libraries use short global names. Keep transaction variables in a
    # dedicated namespace and execute library checks in subshells.
    aliases={'O':'IRIS_NS_SOURCE','N':'IRIS_NS_TARGET','B':'IRIS_NS_BASE','S':'IRIS_NS_STAGE','BB':'IRIS_NS_BB'}
    body='\n'.join(script)+'\n'
    body=re.sub(r'(?<![A-Za-z0-9_])(BB|O|N|B|S)=',lambda m:aliases[m[1]]+'=',body)
    body=re.sub(r'\$(BB|O|N|B|S)(?![A-Za-z0-9_])',lambda m:'$'+aliases[m[1]],body)
    (local/'apply.sh').write_text(body,encoding='utf-8',newline='\n');put(local/'apply.sh',stage+'/apply.sh')
    meta.update({'snapshots':snapshots,'state':'PREPARED','localScripts':str(local)})
    a.receipt.write_text(json.dumps(meta,indent=2),encoding='utf-8')
    shell('sh -n '+q(stage+'/apply.sh')+'\nsh -n '+q(stage+'/recover.sh'))
    try:print(shell('sh '+q(stage+'/apply.sh'),timeout=300),flush=True)
    except RuntimeError as error:
        meta['state']='ROLLED_BACK' if 'NAMESPACE_ROLLBACK_COMPLETE_DATA_RETAINED' in str(error) else 'ATTENTION_REQUIRED'
        a.receipt.write_text(json.dumps(meta,indent=2),encoding='utf-8')
        raise
    meta['state']='COMMITTED_STOPPED';meta['stage']=finalstage;meta['snapshots']=snapshots.replace(source+'/',target+'/',1)
    a.receipt.write_text(json.dumps(meta,indent=2),encoding='utf-8')
    print('TARGET_ENGINE_VERIFIED; install the new APK and grant its normal Host root request. Backups retained.',flush=True)


if __name__=='__main__':main()
