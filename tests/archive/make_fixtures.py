from pathlib import Path
import hashlib,json,zipfile,copy,warnings
OUT=Path(__file__).parent/'assets';OUT.mkdir(exist_ok=True)
data=b'echo archive-test\n'
base={'formatVersion':1,'compatibility':'rpi4-a13-v1','version':'test','files':{'ctl.sh':{'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}}}
def write(name,manifest,entries):
    with zipfile.ZipFile(OUT/(name+'.zip'),'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('engine.json',json.dumps(manifest))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            for key,value in entries:z.writestr(key,value)
write('valid',base,[('ctl.sh',data)])
j=copy.deepcopy(base);j['files']['ctl.sh']['sha256']='0'*64;write('bad_hash',j,[('ctl.sh',data)])
write('missing',base,[])
write('duplicate',base,[('ctl.sh',data),('ctl.sh',data)])
write('unknown',base,[('ctl.sh',data),('extra.sh',data)])
write('oversize',base,[('ctl.sh',data*1000)])
j=copy.deepcopy(base);j['compatibility']='other-device';write('wrong_profile',j,[('ctl.sh',data)])
j=copy.deepcopy(base);j['files']['../escaped']=j['files'].pop('ctl.sh');write('traversal',j,[('../escaped',data)])
j=copy.deepcopy(base);j['files']['/absolute']=j['files'].pop('ctl.sh');write('absolute',j,[('/absolute',data)])
with zipfile.ZipFile(OUT/'wrong_first.zip','w') as z:z.writestr('ctl.sh',data);z.writestr('engine.json',json.dumps(base))
with zipfile.ZipFile(OUT/'manifest_bomb.zip','w') as z:z.writestr('engine.json',b' '*131073)
print('11 archive fixtures generated')
