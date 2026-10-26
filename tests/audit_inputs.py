"""核验固定可选组件的原始字节，防迁移时换行/格式化破坏已有摘要。"""
import hashlib,json,re,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
def check_list(root,manifest):
    count=0
    for line in manifest.read_text(encoding='utf-8').splitlines():
        if not line.strip():continue
        expected,name=line.split(maxsplit=1);name=name.lstrip('*')
        actual=sha(root/name)
        assert actual==expected,(name,expected,actual)
        count+=1
    return count
g=ROOT/'rom/compat/google'
script=(ROOT/'engine/runtime/google/a13_arm64/guest_layer.sh').read_text(encoding='utf-8')
assert sha(g/'component_manifest.json')==re.search(r'MANIFEST_SHA=([a-f0-9]+)',script)[1]
assert sha(g/'files.sha256')==re.search(r'LIST_SHA=([a-f0-9]+)',script)[1]
assert sha(g/'component_manifest.json') in (g/'component.id').read_text()
if (g/'rootfs').is_dir():print('Google static files:',check_list(g/'rootfs',g/'files.sha256'))
else:print('SKIP optional private Google rootfs: public distribution does not include Google binaries')
m=ROOT/'engine/runtime/root/magisk_30_7'
print('Magisk component files:',check_list(m,m/'SHA256SUMS'))
profile=json.loads((ROOT/'app/viewer/assets/rom-profile.json').read_text())
for name,expected in profile.items():assert sha(ROOT/'rom'/name)==expected,name
print('ROM compat profile:',len(profile),'files PASS')
with zipfile.ZipFile(ROOT/'app/viewer/assets/engine.zip') as archive:
    engine=json.loads(archive.read('engine.json'))
    for name,entry in engine['files'].items():assert sha(ROOT/'engine/runtime'/name)==entry['sha256'],name
    assert 'irisbox_ns' in engine['files']
    elf=(ROOT/'engine/runtime/irisbox_ns').read_bytes()
    assert elf[:4]==b'\x7fELF' and elf[4]==2 and int.from_bytes(elf[18:20],'little')==183
    assert '$BASE/irisbox_ns' in (ROOT/'engine/runtime/ctl.sh').read_text(encoding='utf-8')
    assert '$BASE/irisbox_userns' in (ROOT/'engine/runtime/wrapper.sh').read_text(encoding='utf-8')
    print('Engine source/payload matches APK asset:',len(engine['files']),'files PASS')
