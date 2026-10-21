"""Ordinary apps without INTERNET remain denied in both mapped domains."""
import json, subprocess, sys, time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import ADB, BASE, ROOT, shell
from regression import states, guest, PRIVATE

apk=ROOT/'out/RootVMOS-NoInternet.apk'
subprocess.run(ADB+['push',str(apk),'/data/local/tmp/rootvmos-noinet.apk'],check=True,stdout=subprocess.DEVNULL)
result={}
for row in states():
    assert row['ready']
    assert 'Success' in shell('export IRIS_EXPECT_SESSION='+row['session']+'\nsh '+BASE+'/ctl.sh exec '+str(row['id'])+' pm install -r -S '+str(apk.stat().st_size)+' < /data/local/tmp/rootvmos-noinet.apk',90)
    guest(row,'am force-stop com.rootvmos.noinet; am start -W -n com.rootvmos.noinet/com.rootvmos.probe.ProbeActivity',40)
    for _ in range(20):
        proof=json.loads(guest(row,'cat /data/user/0/com.rootvmos.noinet/files/proof.json'))
        if all('SecurityException' in proof[k] for k in ('network','googleNetwork','dns')):break
        time.sleep(1)
    else:raise AssertionError(proof)
    assert proof['uid']!=0
    result[str(row['id'])]={'ordinaryUid':proof['uid'],'httpsDenied':True,'googleEndpointDenied':True,'dnsDenied':True}
(PRIVATE/'network-denial.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
