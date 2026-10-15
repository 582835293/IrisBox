"""绕过旧设备ADB sync的大文件限制，只读流式导出已存在的独立ROM镜像。"""
import subprocess,time,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
for name,size in [('system',2147483648),('vendor',268435456)]:
    dest=ROOT/'out/rom-input'/f'{name}.img'
    if dest.is_file() and dest.stat().st_size==size:continue
    cmd=['adb','-s','DEVICE_SERIAL','exec-out','/system/xbin/su','0','/system/bin/cat',f'/data/guest/iris_runtime_v1/rom/a13_20260916/{name}.img']
    p=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    start=time.monotonic();total=0;last=0
    with dest.open('wb') as f:
        while True:
            data=p.stdout.read(1024*1024)
            if not data:break
            f.write(data);total+=len(data)
            if time.monotonic()-last>10:
                print(f'{name}: {total//1048576}/{size//1048576} MiB',flush=True);last=time.monotonic()
    p.wait(timeout=30)
    if p.returncode or total!=size:raise RuntimeError(f'{name}: incomplete {total}/{size}: '+p.stderr.read().decode(errors='replace'))
    print(f'{name}: exported {total} bytes in {time.monotonic()-start:.1f}s',flush=True)
