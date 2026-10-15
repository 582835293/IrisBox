"""仅用于本项目实机UI验收；选择已读取的可见文本，避免猜坐标。"""
import argparse,subprocess,xml.etree.ElementTree as ET,re
from pathlib import Path
from device import adb,ADB,ROOT
def dump():
    result=adb('shell','uiautomator','dump','/data/local/tmp/rootvmos-ui.xml',timeout=20)
    if 'dumped to:' not in result:raise RuntimeError(result)
    raw=adb('exec-out','cat','/data/local/tmp/rootvmos-ui.xml')
    (ROOT/'validation/private/ui.xml').write_text(raw,encoding='utf-8')
    return ET.fromstring(raw)
def main():
    a=argparse.ArgumentParser();a.add_argument('action',choices=['dump','tap','back','screenshot']);a.add_argument('text',nargs='?',default='');args=a.parse_args()
    if args.action=='back':print(adb('shell','input','keyevent','4'));return
    if args.action=='screenshot':
        data=subprocess.check_output(ADB+['exec-out','screencap','-p'],timeout=30)
        p=ROOT/'validation/private'/((args.text or 'screen')+'.png');p.write_bytes(data);print(str(p));return
    root=dump()
    if args.action=='dump':
        for n in root.iter('node'):
            if n.get('text') or n.get('content-desc'):print(n.get('text') or n.get('content-desc'),n.get('bounds'),n.get('enabled'),n.get('clickable'))
    else:
        found=[n for n in root.iter('node') if args.text in (n.get('text'),n.get('content-desc')) and n.get('enabled')=='true']
        if len(found)>1:found=[n for n in found if n.get('clickable')=='true']
        if len(found)!=1:raise RuntimeError(f'Expected exactly one visible node: {args.text}; found {len(found)}')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',found[0].get('bounds')))
        print(adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)))
if __name__=='__main__':main()
