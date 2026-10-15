"""Fetch pinned upstream source archives for the binary distribution (never credentials)."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def main():
    out=ROOT/'out/corresponding-sources';out.mkdir(exist_ok=True,parents=True)
    entries=[
        ('topjohnwu/Magisk','e8a58776f1d7bdf852072ad0baa6eceb9a1e4aac','Magisk-v30.7'),
        ('topjohnwu/ndk-box-kitchen','14d189ea3070a8167b3576bf83fe070d4a3441af','busybox-kitchen-1.36.1.1'),
        ('raspberry-vanilla/android_kernel_brcm_rpi','b951c20ef4d009cbde200a3c4c436210689d1280','linux-5.15.150-rpi4'),
        ('topjohnwu/crt0','9dfa67b4d543f1b6bf2e936f560fbe77ca2a226a','magisk-crt0'),
        ('topjohnwu/cxx','b09b91554b392523f633b9e3cbe0b43273528c71','magisk-cxx-rs'),
        ('topjohnwu/libcxx','d5117df3ba7704aab06c3a30b97c7529c931662b','magisk-libcxx'),
        ('LSPosed/LSPlt','cef80a97a73184b4def9b3e1148884365fc173fd','magisk-lsplt'),
        ('lz4/lz4','d44371841a2f1728a3f36839fd4b7e872d0927d3','magisk-lz4'),
        ('topjohnwu/selinux','be1b39a657fee7faacfae548b75cb53302043a01','magisk-selinux'),
        ('topjohnwu/system_properties','b7c2088565fbe13d22fe074960332e89615bb4aa','magisk-system-properties'),
    ]
    records=[]
    # Small independent archives first; the kernel can be preseeded from a mirror.
    entries=entries[:2]+entries[3:]+entries[2:3]
    for repo,revision,name in entries:
        file=out/(name+'.tar.gz')
        if not file.exists():
            partial=file.with_suffix('.partial')
            subprocess.run(['curl.exe','--fail','--location','--silent','--show-error','--retry','2',
                            '--max-time','1800','https://codeload.github.com/'+repo+'/tar.gz/'+revision,
                            '--output',str(partial)],check=True)
            partial.replace(file)
        records.append({'file':file.name,'repository':'https://github.com/'+repo,'revision':revision,
                        'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
        print('SOURCE_ARCHIVED '+name+' '+str(file.stat().st_size),flush=True)
    file=out/'busybox-1.36.1.tar.bz2'
    if not file.exists():
        subprocess.run(['curl.exe','--fail','--location','--retry','2','--max-time','180',
                        'https://busybox.net/downloads/busybox-1.36.1.tar.bz2','--output',str(file)],check=True)
    records.append({'file':file.name,'url':'https://busybox.net/downloads/busybox-1.36.1.tar.bz2',
                    'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
    (out/'SOURCES.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf8')
    release=ROOT/'out/release';release.mkdir(exist_ok=True)
    # Archives already compressed; avoid re-compressing the kernel source.
    with zipfile.ZipFile(release/'IrisBox-upstream-sources.zip','w',zipfile.ZIP_STORED,allowZip64=True) as archive:
        archive.write(out/'SOURCES.json','SOURCES.json')
        for row in records:archive.write(out/row['file'],row['file'])
        archive.write(ROOT/'engine/runtime/root/magisk_30_7/adaptation.json','irisbox-changes/magisk-adaptation.json')
        archive.write(ROOT/'engine/runtime/root/magisk_30_7/apk_assets.patch','irisbox-changes/magisk-apk-assets.patch')
    (ROOT/'reference/upstream-sources.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf8')
    print('UPSTREAM_SOURCE_BUNDLE_READY',flush=True)


if __name__=='__main__':main()
