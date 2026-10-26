"""Real toolbar rotation, geometry and Host-to-Guest input on retained instance 2."""
import json,re,sys,time,urllib.error
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import API,adb
from regression import target,guest,PRIVATE,compare
from ui import dump as host_nodes
from guest_ui import observe,rectangle
from fullscreen_regression import geometry,ensure_viewer

def host_tap(label):
    found=[n for n in host_nodes().iter('node') if label in (n.get('text'),n.get('content-desc')) and n.get('enabled')=='true' and rectangle(n)]
    found=list({n.get('bounds'):n for n in found}.values());assert len(found)==1,(label,len(found))
    x1,y1,x2,y2=rectangle(found[0]);adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def main():
    before=target();assert before['ready'] and before['displaying']
    other=target(1)['orientation']
    guest(before,'am force-stop com.irisbox.probe; am start -W -n com.irisbox.probe/.ProbeActivity')
    initial=json.loads(guest(before,'cat /data/user/0/com.irisbox.probe/files/proof.json'))
    results={}
    with API() as api:
        for mode in ('landscape','portrait','auto'):
            host_tap('横竖屏')
            for _ in range(25):
                nodes=host_nodes()
                if any(n.get('text')=='实例屏幕方向' for n in nodes.iter('node')):break
                time.sleep(.2)
            else:raise RuntimeError('Orientation dialog missing')
            host_tap({'landscape':'横屏','portrait':'竖屏','auto':'跟随应用'}[mode])
            for _ in range(70):
                row=target();assert row['session']==before['session'] and row['revision']==before['revision'] and row['ready']
                if row['error']:raise RuntimeError(row['error'])
                if row['orientation']['mode']==mode and not row['busy']:break
                time.sleep(.5)
            else:raise RuntimeError('Orientation action timed out')
            if mode=='auto':
                assert guest(row,'wm user-rotation -d 0').strip()=='free'
                assert guest(row,'wm fixed-to-user-rotation -d 0').strip()=='default'
                results[mode]={'followAppRestored':True};continue
            rotation=0 if mode=='landscape' else 1
            for _ in range(40):
                pos,g=geometry(row)
                if g[18]==rotation and (g[10]>g[11])==(mode=='landscape'):break
                time.sleep(.25)
            else:raise RuntimeError('New display geometry did not settle')
            assert g[16]==0 and g[12:16]==[0,0,g[8],g[9]]
            nodes=None
            for attempt in range(3):
                try:nodes=observe(row);break
                except RuntimeError:
                    if attempt==2:raise
            field=next(n for n in nodes if n.get('content-desc')=='probe-keyboard')
            x1,y1,x2,y2=rectangle(field)
            def point(x,y):return round(pos[0]+g[2]+x*(g[4]-g[2])/g[10]),round(pos[1]+g[3]+y*(g[5]-g[3])/g[11])
            x,y=point((x1+x2)/2,(y1+y2)/2);ensure_viewer();adb('shell','input','tap',str(x),str(y))
            text='Iris9L' if mode=='landscape' else 'Iris8P'
            ensure_viewer();adb('shell','input','text',text)
            for _ in range(15):
                proof=json.loads(guest(row,'cat /data/user/0/com.irisbox.probe/files/proof.json'))
                if proof['edit']==text:break
                time.sleep(.5)
            else:raise RuntimeError('Rotated keyboard input failed')
            assert proof['counter']==initial['counter'] and proof['gpuCheck'].startswith('PASS')
            adb('shell','input','keyboard','keyevent','4')
            results[mode]={'logical':[g[10],g[11]],'rotation':rotation,'hostKeyboard':text,'gpu':proof['gpuCheck'],'counterRetained':True}
            print(json.dumps({mode:results[mode]}),flush=True)
        row=target();mode=row['orientation']['mode']
        for suffix,code in [('&profile=sideways&previousProfile=auto',400),('&profile=landscape&previousProfile=auto',409)]:
            session=row['session'] if code==400 else '1:1'
            try:api.call('/action?id=1010&type=orientation&session='+session+suffix,True)
            except urllib.error.HTTPError as e:assert e.code==code,(e.code,code)
            else:raise AssertionError('Invalid/stale orientation action accepted')
        assert target()['orientation']['mode']==mode and target(1)['orientation']==other
    compare()
    results['guards']={'invalidMode400':True,'staleSession409':True,'otherInstanceUnchanged':True,'googleIdentityRetained':True}
    (PRIVATE/'orientation-regression.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    print('PASS live toolbar modes, real rotated input, GPU and session guards')

if __name__=='__main__':main()
