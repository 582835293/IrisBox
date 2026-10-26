"""Operate observed Guest controls; APK downloads/installations remain in Android UI."""
import argparse,json,re,time,subprocess,xml.etree.ElementTree as ET
from regression import target,guest,PRIVATE,redact
from device import ADB

def capture(row,name='current'):
    """Native screenshot, avoiding an extra UiAutomator ART process under load."""
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}',name)
    remote='/data/local/tmp/irisbox-acceptance-screen.png'
    guest(row,'screencap -p '+remote)
    assert target(row['number'])['session']==row['session'],'Guest changed during capture'
    pid=row['session'].split(':')[0]
    data=subprocess.check_output(ADB+['exec-out','/system/xbin/su','0','/system/bin/cat','/proc/'+pid+'/root'+remote],timeout=30)
    assert data.startswith(b'\x89PNG\r\n\x1a\n') and len(data)<=32*1024*1024,'Invalid native screenshot'
    path=PRIVATE/(name+'-'+str(row['id'])+'.png');path.write_bytes(data)
    return path

def observe(row):
    output=guest(row,'uiautomator dump /data/local/tmp/irisbox-acceptance-ui.xml',25)
    if 'dumped to:' not in output:raise RuntimeError('Guest UI did not reach an idle snapshot')
    raw=guest(row,'cat /data/local/tmp/irisbox-acceptance-ui.xml')
    (PRIVATE/f'acceptance-ui-{row["id"]}.xml').write_text(raw,encoding='utf-8')
    return list(ET.fromstring(raw).iter('node'))

def rectangle(n):
    bounds=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
    return bounds if len(bounds)==4 and bounds[2]>bounds[0] and bounds[3]>bounds[1] else None

def labels(nodes):
    result=[]
    for n in nodes:
        label=n.get('text') or n.get('content-desc')
        if label and rectangle(n) and not label.startswith(('svg+xml;base64,','data:image/')):result.append({'label':redact(label)[:250],'id':n.get('resource-id'),'bounds':n.get('bounds'),'enabled':n.get('enabled'),'clickable':n.get('clickable')})
    return result if len(result)<=45 else result[:8]+result[-37:]

def tap(row,label,seconds=35):
    deadline=time.monotonic()+seconds
    while True:
        nodes=observe(row)
        found=[n for n in nodes if label in (n.get('text'),n.get('content-desc')) and n.get('enabled')=='true' and rectangle(n)]
        # Compose/WebView can expose the same visible control twice at identical bounds.
        found=list({n.get('bounds'):n for n in found}.values())
        if len(found)>1:found=[n for n in found if n.get('clickable')=='true']
        if len(found)==1:
            x1,y1,x2,y2=rectangle(found[0]);guest(row,f'input tap {(x1+x2)//2} {(y1+y2)//2}')
            print('TAPPED_OBSERVED_CONTROL',redact(label),flush=True)
            try:return observe(row)
            except RuntimeError as error:
                # Download progress and games can keep animating after a valid
                # click. Do not report a failed click or send it a second time.
                # Callers still verify the actual package/page result separately.
                if 'idle snapshot' not in str(error):raise
                print('POST_TAP_UI_BUSY; action sent once, verify result separately',flush=True)
                return []
        if time.monotonic()>=deadline:raise RuntimeError('Visible control unavailable: '+label+'; snapshot='+json.dumps(labels(nodes),ensure_ascii=False))
        time.sleep(1)

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['observe','tap','capture']);p.add_argument('label',nargs='?');p.add_argument('--number',type=int,default=2);a=p.parse_args()
    row=target(a.number);assert row['ready']
    if a.action=='capture':print(capture(row,a.label or 'current'));return
    nodes=tap(row,a.label) if a.action=='tap' else observe(row)
    for n in labels(nodes):
        print(json.dumps(n['label'],ensure_ascii=False),n['bounds'],'enabled='+n['enabled'],'clickable='+n['clickable'],n['id'])

if __name__=='__main__':main()
