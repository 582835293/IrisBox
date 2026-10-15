"""普通UID媒体及root回归；仅更新验收APK，保留用户数据。"""
import argparse,json,subprocess,sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from regression import target,guest,PRIVATE
from device import ADB,BASE,shell,ROOT

def main():
 p=argparse.ArgumentParser();p.add_argument('--numbers',type=int,nargs='+',default=[1,2]);a=p.parse_args()
 subprocess.run(ADB+['push',str(ROOT/'out/RootVMOS-Probe.apk'),'/data/local/tmp/rootvmos-probe.apk'],check=True,stdout=subprocess.DEVNULL)
 results={}
 for number in a.numbers:
  r=target(number);assert r['ready']
  shell('cp /data/local/tmp/rootvmos-probe.apk '+BASE+'/instances/'+str(r['id'])+'/data/local/tmp/rootvmos-probe.apk; chmod 0644 '+BASE+'/instances/'+str(r['id'])+'/data/local/tmp/rootvmos-probe.apk')
  assert 'Success' in guest(r,'pm install -r /data/local/tmp/rootvmos-probe.apk',90)
  guest(r,'am force-stop com.rootvmos.probe; am start -W -n com.rootvmos.probe/.ProbeActivity --es instance '+str(r['id'])+' --ez audio true --ez root '+('true' if number==2 else 'false'),40)
  proof=None
  for attempt in range(25):
   time.sleep(1);proof=json.loads(guest(r,'cat /data/user/0/com.rootvmos.probe/files/proof.json'))
   if proof['network']=='HTTP 200' and proof['googleNetwork']=='HTTP 204' and proof['gpuCheck'].startswith('PASS') and proof['audio'].startswith('write=') and (number!=2 or 'exit=0 uid=0' in proof['root']):break
  else:raise AssertionError(proof)
  assert proof['uid']!=0 and proof['frames']>0
  audio=target(number)['audio'];assert audio['ready'] and audio['nonzero']>0 and audio['peak']>0
  guest(r,'rm -f /data/user/0/com.rootvmos.probe/files/video-proof.json')
  guest(r,"am start -W -n 'com.rootvmos.probe/com.rootvmos.probe.ProbeActivity$VideoActivity'",40)
  for attempt in range(25):
   time.sleep(1)
   try:video=json.loads(guest(r,'cat /data/user/0/com.rootvmos.probe/files/video-proof.json'))
   except (RuntimeError,ValueError):continue
   if video['rendered'] and video['positionMs']>0 and video['durationMs']>0:break
  else:raise AssertionError(video)
  assert (video['width'],video['height'])==(640,360)
  assert video['error']=='' and video['uid']==proof['uid']
  time.sleep(2);later=json.loads(guest(r,'cat /data/user/0/com.rootvmos.probe/files/video-proof.json'));assert later['rendered'] and later['error']=='' and later['positionMs']!=video['positionMs']
  video['laterPositionMs']=later['positionMs']
  results[str(r['id'])]={'ordinaryUidProof':proof,'hostAudio':audio,'video':video}
  print(json.dumps({str(r['id']):results[str(r['id'])]},ensure_ascii=False),flush=True)
 (PRIVATE/'media-result.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':main()
