"""登录后回归：权限拒绝、独立音频、受控音频助手异常恢复。保留账号与数据。"""
import argparse,json,sys,time,urllib.request,urllib.error
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import API,shell,BASE
from regression import target,guest,PRIVATE,compare


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--audio-recovery',action='store_true');args=parser.parse_args()
    results={}
    with API() as api:
        for label,headers in [('missing',{}),('invalid',{'Authorization':'Bearer '+'0'*64})]:
            request=urllib.request.Request('http://127.0.0.1:'+api.port+'/state',headers=headers)
            try:urllib.request.urlopen(request,timeout=10)
            except urllib.error.HTTPError as error:
                assert error.code in (401,403),(label,error.code);results['auth_'+label]=error.code;error.close()
            else:raise AssertionError('Unauthenticated state access')
    r=target()
    assert r['ready'] and r['root']['ready'] and r['audio']['ready']
    results['protectedInstance']={k:r[k] for k in ('id','revision','session')}
    results['audioBefore']=r['audio']
    if args.audio_recovery:
        identity_command='. '+BASE+'/common.sh '+str(r['id'])+'; cat "$(cat "$RUN/latest.logdir")/audio/player.identity"'
        old_player=shell(identity_command).strip()
        pid=guest(r,'getprop init.svc_debug_pid.rootvmos_audio').strip();assert pid.isdigit() and int(pid)>1
        assert 'com.rootvmos.audio.AudioBridge' in guest(r,'cat /proc/'+pid+'/cmdline')
        started=time.monotonic();guest(r,'kill -KILL '+pid)
        restored=False
        while time.monotonic()-started<40:
            now=target();assert now['session']==r['session'] and now['running'],'Main Guest session changed'
            newpid=guest(now,'getprop init.svc_debug_pid.rootvmos_audio').strip()
            new_player=shell(identity_command).strip()
            if newpid and newpid!=pid and new_player!=old_player and now.get('audio',{}).get('ready'):
                first=now['audio']['bytes'];time.sleep(2);stable=target()
                if not stable.get('audio',{}).get('ready') or stable['audio']['bytes']<=first:continue
                assert stable['session']==r['session']
                results['audioRecovery']={'seconds':round(time.monotonic()-started,2),'oldPid':pid,'newPid':newpid,'hostPlayerReplaced':True,'mainSessionUnchanged':True};restored=True;break
            time.sleep(1)
        assert restored,'Audio did not recover'
        guest(now,'am force-stop com.rootvmos.probe; am start -W -n com.rootvmos.probe/.ProbeActivity --ez audio true --ez root true',40)
        for attempt in range(15):
            time.sleep(1);current=target()
            if current.get('audio',{}).get('nonzero',0)>0:
                results['audioRecovery']['nonzeroAfterReconnect']=current['audio']['nonzero'];break
        else:raise AssertionError('Reconnected audio did not capture ordinary-app tone')
    rows=[target(1),target(2)]
    assert all(row['ready'] and row.get('audio',{}).get('ready') for row in rows)
    before={row['id']:row['audio']['bytes'] for row in rows};time.sleep(2)
    results['dualAudio']={}
    for row in rows:
        current=target(row['number']);assert current['session']==row['session'] and current['audio']['bytes']>before[row['id']]
        results['dualAudio'][str(row['id'])]=current['audio']
    compare()
    memory=shell('for I in 1007 1010; do echo INSTANCE=$I; for F in memory.usage_in_bytes memory.max_usage_in_bytes memory.limit_in_bytes memory.failcnt memory.oom_control; do echo "$F"; cat /dev/memcg/rootvmos_$I/$F; done; done; head -n 3 /proc/meminfo')
    (PRIVATE/'memory-dual.txt').write_text(memory,encoding='utf-8')
    (PRIVATE/'runtime-result.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(results,ensure_ascii=False))


if __name__=='__main__':main()
