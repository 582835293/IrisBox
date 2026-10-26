"""Prepare a reviewed source snapshot without local history, accounts or machine defaults.

This does not create a GitHub repository, push, or include release binaries.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,default=ROOT/'out/publish/IrisBox');args=parser.parse_args()
    dest=args.out.resolve()
    assert dest.is_relative_to((ROOT/'out/publish').resolve())
    dest.mkdir(parents=True,exist_ok=True)
    raw=subprocess.check_output(['git','-c','safe.directory='+ROOT.as_posix(),'ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT)
    names=sorted(set(raw.decode().strip('\0').split('\0')))
    # Retire renamed tracked sources, preserving ignored builds and signing keys.
    if (dest/'.git').exists():
        previous=subprocess.check_output(['git','-c','safe.directory='+dest.as_posix(),'-C',str(dest),'ls-files','-z']).decode().strip('\0').split('\0')
        wanted={n for n in names if (ROOT/n).is_file()}|{'PUBLICATION.json'}
        for name in previous:
            if not name or name in wanted:continue
            target=dest/name
            assert target.resolve().is_relative_to(dest) and not target.is_symlink()
            if target.is_file():target.unlink()
    copied=[]
    for name in names:
        source=ROOT/name
        if not source.is_file() or source.is_symlink():continue
        assert not any(x in name.split('/') for x in ('.git','validation','out','.codex','.agents'))
        assert source.suffix not in ('.keystore','.img','.irisrom','.log','.pem','.key')
        assert source.stat().st_size<100*1024*1024
        data=source.read_bytes()
        if source.suffix in ('.py','.ps1','.md','.json','.sh') and not name.startswith(('rom/compat/','engine/runtime/root/magisk_30_7/')):
            text=data.decode('utf8')
            # Keep the exact Android fingerprint; only local machine defaults are removed.
            text=text.replace('AOSP_BUILD_HOST','AOSP_BUILD_HOST').replace('DEVICE_SERIAL','DEVICE_SERIAL')
            text=text.replace('/path/to/aosp-rpi4','/path/to/aosp-rpi4')
            text=text.replace('builder@AOSP_BUILD_HOST','builder@AOSP_BUILD_HOST').replace('id_ed25519','id_ed25519')
            text=text.replace('PATH_TO_PREVIOUS_IRIS_PROJECT','PATH_TO_PREVIOUS_IRIS_PROJECT')
            text=text.replace('C:\\\\Users\\\\lijiaquan\\\\Desktop\\\\agyProgram\\\\嵌套安卓虚拟机项目V3.0','PATH_TO_PREVIOUS_IRIS_PROJECT')
            text=text.replace("'C:\\Users\\lijiaquan\\AppData\\Local\\Android\\Sdk'",'"$env:LOCALAPPDATA\\Android\\Sdk"') if source.suffix=='.ps1' else text
            if name=='tools/device.py':
                text=text.replace("ADB=['adb','-s','DEVICE_SERIAL']","import os\nADB=['adb']+(['-s',os.environ['IRISBOX_SERIAL']] if os.environ.get('IRISBOX_SERIAL') else [])")
            if name=='tools/build_producer.py':
                text=text.replace("'--host',default='builder@AOSP_BUILD_HOST'","'--host',required=True").replace("'--aosp',default='/path/to/aosp-rpi4'","'--aosp',required=True")
            if name=='reference/source-inputs.json':
                obj=json.loads(text);obj['reference']='Previous Project Iris V3.0, read-only provenance';text=json.dumps(obj,ensure_ascii=False,indent=2)+'\n'
            data=text.encode('utf8')
        assert not re.search(rb'(?m)^-----BEGIN (?:OPENSSH |RSA |EC )?PRIVATE KEY-----',data),name
        target=dest/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);copied.append(name)
    (dest/'PUBLICATION.json').write_text(json.dumps({'project':'IrisBox','chineseName':'虹膜空间',
        'snapshot':'Reviewed source snapshot; local development Git history excluded',
        'machineDefaults':'Device serial and private build-machine defaults replaced; use your own parameters',
        'releaseData':'No instance data, accounts, private signing keys or raw evidence', 'files':len(copied)},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print('PUBLIC_SNAPSHOT_READY',len(copied),'files',dest)


if __name__=='__main__':main()
