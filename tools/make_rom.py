"""离线制作可导入ROM。不会读取宿主或用户数据，输入必须是独立镜像。"""
import argparse, hashlib, json, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FINGERPRINT='Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys'
def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
    return h.hexdigest()
def main():
    a=argparse.ArgumentParser();a.add_argument('--system',type=Path,required=True);a.add_argument('--vendor',type=Path,required=True)
    a.add_argument('--compat',type=Path,default=ROOT/'rom/compat');a.add_argument('--id',default='rpi4-a13-20260916')
    a.add_argument('--name',default='树莓派4 Android13 · Iris适配');a.add_argument('--with-google',action='store_true');a.add_argument('--out',type=Path,default=ROOT/'out/rpi4-a13.irisrom');args=a.parse_args()
    import re
    if not re.fullmatch('[a-z0-9][a-z0-9._-]{0,63}',args.id) or '..' in args.id:a.error('Invalid ROM id')
    inputs={'system.img':args.system,'vendor.img':args.vendor}
    for name,p in list(inputs.items()):
        if not p.is_file() or p.stat().st_size<1048576:a.error(f'Invalid image: {p}')
        with p.open('rb') as f:
            f.seek(1080)
            if f.read(2)!=b'\x53\xef':a.error(f'{p} must be a raw ext4 image, not Android sparse/boot/super')
    for p in sorted(args.compat.rglob('*')):
        if not p.is_file():continue
        relative=p.relative_to(args.compat).as_posix()
        if relative.startswith('google/') and not args.with_google:continue
        if p.is_symlink():a.error('Compat must not contain symlinks')
        inputs['compat/'+relative]=p
    for name in ('compat/iris_init','compat/guest.rc','compat/guest_vendor.rc','compat/loaded_files.txt','compat/payload/libservice-connectivity_guest_inet.so'):
        if name not in inputs:a.error('Missing '+name)
    manifest={'formatVersion':1,'id':args.id,'name':args.name,'abi':'arm64-v8a','androidApi':33,'compatibility':'rpi4-a13-v1','hostFingerprint':FINGERPRINT,'google':args.with_google,'files':{}}
    for name,p in inputs.items():
        manifest['files'][name]={'size':p.stat().st_size,'sha256':digest(p)}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    temp=args.out.with_suffix('.partial')
    with zipfile.ZipFile(temp,'w',zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as z:
        z.writestr('rom.json',json.dumps(manifest,ensure_ascii=False,separators=(',',':')))
        for name,p in inputs.items():z.write(p,name)
    temp.replace(args.out)
    args.out.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    args.out.with_suffix('.sha256').write_text(digest(args.out)+'  '+args.out.name+'\n',encoding='ascii')
    print(json.dumps({'file':str(args.out),'bytes':args.out.stat().st_size,'files':len(inputs),'google':args.with_google},ensure_ascii=False))
if __name__=='__main__':main()
