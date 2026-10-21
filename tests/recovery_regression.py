"""退出App/恢复后台不停止Guest；最终保护与安装一致性核验。"""
import hashlib,json,sys,time,xml.etree.ElementTree as ET
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import API,adb,shell,BASE,ROOT
from regression import states,guest,PRIVATE,compare

def reopen():
 adb('shell','am','start','-W','-n','com.rootvmos.viewer/.ListActivity')
 stable=0;previous=''
 for n in range(30):
  try:
   rows=states()
   identity=shell('cat '+BASE+'/display/buffer_viewer/broker.identity; /data/rootvmos/host/bin/busybox flock -n '+BASE+'/display/buffer_viewer/manager.lock /data/rootvmos/host/bin/busybox true')
   stable=stable+1 if identity==previous and len(rows)==2 and all(r['ready'] and r['audio']['ready'] for r in rows) else 0
   previous=identity
   if stable>=5:return rows
  except (OSError,ValueError,RuntimeError):stable=0
  time.sleep(1)
 raise RuntimeError('App did not restore broker')

def main():
 before=states();assert len(before)==2 and all(r['ready'] and r['audio']['ready'] for r in before)
 identity={r['id']:(r['session'],r['revision']) for r in before}
 result={}
 for mode in ('app_reopen','broker_restore'):
  adb('shell','am','force-stop','com.rootvmos.viewer')
  if mode=='broker_restore':assert 'Guests preserved' in shell('sh '+BASE+'/display/buffer_viewer/manager.sh stop',40)
  rows=reopen();assert {r['id']:(r['session'],r['revision']) for r in rows}==identity
  assert all(r['ready'] and r['audio']['ready'] for r in rows)
  result[mode]={'sessionsUnchanged':True,'allReady':True,'audioReady':True}
 compare()
 for r in rows:
  assert 'READY' in guest(r,'cat /data/rootvmos_audio/health.status')
  assert 'rootvmos_traceur' in guest(r,'cat /proc/self/mountinfo')
  guest(r,'test ! -e /system/app/Traceur/Traceur.apk')
  crash=guest(r,'logcat -b crash -d');(PRIVATE/('final-crash-'+str(r['id'])+'.txt')).write_text(crash,encoding='utf-8')
  assert 'FATAL EXCEPTION' not in crash and 'Fatal signal' not in crash,crash[-2000:]
 shell('test -f /system/app/Traceur/Traceur.apk; ! grep -q rootvmos_traceur /proc/self/mountinfo; '+BASE+'/iris_inet_policy verify')
 actual=shell('P=$(pm path com.rootvmos.viewer); P=${P#package:}; /system/bin/busybox sha256sum "$P"').split()[0]
 version=ET.parse(ROOT/'app/viewer/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
 expected=hashlib.sha256((ROOT/('out/IrisBox-'+version+'.apk')).read_bytes()).hexdigest();assert actual==expected
 result.update({'guestBootCrashes':0,'traceurMaskPrivate':True,'apkSha256':actual,'oldProjectCheck':'compare the current-run read-only snapshot separately; no Host reboot is implied'})
 (PRIVATE/'recovery-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
if __name__=='__main__':main()
