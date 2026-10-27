"""Prepare public release hashes and bilingual download pages; never include private Google ROMs."""
import argparse
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
    parser=argparse.ArgumentParser()
    parser.add_argument('--published-assets',type=Path,help='Saved gh release view JSON for an already public release; URLs and hashes are verified before use')
    parser.add_argument('--reuse-assets',type=Path,help='Saved public older release JSON; reuse only identical non-APK resources after size/digest checks')
    args=parser.parse_args()
    published={}
    if args.published_assets:
        observed=json.loads(args.published_assets.read_text(encoding='utf-8-sig'))
        assert observed['tagName']==TAG and observed['isDraft'] is False, 'Require an actually published matching release'
        published={row['name']:row for row in observed['assets']}
    reused={}
    if args.reuse_assets:
        older=json.loads(args.reuse_assets.read_text(encoding='utf-8-sig'))
        assert older['isDraft'] is False and older['tagName']!=TAG
        assert older['tagName'].startswith('v') and '/' not in older['tagName']
        for item in older['assets']:
            assert item['url'].startswith(REPO+'/releases/download/'+older['tagName']+'/')
            if not item['name'].lower().endswith('.apk'):reused[item['name']]=item
    release=ROOT/'out/release';release.mkdir(parents=True,exist_ok=True)
    apk='IrisBox-'+VERSION+'.apk'
    for name in (apk,'IrisBox-rpi4-a13-aosp.irisrom'):
        source=ROOT/'out'/name;dest=release/name
        if not dest.exists() or digest(source)!=digest(dest):shutil.copyfile(source,dest)
    names=[apk,'IrisBox-rpi4-a13-aosp.irisrom',
           'IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz',
           'IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json','IrisBox-guest-root-sources.zip','IrisBox-upstream-sources.zip']
    artifacts=[{'file':name,'bytes':(release/name).stat().st_size,'sha256':digest(release/name)} for name in names if (release/name).exists()]
    for row in artifacts:
        item=published.get(row['file']) or reused.get(row['file'])
        if item:
            assert item and item['state']=='uploaded' and item['size']==row['bytes'],row['file']
            assert item['digest']=='sha256:'+row['sha256'],row['file']
        elif args.published_assets:
            raise AssertionError('No verified published asset: '+row['file'])
        row['distribution']='github-release' if item else 'release-page'
        row['downloadUrl']=item['url'] if item else None
    assert all(row['bytes']<2*1024**3 for row in artifacts)
    for row in artifacts:
        assert 'google' not in row['file'].lower()
    app=ET.parse(ROOT/'app/viewer/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
    data={'product':'IrisBox','chineseName':'虹膜空间','release':TAG,'repository':REPO,
          'package':app.get('package'),'versionName':app.get(ns+'versionName'),'versionCode':int(app.get(ns+'versionCode')),
          'engineVersion':json.loads((ROOT/'out/engine-manifest.json').read_text())['version'],
          'publicRomGoogleIncluded':False,'hostMagiskIncluded':False,'freshCardFlashTested':False,
          'userNamespaceIsolation':True,'legacyDataMigrationRequired':True,
          'googlePlayDownloadTested':True,'googleDeviceCertificationPassed':False,
          'liveOrientationTested':True,'gameQrLoginAndPlayOwnerConfirmed':True,
          'hostCameraExposed':False,'guestProjectDiskQuotaEnforced':False,
          'appLanguageChoices':['system','en','zh-CN'],'languagePersistenceTested':True,
          'clashTutorialInteractionTested':True,'clashLongTermBattlesTested':False,
          'youtubePlaybackTested':True,'angryBirds2GameplayTested':True,
          'angryBirds2TestConditions':{'guestMiB':2048,'hostSwapMiB':2048,'guestRoot':'magisk','scope':'Tutorial slingshot, Red ability and completion score 203332'},
          'hostSwapSizeChoicesMiB':[0,1024,2048,4096],'hostSwapSettingsTested':True,
          'hostSwapAutoRestoreRebootTested':True,'hostSwapOffRemovesOwnedHookTested':True,
          'hostRebootTestedThisEngine':True,'alreadyMapped015NeedsDataMigration':False,
          'dualInstanceTested':True,'dualHeavyGamesTested':True,
          'dualGamesConditions':{'guestMemoryMiB':[1024,2048],'hostSwapMiB':2048,'games':['Clash of Clans','Angry Birds 2'],'scope':'Short tutorial interaction and live previews; game idle reconnect observed'},
          'dualHeavyGamesLongRunTested':False,'artifacts':artifacts}
    (release/'release-manifest.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    (release/'SHA256SUMS').write_text(''.join(row['sha256']+'  '+row['file']+'\n' for row in artifacts),encoding='ascii')
    (release/'上传说明.txt').write_text(
        '虹膜空间 / IrisBox '+VERSION+'\n\n'
        '当前文件及校验信息见GitHub发行页。可另提供Google Drive（国外）或夸克网盘（国内）镜像；分享链接必须真实并与摘要一致。旧版APK不属于本版清单。\n\n'
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
            label='['+row['file']+']('+row['downloadUrl']+')' if row['downloadUrl'] else '`'+row['file']+'` — [GitHub Releases]('+REPO+'/releases)'
            lines.append('| '+label+' | '+format(row['bytes']/1048576,'.1f')+' MiB | `'+row['sha256']+'` |')
        lines += ['', '[SHA256SUMS]('+(published.get('SHA256SUMS',{}).get('url') or REPO+'/releases')+') · [release-manifest.json]('+(published.get('release-manifest.json',{}).get('url') or REPO+'/releases')+')', '',
                  '## '+('网盘镜像' if zh else 'Drive mirrors'),'',
                  ('当前资源及附件见GitHub发行页，直接链接仅在公开附件核验后写入。Google Drive（国外）和夸克网盘（国内）可作额外镜像，目前尚无分享链接。所有镜像必须与SHA256SUMS一致；Google应用、账号及私人实例数据不随资源分发。' if zh else 'See GitHub Releases for current resources; direct links are recorded only after public assets are verified. Google Drive (international) and Quark Drive (China) may provide extra mirrors; no share links are available yet. Mirrors must match SHA256SUMS. Google apps, accounts and private instance data are excluded.'),'',
                  ('`.irisrom` 在 App 内导入；`.img.xz` 是给16GB以上备用USB盘刷入的整盘宿主镜像。两者不可互换。' if zh else 'Import `.irisrom` inside the App. The `.img.xz` is a whole-disk host image for a spare USB disk of 16 GB or larger. These formats are not interchangeable.'),'',
                  ('[安装与刷机边界](docs/08_国际化与发布.md)' if zh else '[Installation and flash boundaries](docs/en/installation.md)')]
        (ROOT/('DOWNLOADS.zh-CN.md' if zh else 'DOWNLOADS.md')).write_text('\n'.join(lines)+'\n',encoding='utf8')
    print(json.dumps({'engine':data['engineVersion'],'artifacts':len(artifacts),'totalBytes':sum(r['bytes'] for r in artifacts)}))


if __name__=='__main__':main()
