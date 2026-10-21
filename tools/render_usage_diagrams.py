"""Create bilingual, account-free vector usage illustrations for the README."""
from html import escape
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def render(zh):
    steps=(
        [('安装并授权','安装 IrisBox APK','1 · 授权与检查','宿主 Magisk 由你自行安装'),
         ('安装引擎','检查宿主兼容性后','2 · 安装内置引擎','等待安装完成'),
         ('导入 ROM','选择兼容的 .irisrom 文件','3 · 导入 ROM','公开 AOSP ROM 不带 Google'),
         ('创建实例','进入管理页，新增实例','新增实例','可设置内存、分辨率与实例 Root'),
         ('启动实例','点击实例卡片上的启动','启动','首次启动需要等待桌面就绪'),
         ('进入与多开','就绪后打开实例桌面','进入','返回列表可管理其他实例')]
        if zh else
        [('Install and authorize','Install the IrisBox APK','1 · Authorize and check','Install host Magisk yourself first'),
         ('Install the engine','After the host check succeeds','2 · Install engine','Wait for installation to finish'),
         ('Import a ROM','Select a compatible .irisrom file','3 · Import ROM','Public AOSP ROM excludes Google'),
         ('Create an instance','Open the manager and add one','New instance','Choose memory, display and Root'),
         ('Start the instance','Use Start on the instance card','Start','Wait until the Android desktop is ready'),
         ('Enter and multitask','Open the ready instance desktop','Enter','Return to manage the other instances')])
    title='虹膜空间 · 从安装到启动' if zh else 'IrisBox · From installation to Android'
    subtitle='树莓派 4 · 匹配的 AOSP 13 · ARM64' if zh else 'Raspberry Pi 4 · Matched AOSP 13 host · ARM64'
    footer='操作示意图 · 不含设备截图、账号或私密数据 · 详细步骤见安装文档' if zh else 'Illustrated workflow · No device screenshots or account data · See the installation guide'
    out=['<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="760" viewBox="0 0 1400 760" role="img" aria-labelledby="title desc">',
         '<title id="title">'+escape(title)+'</title><desc id="desc">'+escape(' → '.join(s[0] for s in steps))+'</desc>',
         '<defs><linearGradient id="brand"><stop stop-color="#7366ec"/><stop offset="1" stop-color="#449bc4"/></linearGradient></defs>',
         '<rect width="1400" height="760" rx="24" fill="#101826"/>',
         '<g font-family="Segoe UI, Microsoft YaHei, Noto Sans CJK SC, sans-serif">',
         '<circle cx="70" cy="65" r="25" fill="none" stroke="#a39afa" stroke-width="4"/><circle cx="70" cy="65" r="10" fill="#76d9d1"/>',
         '<text x="120" y="76" font-size="32" font-weight="700" fill="#f2f5ff">'+escape(title)+'</text>',
         '<text x="48" y="120" font-size="19" fill="#a8b6cd">'+escape(subtitle)+'</text>']
    for i,(heading,detail,button,note) in enumerate(steps):
        x=48+(i%3)*444;y=154+(i//3)*265
        out += [f'<rect x="{x}" y="{y}" width="416" height="235" rx="18" fill="#1b293d" stroke="#334660"/>',
                f'<circle cx="{x+35}" cy="{y+37}" r="18" fill="url(#brand)"/>',
                f'<text x="{x+35}" y="{y+43}" font-size="18" text-anchor="middle" fill="white" font-weight="700">{i+1}</text>',
                f'<text x="{x+66}" y="{y+45}" font-size="23" font-weight="600" fill="#f2f5ff">{escape(heading)}</text>',
                f'<text x="{x+24}" y="{y+90}" font-size="18" fill="#c0ccdf">{escape(detail)}</text>',
                f'<rect x="{x+24}" y="{y+112}" width="368" height="58" rx="11" fill="#34456b"/>',
                f'<text x="{x+208}" y="{y+148}" text-anchor="middle" font-size="20" font-weight="600" fill="#dedaff">{escape(button)}</text>',
                f'<text x="{x+24}" y="{y+208}" font-size="16" fill="#9fb3ca">{escape(note)}</text>']
    out += ['<text x="48" y="720" font-size="18" fill="#91a5c0">'+escape(footer)+'</text>','</g></svg>']
    target=ROOT/'docs/images'/('usage.zh-CN.svg' if zh else 'usage.en.svg');target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text('\n'.join(out)+'\n',encoding='utf-8',newline='\n')

if __name__=='__main__':
    render(False);render(True);print('Created EN/ZH usage illustrations')
