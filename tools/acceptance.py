"""新环境验收操作；只接受产品保留ID，通过现有鉴权管理协议执行生命周期。"""
import argparse,json,time,subprocess
from device import API,ADB,BASE,ROOT,shell
def rows(api):return api.call('/state?offset=0&limit=128')['instances']
def row(api,id):return next(r for r in rows(api) if r['id']==id)
def compact(r):return {k:r.get(k) for k in ('id','running','ready','session','busy','error','stage','bootElapsedMs')}
def wait(api,id,condition,seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        r=row(api,id)
        if condition(r):return r
        if r.get('error') and not r.get('busy'):raise RuntimeError(r['error'])
        time.sleep(1)
    raise RuntimeError('Timed out: '+str(compact(r)))
def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['create','memory','start','stop','ready','install-probe','proof','reset','delete']);p.add_argument('--id',type=int,default=1007);p.add_argument('--mib',type=int,default=512);p.add_argument('--google',action='store_true');p.add_argument('--root',choices=['off','magisk'],default='off');p.add_argument('--confirm-temporary-data',action='store_true');a=p.parse_args()
    if not 1007<=a.id<=1134:raise SystemExit('Only IrisBox reserved IDs allowed')
    if a.action=='proof':
        value=json.loads(shell(f'sh {BASE}/ctl.sh exec {a.id} cat /data/user/0/com.irisbox.probe/files/proof.json'))
        path=ROOT/f'validation/private/proof-{a.id}.json';path.write_text(json.dumps(value,indent=2),encoding='utf-8');print(json.dumps(value));return
    with API() as api:
        if a.action=='create':
            previous={r['id'] for r in rows(api)};print(api.call(f'/instances/create?google={int(a.google)}&root={a.root}',True))
            for n in range(20):
                added=[r for r in rows(api) if r['id'] not in previous]
                if added:print(json.dumps([compact(r) for r in added]));return
                time.sleep(1)
            raise RuntimeError('Creation not completed')
        r=row(api,a.id)
        if a.action in ('reset','delete'):
            if not a.confirm_temporary_data or a.id!=1009:raise SystemExit('Only explicitly confirmed QA instance1009 may be cleared by this tool')
            revision=r['revision']
            api.call(f"/action?id={a.id}&type={a.action}&session=&revision={revision}&confirm={r['number']}",True)
            for n in range(30):
                current=[x for x in rows(api) if x['id']==a.id]
                if a.action=='delete' and not current:print('QA_INSTANCE_DELETED');return
                if a.action=='reset' and current and current[0]['revision']!=revision and not current[0]['busy']:
                    assert current[0]['googleEnabled'] and current[0]['root']['profile']=='magisk'
                    print('QA_RESET_PRESERVED_GOOGLE_ROOT_AND_CHANGED_REVISION');return
                time.sleep(1)
            raise RuntimeError('Data operation did not complete')
        if a.action=='ready':
            r=wait(api,a.id,lambda x:x['ready'],300)
            (ROOT/f'validation/private/ready-{a.id}.json').write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(compact(r)));return
        if a.action=='memory':
            if not 512<=a.mib<=2560:raise SystemExit('Invalid memory budget')
            path=f"/action?id={a.id}&type=memory&mib={a.mib}&previousMib={r['memory']['mib']}&session="
            api.call(path,True);r=wait(api,a.id,lambda x:not x['busy'] and x['memory']['mib']==a.mib);print(json.dumps(compact(r)));return
        if a.action=='start':
            print(api.call(f'/action?id={a.id}&type=start&session=',True));return
        if a.action=='stop':
            print(api.call(f"/action?id={a.id}&type=stop&session={r['session']}",True));print(json.dumps(compact(wait(api,a.id,lambda x:not x['running'] and not x['busy']))));return
        if a.action=='install-probe':
            if not r['ready']:raise RuntimeError('Guest is not ready')
            subprocess.run(ADB+['push',str(ROOT/'out/IrisBox-Probe.apk'),'/data/local/tmp/irisbox-probe.apk'],check=True,timeout=30)
            size=(ROOT/'out/IrisBox-Probe.apk').stat().st_size
            script=f'''export IRIS_EXPECT_SESSION={r['session']}
sh {BASE}/ctl.sh exec {a.id} pm install -r -S {size} < /data/local/tmp/irisbox-probe.apk
sh {BASE}/ctl.sh exec {a.id} am start -W -n com.irisbox.probe/.ProbeActivity --es instance {a.id}
'''
            print(shell(script,timeout=90))
if __name__=='__main__':main()
