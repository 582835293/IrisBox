"""Prepare public release hashes and bilingual download pages; never include private Google ROMs."""
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
VERSION=ET.parse(ROOT/'app/viewer/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
TAG='v'+VERSION+'-preview'
REPO='https://github.com/582835293/IrisBox'


def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(4*1024*1024),b''):value.update(block)
    return value.hexdigest()


def main():
    release=ROOT/'out/release';release.mkdir(parents=True,exist_ok=True)
    apk='IrisBox-'+VERSION+'.apk'
    for name in (apk,'IrisBox-rpi4-a13-aosp.irisrom'):
        source=ROOT/'out'/name;dest=release/name
        if not dest.exists() or digest(source)!=digest(dest):shutil.copyfile(source,dest)
    names=[apk,'IrisBox-rpi4-a13-aosp.irisrom',
           'IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz',
           'IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json','IrisBox-guest-root-sources.zip','IrisBox-upstream-sources.zip']
    artifacts=[{'file':name,'bytes':(release/name).stat().st_size,'sha256':digest(release/name)} for name in names if (release/name).exists()]
    direct={apk,'IrisBox-guest-root-sources.zip','IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json'}
    for row in artifacts:
        row['distribution']='github-release' if row['file'] in direct else 'awaiting-owner-mirror'
        # Unchanged source and host metadata assets already exist in v0.1.2.
        asset_tag=TAG if row['file']==apk else 'v0.1.2-preview'
        row['downloadUrl']=REPO+'/releases/download/'+asset_tag+'/'+row['file'] if row['file'] in direct else None
    assert all(row['bytes']<2*1024**3 for row in artifacts)
    for row in artifacts:
        assert 'google' not in row['file'].lower()
    app=ET.parse(ROOT/'app/viewer/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
    data={'product':'IrisBox','chineseName':'虹膜空间','release':TAG,'repository':REPO,
          'package':app.get('package'),'versionName':app.get(ns+'versionName'),'versionCode':int(app.get(ns+'versionCode')),
          'engineVersion':json.loads((ROOT/'out/engine-manifest.json').read_text())['version'],
          'publicRomGoogleIncluded':False,'hostMagiskIncluded':False,'freshCardFlashTested':False,
          'userNamespaceIsolation':True,'legacyDataMigrationRequired':True,'artifacts':artifacts}
    (release/'release-manifest.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    (release/'SHA256SUMS').write_text(''.join(row['sha256']+'  '+row['file']+'\n' for row in artifacts),encoding='ascii')
    (release/'上传说明.txt').write_text(
        '虹膜空间 / IrisBox '+VERSION+'\n\n'
        '请上传下列当前发行文件及 SHA256SUMS、release-manifest.json 到 Google Drive（国外）和/或夸克网盘（国内），然后提供真实分享链接与提取码。旧版APK可留存，但不属于本版清单。\n\n'
        +'\n'.join(row['file'] for row in artifacts)+'\n\n'
        '宿主镜像仅支持 USB/sda、16GB以上USB盘及12GiB全新空白userdata，不是SD/mmc镜像；首次物理刷盘/启动未验证。宿主Magisk自行安装。\n'
        '0.1.2/e13现有实例需停机备份并迁移User namespace。不要上传私人Google ROM、密钥、账号或validation/private；网盘文件必须与本版SHA256SUMS一致。\n',encoding='utf-8')
    (ROOT/'reference/release-manifest.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    for zh in (False,True):
        lines=['# '+('下载 · 虹膜空间 / IrisBox' if zh else 'Downloads · IrisBox'),'',
               '[English](DOWNLOADS.md) · [简体中文](DOWNLOADS.zh-CN.md)','',
               ('预览版：宿主镜像仅支持USB/sda和16GB以上USB盘，非SD卡，首次物理启动尚未验证。宿主 Magisk 自行安装；公开 ROM 不含 Google。' if zh else 'Preview: USB/sda boot only, 16 GB or larger USB disk; not SD/mmc. Physical first boot is untested. Install host Magisk yourself. The public ROM excludes Google apps.'),'',
               '| '+('文件 | 大小 | SHA-256' if zh else 'File | Size | SHA-256')+' |','|---|---:|---|']
        for row in artifacts:
            label='['+row['file']+']('+row['downloadUrl']+')' if row['downloadUrl'] else '`'+row['file']+'` — '+('待网盘链接' if zh else 'mirror link pending')
            lines.append('| '+label+' | '+format(row['bytes']/1048576,'.1f')+' MiB | `'+row['sha256']+'` |')
        lines += ['', '[SHA256SUMS]('+REPO+'/releases/download/'+TAG+'/SHA256SUMS) · [release-manifest.json]('+REPO+'/releases/download/'+TAG+'/release-manifest.json)', '',
                  '## '+('网盘镜像' if zh else 'Drive mirrors'),'',
                  ('Google Drive（国外）和夸克网盘（国内）：待维护者上传并提供分享链接。GitHub目前提供APK、实例Root/BusyBox源码及校验文件；大ROM、宿主镜像和完整内核源码留作网盘分发。所有镜像必须与SHA256SUMS一致。' if zh else 'Google Drive (international) and Quark Drive (China): awaiting owner uploads and share links. GitHub currently hosts the APK, guest Root/BusyBox sources and checksums. Large ROM, host image and complete kernel sources await owner mirrors. Mirror files must match SHA256SUMS.'),'',
                  ('`.irisrom` 在 App 内导入；`.img.xz` 是给16GB以上备用USB盘刷入的整盘宿主镜像。两者不可互换。' if zh else 'Import `.irisrom` inside the App. The `.img.xz` is a whole-disk host image for a spare USB disk of 16 GB or larger. These formats are not interchangeable.'),'',
                  ('[安装与刷机边界](docs/08_国际化与发布.md)' if zh else '[Installation and flash boundaries](docs/en/installation.md)')]
        (ROOT/('DOWNLOADS.zh-CN.md' if zh else 'DOWNLOADS.md')).write_text('\n'.join(lines)+'\n',encoding='utf8')
    print(json.dumps({'engine':data['engineVersion'],'artifacts':len(artifacts),'totalBytes':sum(r['bytes'] for r in artifacts)}))


if __name__=='__main__':main()
