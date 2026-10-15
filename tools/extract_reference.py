"""一次性提取旧工程的明确输入。只读旧目录，所有输出写到本项目。"""
from pathlib import Path
import hashlib
import json
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]
OLD = Path(r"PATH_TO_PREVIOUS_IRIS_PROJECT")
REF = OLD / "experiments/init_migration/runtime_v1"
ENGINE = ROOT / "engine/runtime"
COMPAT = ROOT / "rom/compat"

def sha(data):
    return hashlib.sha256(data).hexdigest()

def transform(text):
    return (text.replace('/data/guest/iris_runtime_v1', '/data/rootvmos/runtime')
        .replace('com.iris.viewer.buffer', 'com.rootvmos.viewer')
        .replace('com.iris.buffer.viewer', 'com.rootvmos.buffer.viewer')
        .replace('Java_com_iris_viewer_buffer_', 'Java_com_rootvmos_viewer_')
        .replace('iris_runtime_', 'rootvmos_')
        .replace('iris${ID}h', 'rv${ID}h')
        .replace('IRIS_RUNTIME_NET_', 'ROOTVMOS_NET_')
        .replace('TAG=IRIS_NET_', 'TAG=ROOTVMOS_NET_')
        .replace('18762', '19762'))

def put(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)

def is_compat(name):
    return name in ('iris_init', 'guest.rc', 'guest_vendor.rc', 'loaded_files.txt') or name.startswith(('rc_candidate/', 'payload/'))

def main():
    manifest = []
    archive = REF / 'artifacts/runtime_package_20261010_magisk_extensions.tar'
    with tarfile.open(archive) as tar:
        for entry in tar:
            if not entry.isfile():
                continue
            name = entry.name
            if name.endswith('.apk') and not name.startswith(('payload/', 'root/')):
                continue  # 不携带旧宿主 App。
            data = tar.extractfile(entry).read()
            source = 'frozen-20261010/' + name
            current = REF / name
            if current.is_file() and (name.endswith(('.sh', '.rc', '.txt', '.json')) or name.startswith('payload/')):
                data = current.read_bytes()
                source = str(current.relative_to(OLD))
            manifest.append(dict(file=name, source=source, source_sha256=sha(data), mode=oct(entry.mode)))
            if name.endswith(('.sh', '.rc', '.txt', '.json', '.patch')) and not name.startswith('google/a13_arm64/'):
                data = transform(data.decode('utf-8-sig')).replace('\r\n', '\n').encode('utf-8')
            put((COMPAT if is_compat(name) else ENGINE) / name, data)
    for component in ('viewer', 'broker', 'gateway'):
        source_dir = REF / 'display/buffer_viewer' / component
        for sub in ('src', 'res', 'native'):
            source = source_dir / sub
            if not source.is_dir():
                continue
            for file in source.rglob('*'):
                if file.is_file():
                    data = file.read_bytes()
                    manifest.append(dict(file=f'app/{component}/{file.relative_to(source_dir).as_posix()}', source=str(file.relative_to(OLD)), source_sha256=sha(data)))
                    put(ROOT / 'app' / component / file.relative_to(source_dir), transform(data.decode('utf-8-sig')).replace('\r\n', '\n').encode())
        for name in ('build.ps1', 'AndroidManifest.xml'):
            file = source_dir / name
            if file.is_file():
                put(ROOT / 'app' / component / name, transform(file.read_text(encoding='utf-8-sig')).replace('\r\n', '\n').encode())
    for file in (REF / 'compatibility/inet_permissions').glob('*'):
        if file.suffix in ('.c', '.h'):
            put(ROOT / 'engine/native/inet' / file.name, transform(file.read_text(encoding='utf-8-sig')).encode())
    put(ROOT / 'engine/native/iris_runtime_ns.c', (REF / 'iris_runtime_ns.c').read_bytes())
    # 仅保留重建系统组件需要的源码/补丁，不复制 AOSP 树或历史日志。
    for relative in ('compatibility/no_bpf', 'compatibility/networkstats', 'compatibility/guest_services', 'compatibility/documentsui', 'graphics/gralloc_namespace'):
        for file in (REF / relative).rglob('*'):
            if file.is_file() and file.suffix in ('.patch', '.c', '.cpp', '.h', '.bp') and not any(p in ('build', 'upstream', 'before', 'backups') for p in file.relative_to(REF / relative).parts):
                put(ROOT / 'reference/patches' / relative / file.relative_to(REF / relative), file.read_bytes())
    for file in (REF.parent / 'patches').glob('*.patch'):
        put(ROOT / 'reference/patches/init' / file.name, file.read_bytes())
    put(ROOT / 'reference/source-inputs.json', json.dumps(dict(reference=str(OLD), archive_sha256=sha(archive.read_bytes()), files=manifest), ensure_ascii=False, indent=2).encode())
    print(f'Extracted {len(manifest)} explicit inputs; old project unchanged')

if __name__ == '__main__':
    main()
