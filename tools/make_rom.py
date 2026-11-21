"""离线制作可导入ROM。不会读取宿主或用户数据，输入必须是独立镜像。"""
import argparse, hashlib, json, sys, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
if str(Path(__file__).resolve().parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
import board_profiles

def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
    return h.hexdigest()

def main():
    a=argparse.ArgumentParser()
    a.add_argument('--board',default='rpi4-a13-v1')
    a.add_argument('--system',type=Path,required=True);a.add_argument('--vendor',type=Path,required=True)
    a.add_argument('--compat',type=Path,default=None);a.add_argument('--id',default=None)
    a.add_argument('--name',default=None);a.add_argument('--with-google',action='store_true');a.add_argument('--out',type=Path,default=None);args=a.parse_args()
    board_path=ROOT/'engine/boards'/args.board/'board.json'
    if not board_path.is_file():a.error(f'Unknown board profile: {args.board}')
    board=board_profiles.validate_board(json.loads(board_path.read_text(encoding='utf-8')))
    compat_dir=args.compat or (ROOT/board.get('rom',{}).get('compatDir','rom/compat'))
    rom_id=args.id or ('rpi4-a13-20260916' if args.board=='rpi4-a13-v1' else f"{args.board}-rom")
    rom_name=args.name or ('树莓派4 Android13 · Iris适配' if args.board=='rpi4-a13-v1' else f"{board['displayName']} · Iris适配")
    out_path=args.out or (ROOT/('out/rpi4-a13.irisrom' if args.board=='rpi4-a13-v1' else f'out/{args.board}.irisrom'))
    import re
    if not re.fullmatch('[a-z0-9][a-z0-9._-]{0,63}',rom_id) or '..' in rom_id:a.error('Invalid ROM id')
    inputs={'system.img':args.system,'vendor.img':args.vendor}
    for name,p in list(inputs.items()):
        if not p.is_file() or p.stat().st_size<1048576:a.error(f'Invalid image: {p}')
        with p.open('rb') as f:
            f.seek(1080)
            if f.read(2)!=b'\x53\xef':a.error(f'{p} must be a raw ext4 image, not Android sparse/boot/super')
    for p in sorted(compat_dir.rglob('*')):
        if not p.is_file():continue
        relative=p.relative_to(compat_dir).as_posix()
        if relative.startswith('google/') and not args.with_google:continue
        if p.is_symlink():a.error('Compat must not contain symlinks')
        inputs['compat/'+relative]=p
    host=board['host']
    required_compat=['compat/iris_init','compat/guest.rc','compat/guest_vendor.rc','compat/loaded_files.txt']
    if int(host['androidApi'])<=32:
        required_compat.extend([
            'compat/payload/netd_no_bpf',
            'compat/payload/libnetdbpf_no_bpf.so',
            'compat/payload/libmeminfo_no_bpf.so',
            'compat/payload/vold',
            'compat/payload/libalarm_jni.so',
            'compat/payload/hwcomposer.iris.so',
            'compat/payload/iris_buffer_producer',
        ])
    else:
        required_compat.append('compat/payload/libservice-connectivity_guest_inet.so')
    for name in required_compat:
        if name not in inputs:a.error('Missing '+name)
    manifest={'formatVersion':1,'id':rom_id,'name':rom_name,'abi':host['abi'],'androidApi':host['androidApi'],'compatibility':board['profile'],'hostFingerprint':host['fingerprints'][0],'google':args.with_google,'files':{}}
    for name,p in inputs.items():
        manifest['files'][name]={'size':p.stat().st_size,'sha256':digest(p)}
    out_path.parent.mkdir(parents=True,exist_ok=True)
    temp=out_path.with_suffix('.partial')
    with zipfile.ZipFile(temp,'w',zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as z:
        z.writestr('rom.json',json.dumps(manifest,ensure_ascii=False,separators=(',',':')))
        for name,p in inputs.items():z.write(p,name)
    temp.replace(out_path)
    out_path.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    out_path.with_suffix('.sha256').write_text(digest(out_path)+'  '+out_path.name+'\n',encoding='ascii')
    print(json.dumps({'file':str(out_path),'bytes':out_path.stat().st_size,'files':len(inputs),'google':args.with_google},ensure_ascii=False))
if __name__=='__main__':main()
