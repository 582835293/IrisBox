"""设备开发核验工具。旧项目只读；所有写操作只能指向新项目。"""
import xml.etree.ElementTree as ET
import argparse, subprocess, json, hashlib, urllib.request, urllib.parse, time,re,secrets
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
import os
ADB=['adb']+(['-s',os.environ['IRISBOX_SERIAL']] if os.environ.get('IRISBOX_SERIAL') else [])
BASE='/data/irisbox/runtime'
def adb(*args,timeout=30):return subprocess.check_output(ADB+list(args),timeout=timeout).decode('utf-8','replace')
def shell(script,timeout=30):
    # The device's debug su wrapper can return adb status0 after a failed child.
    # Read an explicit shell EXIT marker; never infer success from transport alone.
    marker='__IRISBOX_EXIT_'+secrets.token_hex(12)+'__'
    trap="trap 'IRISBOX_SHELL_RC=$?; printf \"\\n"+marker+"=%s\\n\" \"$IRISBOX_SHELL_RC\"' EXIT\n"
    p=subprocess.run(ADB+['exec-out','/system/xbin/su','0','/system/bin/sh','-c','set -eu\n'+trap+script+'\n'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    output=p.stdout.decode('utf-8','replace')
    found=re.search(r'\n'+marker+r'=(\d+)\r?\n?$',output)
    if not found:raise RuntimeError('Device shell did not report completion: '+output)
    result=output[:found.start()]
    if p.returncode or int(found[1]):raise RuntimeError('Device command failed ('+found[1]+'): '+result)
    return result
def old_snapshot():
    return shell('''BB=/system/bin/busybox
B=/data/guest/iris_runtime_v1
echo HASHES
$BB sha256sum "$B"/*.sh "$B"/config/instances_v1/*.record "$B"/config/memory_v1/*.mib "$B"/config/resolution_v1/*.profile "$B"/display/buffer_viewer/*.jar
echo IDENTITIES
for I in 7 8 23; do
  echo "INSTANCE=$I"; cat "$B/instances/$I/init.identity"
  read P T < "$B/instances/$I/init.identity"
  if [ -r /proc/$P/stat ]; then $BB awk -v tick="$T" '$22==tick && $3!="Z" && $3!="X" {print $1,$3,$22}' /proc/$P/stat; fi
done
echo HOST
cat /proc/sys/kernel/random/boot_id
getprop sys.boot_completed
echo POLICY
"$B/iris_inet_policy" inspect
''')
class API:
    def __enter__(self):
        self.token=shell('cat '+BASE+'/display/buffer_viewer/broker.token').strip()
        self.port=adb('forward','tcp:0','tcp:19762').strip();return self
    def __exit__(self,*args):adb('forward','--remove','tcp:'+self.port)
    def call(self,path,post=False):
        req=urllib.request.Request('http://127.0.0.1:'+self.port+path,data=b'' if post else None,headers={'Authorization':'Bearer '+self.token})
        with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)
def main():
    a=argparse.ArgumentParser();a.add_argument('action',choices=['baseline','compare','state','install','preflight','progress']);a.add_argument('--old-stopped',action='store_true');a.add_argument('--host-rebooted',action='store_true');args=a.parse_args()
    private=ROOT/'validation/private';private.mkdir(parents=True,exist_ok=True)
    if args.action=='baseline':
        (private/'old-before.txt').write_text(old_snapshot(),encoding='utf-8');print('Old project snapshot saved (read only)')
    elif args.action=='compare':
        before=(private/'old-before.txt').read_text(encoding='utf-8');after=old_snapshot();(private/'old-after.txt').write_text(after,encoding='utf-8')
        if args.old_stopped:
            assert not re.search(r'^\d+ [A-Z] \d+$',after,re.M),'An old Guest is still running'
            before=re.sub(r'^\d+ [A-Z] \d+\n','',before,flags=re.M)
            after=re.sub(r'^\d+ [A-Z] \d+\n','',after,flags=re.M)
        if args.host_rebooted:
            assert args.old_stopped,'Reboot comparison requires explicit old-stopped expectation'
            assert before.split('HOST\n')[1].splitlines()[0]!=after.split('HOST\n')[1].splitlines()[0],'Host did not reboot'
            before=before.split('HOST\n')[0];after=after.split('HOST\n')[0]
        import difflib
        differences=''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True)))
        print(differences or ('PASS: old scripts/config/registry unchanged; old Guests stopped; Host reboot verified' if args.host_rebooted else 'PASS: old Guests stopped as authorized; scripts/config/registry, Host boot and socket policy unchanged' if args.old_stopped else 'PASS: old scripts, config, registry, live init identity, Host boot and socket policy unchanged'))
        if differences:raise SystemExit(1)
    elif args.action=='state':
        with API() as api:
            state=api.call('/state?offset=0&limit=20');(private/'last-state.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
            rows=[]
            for row in state.get('instances',[]):
                compact={k:row.get(k) for k in ['id','number','running','session','ready','busy','stage','error','bootElapsedMs','previewSequence','displaying','audio']}
                compact['memoryMiB']=row.get('memory',{}).get('mib');compact['googleEnabled']=row.get('googleEnabled');compact['root']=row.get('root');rows.append(compact)
            print(json.dumps({'count':state.get('count'),'catalogError':state.get('catalogError'),'instances':rows},ensure_ascii=False))
    elif args.action=='install':print(adb('install','-r',str(ROOT/('out/IrisBox-'+ET.parse(ROOT/'app/viewer/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')+'.apk')),timeout=120))
    elif args.action=='preflight':print(shell((ROOT/'app/viewer/assets/preflight.sh').read_text(encoding='utf-8'),timeout=30))
    elif args.action=='progress':print(shell('cat /data/user/0/com.irisbox.viewer/shared_prefs/setup.xml'))
if __name__=='__main__':main()
