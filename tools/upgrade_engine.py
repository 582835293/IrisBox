"""本地维护升级：已知APK引擎→当前引擎。全部新Guest停机后使用；保留ROM/config/实例和备份。"""
import argparse, hashlib, io, json, re, shlex, subprocess, uuid, zipfile
from pathlib import Path
from device import API, ADB, BASE, ROOT, shell


def engine(apk):
    with zipfile.ZipFile(apk) as archive: raw=archive.read('assets/engine.zip')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        manifest=json.loads(archive.read('engine.json'))
        for name,entry in manifest['files'].items():
            assert re.fullmatch(r'[A-Za-z0-9_./@+-]+',name) and '..' not in name.split('/') and not name.startswith('/')
            data=archive.read(name)
            assert len(data)==entry['size'] and hashlib.sha256(data).hexdigest()==entry['sha256'],name
    return manifest,raw


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');parser.add_argument('--recover',action='store_true');parser.add_argument('--from-apk',type=Path,default=ROOT/'out/RootVMOS-0.1.0.apk');args=parser.parse_args()
    if args.recover:
        stage=shell('cat /data/rootvmos/engine-upgrade.pending').strip()
        assert re.fullmatch(r'/data/rootvmos/\.upgrade-[a-f0-9]{32}',stage)
        print(shell('test ! -L '+stage+'\nsh '+stage+'/recover.sh',timeout=180));return
    old,_=engine(args.from_apk);new,raw=engine(ROOT/'out/IrisBox-0.1.4.apk')
    assert old['version'] in ('0.1.2-e13','0.1.3-e14','0.1.3-e15','0.1.3-e16','0.1.3-e17','0.1.4-e18','0.1.4-e19','0.1.4-e20') and new['version']=='0.1.4-e21'
    current=json.loads(shell('cat '+BASE+'/engine.json'))
    assert current==old,'Installed engine is not the supplied known baseline'
    with API() as api:
        rows=api.call('/state?offset=0&limit=128')['instances']
    assert all(not r['running'] and not r['busy'] for r in rows),'Stop all NEW Guests first'
    print('Precheck:',old['version'],'; all new Guests stopped; new engine contains',len(new['files']),'verified files')
    if not args.apply:return
    stage='/data/rootvmos/.upgrade-'+uuid.uuid4().hex
    work=ROOT/'out'/Path(stage).name;work.mkdir()
    payload=work/'engine.zip';payload.write_bytes(raw)
    old_list=''.join(e['sha256']+'  '+n+'\n' for n,e in sorted(old['files'].items()))
    new_list=''.join(e['sha256']+'  '+n+'\n' for n,e in sorted(new['files'].items()))
    (work/'old.sha256').write_text(old_list,encoding='ascii',newline='\n')
    (work/'files.sha256').write_text(new_list,encoding='ascii',newline='\n')
    backup=sorted(set(old['files'])|{'engine.json','engine.sha256','engine.version','files.sha256'})
    added=sorted(set(new['files'])-set(old['files']))
    rollback=['#!/system/bin/sh','set -eu','B='+BASE,'S='+stage,'BB=/system/bin/busybox',
              '[ ! -L "$S" ] && [ "$($BB realpath "$S")" = "$S" ]',
              '[ "$(cat /data/rootvmos/engine-upgrade.pending)" = "$S" ]']
    for name in backup:
        rollback += [f'cp -p "$S/backup/{name}" "$B/{name}.restore"',f'mv -f "$B/{name}.restore" "$B/{name}"']
    for name in added:rollback += [f'[ ! -L "$B/{name}" ]',f'rm -f "$B/{name}"']
    rollback += ['sync','rm -f /data/rootvmos/engine-upgrade.pending','echo ENGINE_ROLLED_BACK_DATA_PRESERVED']
    (work/'recover.sh').write_text('\n'.join(rollback)+'\n',encoding='utf-8',newline='\n')
    script=['#!/system/bin/sh','set -eu','B='+BASE,'S='+stage,'BB=/system/bin/busybox',
            '[ ! -e /data/rootvmos/engine-upgrade.pending ]',
            '[ ! -L "$S" ] && [ "$($BB realpath "$S")" = "$S" ]',
            '. "$B/common.sh" 7','host_only',
            'for F in "$B"/config/instances_v1/*.record; do N=${F##*/}; N=${N%.record};',
            '  . "$B/common.sh" "$N"; groups_empty || exit 4;',
            '  if [ -f "$RUN/init.identity" ]; then read P T < "$RUN/init.identity"; ! alive "$P" "$T" || exit 4; fi;',
            'done',
            '(cd "$B" && "$BB" sha256sum -c "$S/old.sha256" >/dev/null)',
            'mkdir -p "$S/payload" "$S/backup"',
            '"$BB" unzip -q "$S/engine.zip" -d "$S/payload"',
            'cp "$S/files.sha256" "$S/payload/files.sha256"',
            '(cd "$S/payload" && "$BB" sha256sum -c files.sha256 >/dev/null)']
    for name in new['files']:
        if name.endswith('.sh'):script.append('sh -n "$S/payload/'+name+'"')
    for name in backup:
        parent=str(Path(name).parent).replace('\\','/')
        script += [f'[ ! -L "$B/{name}" ] && [ -f "$B/{name}" ]',f'mkdir -p "$S/backup/{parent}"',f'cp -p "$B/{name}" "$S/backup/{name}"']
    script += ['sync','printf "%s\\n" "$S" > /data/rootvmos/engine-upgrade.pending',
               'trap \'CODE=$?; trap - EXIT; if [ "$CODE" != 0 ]; then sh "$S/recover.sh"; fi; exit "$CODE"\' EXIT']
    for name in sorted(new['files']):
        parent=str(Path(name).parent).replace('\\','/')
        script += [f'mkdir -p "$B/{parent}"',f'[ ! -L "$B/{name}" ]',f'cp "$S/payload/{name}" "$B/{name}.upgrade"',f'chmod 0644 "$B/{name}.upgrade"',f'mv -f "$B/{name}.upgrade" "$B/{name}"']
    executable=('rootvmos_ns','rootvmos_userns','iris_inet_policy','display/buffer_bridge/iris_buffer_producer','root/magisk_30_7/busybox','root/magisk_30_7/magisk','root/magisk_30_7/magisk32','root/magisk_30_7/magiskpolicy')
    script += ['chmod 0755 '+' '.join('"$B/'+n+'"' for n in executable),
               'cp "$S/payload/engine.json" "$B/engine.json"','cp "$S/files.sha256" "$B/files.sha256"',
               '(cd "$B" && "$BB" sha256sum -c files.sha256 >/dev/null)',
               'printf "%s\\n" '+new['version']+' > "$B/engine.version"',
               '"$BB" sha256sum "$B/files.sha256" | "$BB" cut -d " " -f 1 > "$B/engine.sha256"',
               'sync','rm -f /data/rootvmos/engine-upgrade.pending','trap - EXIT','echo ENGINE_UPGRADED_DATA_PRESERVED']
    (work/'apply.sh').write_text('\n'.join(script)+'\n',encoding='utf-8',newline='\n')
    assert all(b'\r' not in f.read_bytes() for f in work.iterdir() if f.suffix!='.zip')
    subprocess.run(ADB+['shell','am','force-stop','com.rootvmos.viewer'],check=True)
    print(shell('sh '+BASE+'/display/buffer_viewer/manager.sh stop',timeout=30))
    shell('mkdir -m 0700 '+stage)
    for file in work.iterdir():
        remote='/data/local/tmp/rootvmos-upgrade-'+file.name
        subprocess.run(ADB+['push',str(file),remote],check=True,stdout=subprocess.DEVNULL)
        shell('cp '+shlex.quote(remote)+' '+stage+'/'+file.name+'\nchmod 0600 '+stage+'/'+file.name)
    shell('sh -n '+stage+'/apply.sh\nsh -n '+stage+'/recover.sh')
    result=shell('sh '+stage+'/apply.sh',timeout=180)
    print(result)
    assert 'ENGINE_UPGRADED_DATA_PRESERVED' in result,'Upgrade did not reach its verified commit point'
    assert shell('cat '+BASE+'/engine.version').strip()==new['version']
    print('Backup retained at',stage,'; ROM, account data and instance registry were not moved')


if __name__=='__main__':main()
