"""两个新Guest使用相同文件名、不同内容，验证MediaProvider导入导出和隔离。"""
import sys,json,uuid,hashlib,urllib.request,urllib.parse,urllib.error,time,argparse
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from device import API,ROOT
name='RootVMOS_check_'+uuid.uuid4().hex[:8]+'.txt'
parser=argparse.ArgumentParser();parser.add_argument('--ids',type=int,nargs=2);args=parser.parse_args()
results=[]
def request_ready(request):
    # 上个导出已送完HTTP正文时，Guest helper可能仍在退出；423明确表示尚未受理。
    for attempt in range(8):
        try:return urllib.request.urlopen(request,timeout=45)
        except urllib.error.HTTPError as error:
            detail=error.read().decode('utf-8','replace');error.close()
            if error.code not in (423,429) or attempt==7:raise RuntimeError(f'{error.code}: {detail}')
            time.sleep(0.5)
with API() as api:
    states={r['id']:r for r in api.call('/state?offset=0&limit=20')['instances']}
    ids=args.ids or [r['id'] for r in sorted(states.values(),key=lambda r:r['number'])[:2]]
    assert len(ids)==2 and len(set(ids))==2 and all(1007<=id<=1134 for id in ids)
    for id in ids:
        r=states[id];assert r['ready']
        query=urllib.parse.urlencode({'id':id,'session':r['session'],'name':name})
        body=f'RootVMOS independent Guest {id}\n文件交换验收\n'.encode('utf-8')
        headers={'Authorization':'Bearer '+api.token,'Content-Type':'application/octet-stream'}
        request=urllib.request.Request(f'http://127.0.0.1:{api.port}/files/import?'+query,data=body,headers=headers,method='POST')
        with request_ready(request) as response:
            assert response.status==201;created=json.load(response)
        digest=hashlib.sha256(body).hexdigest();assert created['sha256']==digest and created['bytes']==len(body)
        request=urllib.request.Request(f'http://127.0.0.1:{api.port}/files/export?'+query,headers={'Authorization':'Bearer '+api.token})
        with request_ready(request) as response:assert response.read()==body
        results.append({'id':id,'name':name,'bytes':len(body),'sha256':digest,'roundtrip':'PASS'})
(ROOT/'validation/private/file-exchange.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps(results))
