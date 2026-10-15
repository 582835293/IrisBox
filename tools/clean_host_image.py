"""Build a clean flash image without Magisk from the static factory partitions.

Run on Linux, against an offline Raspberry Vanilla image. Never accepts a block
device; never copies the source userdata partition. Does not flash any device.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess


def sha(file, offset=0, length=None):
    digest = hashlib.sha256()
    with open(file, 'rb') as stream:
        stream.seek(offset)
        remaining = length if length is not None else os.fstat(stream.fileno()).st_size-offset
        while remaining:
            data = stream.read(min(4*1024*1024, remaining))
            if not data:
                raise RuntimeError('Truncated image')
            digest.update(data)
            remaining -= len(data)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--factory', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--userdata-gib', type=int, choices=(8, 12, 16, 24, 32), default=12)
    args = parser.parse_args()
    source = args.factory.resolve(strict=True)
    dest = args.out.resolve()
    assert source.is_file() and str(source).startswith('/home/'), 'Offline factory file required'
    assert str(dest).startswith('/tmp/irisbox-host-') and not dest.exists()
    assert source != dest and source.stat().st_size > 3*1024**3
    with source.open('rb') as stream:
        mbr = stream.read(512)
    assert mbr[510:] == b'\x55\xaa'
    parts = []
    for i in range(4):
        entry = mbr[446+i*16:462+i*16]
        start, sectors = struct.unpack_from('<II', entry, 8)
        parts.append({'number': i+1, 'type': entry[4], 'offset': start*512, 'bytes': sectors*512})
    assert [p['type'] for p in parts] == [12, 131, 131, 131]
    assert parts[3]['offset']+parts[3]['bytes'] == source.stat().st_size
    assert all(parts[i]['offset']+parts[i]['bytes'] == parts[i+1]['offset'] for i in range(3))
    source_userdata_bytes=parts[3]['bytes']
    with source.open('rb') as stream:
        stream.seek(parts[3]['offset']+1024+92)
        expected_features=struct.unpack('<III',stream.read(12))
    parts[3]['bytes']=args.userdata_gib*1024**3
    assert parts[3]['bytes']>=source_userdata_bytes
    image_bytes=parts[3]['offset']+parts[3]['bytes']
    report = {'format': 1, 'product': 'IrisBox host preview', 'userdata': 'fresh ext4; source userdata NEVER copied',
              'magisk': 'not bundled or installed', 'flashTested': False, 'partitions': parts,
              'bootStorage': 'USB disk /dev/block/sda2,sda3,sda4; not an SD/mmc image',
              'sourceFactoryUserdataBytes':source_userdata_bytes,'freshUserdataBytes':parts[3]['bytes'],
              'minimumMediumBytes':image_bytes,'userdataEnlarged':True}
    # Match factory partition bytes against its original immutable raw images.
    for part, name in zip(parts[:3], ('boot.img', 'system.img', 'vendor.img')):
        raw = source.parent/name
        assert raw.is_file() and raw.stat().st_size == part['bytes']
        part['sha256'] = sha(source, part['offset'], part['bytes'])
        assert part['sha256'] == sha(raw), name+' differs from factory'
        print(name+' verified', flush=True)
    dest.parent.mkdir(parents=True, exist_ok=True)
    userdata = dest.parent/'fresh-userdata.ext4'
    with userdata.open('xb') as stream:
        stream.truncate(parts[3]['bytes'])
    subprocess.run(['mke2fs', '-q', '-t', 'ext4', '-F', '-L', 'userdata', '-m', '0', str(userdata)], check=True)
    with userdata.open('rb') as stream:
        stream.seek(1024+92)
        assert struct.unpack('<III',stream.read(12))==expected_features,'Review filesystem feature changes'
    subprocess.run(['e2fsck', '-fn', str(userdata)], check=True)
    listing = subprocess.check_output(['debugfs', '-R', 'ls -p /', str(userdata)], stderr=subprocess.STDOUT).decode()
    names = [line.split('/')[5] for line in listing.splitlines() if line.startswith('/')]
    assert set(names) <= {'.', '..', 'lost+found'} and 'lost+found' in names, listing
    report['freshUserdataRoot'] = names
    parts[3]['sha256'] = sha(userdata)
    with source.open('rb') as src, dest.open('xb') as out:
        remaining = parts[3]['offset']
        while remaining:
            data = src.read(min(4*1024*1024, remaining))
            assert data
            out.write(data)
            remaining -= len(data)
        with userdata.open('rb') as data:
            while True:
                chunk = data.read(4*1024*1024)
                if not chunk:
                    break
                if any(chunk):
                    out.write(chunk)
                else:
                    out.seek(len(chunk), 1)
        out.truncate(image_bytes)
        # Preserve the original boot/system/vendor entries; enlarge only blank userdata.
        out.seek(446+3*16+12)
        out.write(struct.pack('<I',parts[3]['bytes']//512))
        out.flush()
        os.fsync(out.fileno())
    with dest.open('rb') as stream:
        final_mbr=stream.read(512)
    assert final_mbr[510:]==b'\x55\xaa'
    for i,part in enumerate(parts):
        start,sectors=struct.unpack_from('<II',final_mbr,446+i*16+8)
        assert start*512==part['offset'] and sectors*512==part['bytes']
    report['mbrValidated']=True
    for part in parts:
        assert sha(dest, part['offset'], part['bytes']) == part['sha256']
    report['image'] = {'name': dest.name, 'bytes': dest.stat().st_size, 'sha256': sha(dest)}
    dest.with_suffix('.json').write_text(json.dumps(report, indent=2)+'\n')
    print('CLEAN_IMAGE_VERIFIED '+json.dumps(report['image']), flush=True)
    subprocess.run(['xz', '-T2', '-3', '--keep', str(dest)], check=True)
    packed = Path(str(dest)+'.xz')
    report['compressed'] = {'name': packed.name, 'bytes': packed.stat().st_size, 'sha256': sha(packed)}
    dest.with_suffix('.json').write_text(json.dumps(report, indent=2)+'\n')
    print('COMPRESSED '+json.dumps(report['compressed']), flush=True)


if __name__ == '__main__':
    main()
