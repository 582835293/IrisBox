"""Check that shipping locales cover exactly the same UI, including dynamic status keys."""
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
from generate_i18n import key
translations=json.loads((ROOT/'app/viewer/i18n/translations.json').read_text(encoding='utf8'))
en={e.get('name'):e.text for e in ET.parse(ROOT/'app/viewer/res/values/strings.xml').getroot()}
zh={e.get('name'):e.text for e in ET.parse(ROOT/'app/viewer/res/values-zh/strings.xml').getroot()}
assert en.keys()==zh.keys()=={'app_name'}|{key(k) for k in translations}
assert zh['app_name']=='虹膜空间'
assert all(not re.search('[\u4e00-\u9fff]',v) for v in en.values())
for file in (ROOT/'app/viewer/src').rglob('*.java'):
    for resource in re.findall(r'R\.string\.(ui_[a-f0-9]+)',file.read_text(encoding='utf8')):assert resource in en,(file,resource)
print('PASS EN/ZH resource parity, English fallback and all Java resource references:',len(en),'entries')
