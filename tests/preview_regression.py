"""单帧预览连续采样，核验JPEG及辅助进程退出；只写验收临时文件。"""
import argparse,json,sys,subprocess,time,io
from pathlib import Path
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from regression import target,guest,PRIVATE
from device import ADB,BASE

def main():
 p=argparse.ArgumentParser();p.add_argument('--numbers',type=int,nargs='+',default=[1,2]);p.add_argument('--count',type=int,default=50);a=p.parse_args();assert 1<=a.count<=100
 results={}
 for number in a.numbers:
  r=target(number);assert r['ready'];session=r['session'];started=time.monotonic()
  command='set -eu\nfor N in $(busybox seq 1 '+str(a.count)+'); do busybox timeout 8 /system/bin/iris_preview_capture '+str(r['id'])+' '+session+' /data/iris_buffer/'+session.replace(':','_')+'/output.sock --mode preview --width 640 --height 360 > /data/local/tmp/rootvmos-preview-check.jpg; done; echo PREVIEW_BATCH_PASS'
  assert 'PREVIEW_BATCH_PASS' in guest(r,command,180)
  remote=BASE+'/instances/'+str(r['id'])+'/data/local/tmp/rootvmos-preview-check.jpg'
  raw=subprocess.check_output(ADB+['exec-out','/system/xbin/su','0','/system/bin/cat',remote],timeout=15)
  img=Image.open(io.BytesIO(raw));img.load();assert img.format=='JPEG' and img.size==(640,360)
  crash=guest(r,'logcat -b crash -d');(PRIVATE/('crash-'+str(r['id'])+'.txt')).write_text(crash,encoding='utf-8')
  assert 'FATAL EXCEPTION' not in crash and 'Fatal signal' not in crash
  assert 'FORTIFY: pthread_mutex_lock' not in crash and 'iris_preview_capture' not in crash,crash[-3000:]
  assert target(number)['session']==session
  (PRIVATE/('preview-'+str(r['id'])+'.jpg')).write_bytes(raw)
  results[str(r['id'])]={'captures':a.count,'seconds':round(time.monotonic()-started,2),'jpeg':[640,360],'sessionUnchanged':True,'previewCrash':False}
  print(json.dumps({str(r['id']):results[str(r['id'])]}),flush=True)
 (PRIVATE/'preview-result.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
if __name__=='__main__':main()
