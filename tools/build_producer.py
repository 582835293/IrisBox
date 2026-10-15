"""复用现有AOSP只读缓存，在独立/tmp目录编译新项目producer。"""
import argparse,hashlib,json,shlex,subprocess,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--host',required=True);parser.add_argument('--key',type=Path,default=Path.home()/'.ssh/id_ed25519');parser.add_argument('--aosp',required=True);parser.add_argument('--cache',type=Path,default=ROOT/'out/native-producer/compile_variables.json');args=parser.parse_args()
    ssh=['ssh','-i',str(args.key),'-o','BatchMode=yes','-o','ConnectTimeout=8',args.host]
    scp=['scp','-i',str(args.key),'-o','BatchMode=yes']
    remote='/tmp/rootvmos-producer-'+uuid.uuid4().hex
    result=ROOT/'out/native-producer';result.mkdir(parents=True,exist_ok=True)
    subprocess.run(ssh+['mkdir -m 0700 '+remote+' && mkdir '+remote+'/build'],check=True,timeout=15)
    for file in ('buffer_producer.cpp','build_direct.py'):
        subprocess.run(scp+[str(ROOT/'engine/native/buffer'/file),args.host+':'+remote+'/'+file],check=True,timeout=20)
    if args.cache.is_file():
        subprocess.run(scp+[str(args.cache),args.host+':'+remote+'/build/compile_variables.json'],check=True,timeout=20)
    command='python3 '+shlex.quote(remote+'/build_direct.py')+' '+shlex.quote(args.aosp)+' '+shlex.quote(remote)
    process=subprocess.run(ssh+[command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=180)
    (result/'build.log').write_bytes(process.stdout)
    if process.returncode:raise RuntimeError(process.stdout.decode('utf-8','replace')[-5000:])
    for file in ('iris_buffer_producer','build_manifest.json','commands.json','elf_inspection.txt','compile_variables.json'):
        subprocess.run(scp+[args.host+':'+remote+'/build/'+file,str(result/file)],check=True,timeout=25)
    manifest=json.loads((result/'build_manifest.json').read_text())
    assert manifest['source_sha256']==hashlib.sha256((ROOT/'engine/native/buffer/buffer_producer.cpp').read_bytes()).hexdigest()
    binary=(result/'iris_buffer_producer').read_bytes();assert hashlib.sha256(binary).hexdigest()==manifest['binary_sha256']
    assert binary[:4]==b'\x7fELF' and int.from_bytes(binary[18:20],'little')==183
    (ROOT/'engine/runtime/display/buffer_bridge/iris_buffer_producer').write_bytes(binary)
    (ROOT/'reference/producer-build.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8',newline='\n')
    print('Producer compiled in isolated directory:',remote)
    print(json.dumps(manifest))

if __name__=='__main__':main()
