"""Actual kernel credentials, namespace ownership and DAC rejection; dummy data only."""
import json, re, secrets, shlex, subprocess, sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import ADB, BASE, ROOT, shell
from regression import states, guest

def main():
    rows=states();assert len(rows)==2 and all(r['ready'] for r in rows)
    tool='/data/rootvmos/qa/userns_fixture'
    subprocess.run(ADB+['push',str(ROOT/'out/userns_fixture'),'/data/local/tmp/rootvmos-userns-fixture'],check=True,stdout=subprocess.DEVNULL)
    shell('mkdir -p /data/rootvmos/qa; cp /data/local/tmp/rootvmos-userns-fixture '+tool+'; chmod 0700 '+tool)
    result={};bases=[]
    for r in rows:
        pid,tick=r['session'].split(':');id=r['id']
        mapping=shell(f'cat /proc/{pid}/uid_map; cat /proc/{pid}/gid_map').splitlines()
        values=[list(map(int,line.split())) for line in mapping]
        assert len(values)==2 and values[0]==values[1]
        inside,base,span=values[0];assert inside==0 and 2000000000<=base<2012800000 and base%100000==0 and span==100000
        bases.append(base)
        status=shell(f'cat /proc/{pid}/status')
        for tag in ('Uid','Gid'):
            assert list(map(int,re.search(r'^'+tag+r':\s*(.+)$',status,re.M)[1].split()))==[base]*4
        assert guest(r,'id -u; id -g').splitlines()==['0','0']
        # The production verifier also checks NS_GET_USERNS on all six namespaces.
        shell(f'{BASE}/rootvmos_userns verify {id} {pid} {tick}')
        shell(f'if {BASE}/rootvmos_userns verify {id} {pid} {int(tick)+1} >/dev/null 2>&1; then exit 3; fi')
        assert guest(r,'cat /proc/1/comm').strip()=='iris_init'
        nonce=secrets.token_hex(16);other=next(x for x in rows if x['id']!=id)
        args=f'{id} {pid} {tick} {other["id"]} {nonce} fixture-v1'
        shell(tool+' create '+args)
        try:
            path='/data/rootvmos_userns_test_'+nonce
            assert guest(r,'cat '+path+'/own')=='guest-owned-test'
            for kind in ('host','other'):
                assert guest(r,'if cat '+path+'/'+kind+' >/dev/null 2>&1; then echo UNEXPECTED_ACCESS; else echo DENIED; fi').strip()=='DENIED'
        finally:shell(tool+' remove '+args)
        result[str(id)]={'hostUid':base,'guestUid':0,'rangeSize':span,'namespaceOwnership':True,'wrongTickRejected':True,'hostUid0ReadDenied':True,'foreignRootReadDenied':True}
    assert len(set(bases))==2
    p=ROOT/'validation/private/userns-20261021/kernel-isolation.json';p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))

if __name__=='__main__':main()
