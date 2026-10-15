"""Package the APK's Magisk/BusyBox upstream inputs separately from the host kernel."""
import hashlib
import json
from pathlib import Path
import tarfile
import zipfile
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'out/corresponding-sources'
files=sorted(p for p in source.glob('*') if p.name.endswith(('.tar.gz','.tar.bz2')) and not p.name.startswith('linux-'))
assert len(files)==10,[p.name for p in files]
for p in files:
    with tarfile.open(p) as archive:
        archive.getmembers()  # Fail on truncated downloads before publishing.
records=[{'file':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
out=ROOT/'out/release/IrisBox-guest-root-sources.zip'
with zipfile.ZipFile(out,'w',zipfile.ZIP_STORED) as archive:
    archive.writestr('SOURCES.json',json.dumps(records,indent=2)+'\n')
    archive.writestr('README.txt',
        'IrisBox guest Root and host BusyBox source inputs.\n'
        'Magisk v30.7: e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac. Submodule archives use its pinned gitlinks.\n'
        'BusyBox 1.36.1 plus topjohnwu ndk-box-kitchen 14d189ea3070a8167b3576bf83fe070d4a3441af.\n'
        'Preserved upstream build scripts describe external build dependencies and retrieval.\n'
        'Use the repository tools/patch_magisk_constants.py with official original native binaries to reproduce both documented native constant changes.\n'
        'Manager script changes are in magisk-apk-assets.patch; apply within upstream scripts (strip two path components).\n'
        'Build/sign your Manager using your own key. No private signing key is supplied.\n'
        'Exact upstream signed binary reproduction is not claimed. See THIRD_PARTY_NOTICES.md in the source repository.\n')
    for p in files:archive.write(p,p.name)
    for name in ('LICENSE','adaptation.json','apk_assets.patch'):
        archive.write(ROOT/'engine/runtime/root/magisk_30_7'/name,'irisbox-adapter/'+name)
    archive.write(ROOT/'tools/patch_magisk_constants.py','irisbox-adapter/patch_magisk_constants.py')
with tarfile.open(source/'busybox-1.36.1.tar.bz2') as archive:
    member=next(m for m in archive.getmembers() if m.name.endswith('/LICENSE'))
    (ROOT/'reference/licenses/BusyBox-LICENSE.txt').write_bytes(archive.extractfile(member).read())
print('GUEST_SOURCE_BUNDLE_READY',out.stat().st_size,hashlib.sha256(out.read_bytes()).hexdigest())
