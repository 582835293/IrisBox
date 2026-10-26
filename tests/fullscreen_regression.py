"""Real Viewer FD handoff, re-entry and Host-to-Guest input using observed geometry."""
import json, re, sys, time, xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import API, adb, shell, BASE
from regression import target, guest, PRIVATE
from ui import dump as host_ui

def guest_nodes(row):
    assert 'dumped to:' in guest(row,'uiautomator dump /data/local/tmp/irisbox-input-ui.xml',30)
    raw=guest(row,'cat /data/local/tmp/irisbox-input-ui.xml')
    (PRIVATE/f'input-ui-{row["id"]}.xml').write_text(raw,encoding='utf-8')
    return list(ET.fromstring(raw).iter('node'))

def rectangle(nodes,description):
    choices=[n for n in nodes if n.get('content-desc')==description and n.get('enabled')=='true']
    assert len(choices)==1,(description,len(choices))
    return list(map(int,re.findall(r'\d+',choices[0].get('bounds'))))

def geometry(row):
    pattern=r'IRIS_BUFFER_VISIBLE_GEOMETRY id='+str(row['id'])+r' session='+re.escape(row['session'])+r' surfaceScreen=(\[[^\]]+\]) geometry=(\[[^\]]+\])'
    for _ in range(20):
        raw=adb('logcat','-d','-s','IrisBufferViewer:I')
        found=list(re.finditer(pattern,raw))
        if found:return json.loads(found[-1][1]),json.loads(found[-1][2])
        time.sleep(1)
    raise RuntimeError('Viewer did not report a completed frame geometry')

def wait_display(api,row,active):
    for _ in range(60):
        now=next(r for r in api.call('/state?offset=0&limit=128')['instances'] if r['id']==row['id'])
        assert now['session']==row['session'] and now['ready']
        if now['displaying']==active and not now['busy']:
            if not active:
                # A dead Viewer is earlier than completed producer/input cleanup.
                try:
                    if 'STOPPED' not in shell('sh '+BASE+'/display/buffer_viewer/display.sh status '+str(row['id'])):
                        time.sleep(1);continue
                    shell('/data/irisbox/host/bin/busybox flock -n '+BASE+'/display/buffer_viewer/operation.lock /data/irisbox/host/bin/busybox true')
                except RuntimeError:time.sleep(1);continue
            return now
        if now['error'] and not now['busy']:raise RuntimeError(now['error'])
        time.sleep(1)
    raise RuntimeError('Display transition timed out')

def return_list():
    nodes=[n for n in host_ui().iter('node') if ('return-list' in n.get('content-desc','') or n.get('content-desc')=='返回列表') and n.get('enabled')=='true']
    assert len(nodes)==1,'Expected the actual Viewer return-list toolbar button'
    x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def ensure_viewer():
    focused=adb('shell','dumpsys','window')
    line=next((s for s in focused.splitlines() if 'mCurrentFocus=' in s),'')
    assert 'com.irisbox.viewer' in line and 'DisplayActivity' in line,'Viewer lost focus; input cancelled'

def main():
    results={}
    with API() as api:
        for number in (1,2):
            row=target(number);assert row['ready']
            guest(row,'am force-stop com.irisbox.probe; am start -W -n com.irisbox.probe/.ProbeActivity',40)
            initial=json.loads(guest(row,'cat /data/user/0/com.irisbox.probe/files/proof.json'))
            # UiAutomator is a separate ART VM. Read bounds before opening the
            # stream so this instrumentation cannot starve a 768-MiB Guest.
            nodes=guest_nodes(row)
            keyboard_rect=rectangle(nodes,'probe-keyboard')
            gpu_rect=rectangle(nodes,'probe-gesture-gpu')
            api.call(f'/action?id={row["id"]}&type=open&session={row["session"]}&seconds=0',True)
            wait_display(api,row,True)
            position,g=geometry(row);assert len(g)==19 and g[0]==1 and g[10]>0 and g[11]>0
            def host(x,y):return (round(position[0]+g[2]+x*(g[4]-g[2])/g[10]),round(position[1]+g[3]+y*(g[5]-g[3])/g[11]))
            rect=keyboard_rect
            x,y=host((rect[0]+rect[2])/2,(rect[1]+rect[3])/2)
            ensure_viewer()
            adb('shell','input','tap',str(x),str(y))
            text='Iris42Z'
            # The bounded queue intentionally cancels instantaneous >32-event
            # bursts. This mixed-case sample stays within its supported bound.
            ensure_viewer();adb('shell','input','text',text)
            for _ in range(12):
                proof=json.loads(guest(row,'cat /data/user/0/com.irisbox.probe/files/proof.json'))
                if proof['edit']==text:break
                time.sleep(1)
            else:raise AssertionError('Keyboard text was not received through Viewer: '+str(proof['edit']))
            # Explicit KEYBOARD source is forwarded to Guest; unspecified Host
            # Back is a navigation action that returns to the instance list.
            ensure_viewer();adb('shell','input','keyboard','keyevent','4');time.sleep(1)
            rect=gpu_rect;y=(rect[1]+rect[3])/2
            a=host(rect[0]+(rect[2]-rect[0])*.2,y);b=host(rect[0]+(rect[2]-rect[0])*.8,y)
            ensure_viewer();adb('shell','input','swipe',str(a[0]),str(a[1]),str(b[0]),str(b[1]),'400')
            time.sleep(1);proof=json.loads(guest(row,'cat /data/user/0/com.irisbox.probe/files/proof.json'))
            assert proof['touches']>0 and proof['swipes']>0 and proof['counter']==initial['counter']
            return_list();wait_display(api,row,False)
            api.call(f'/action?id={row["id"]}&type=open&session={row["session"]}&seconds=0',True)
            wait_display(api,row,True)
            return_list();wait_display(api,row,False)
            results[str(row['id'])]={'fullscreen':True,'keyboard':text,'swipe':True,'reentry':True,'sessionUnchanged':True,'counterUnchanged':True}
            print(json.dumps({str(row['id']):results[str(row['id'])]}),flush=True)
    (PRIVATE/'fullscreen-result.json').write_text(json.dumps(results,indent=2),encoding='utf-8')

if __name__=='__main__':main()
