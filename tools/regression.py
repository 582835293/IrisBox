"""保留用户实例的回归辅助工具；不重置、不删除，不输出账号或凭据。"""
import argparse, hashlib, hmac, json, os, re, secrets, shlex, time
from device import API, BASE, ROOT, shell

PRIVATE = ROOT / 'validation/private' / os.environ.get('IRISBOX_VALIDATION_RUN','logged-in-regression')
assert PRIVATE.resolve().is_relative_to((ROOT/'validation/private').resolve())
PRIVATE.mkdir(parents=True, exist_ok=True)


def states():
    with API() as api:
        return api.call('/state?offset=0&limit=128')['instances']


def guest(row, command, timeout=40):
    assert 1007 <= row['id'] <= 1134 and re.fullmatch(r'\d+:\d+', row['session'])
    script = 'export IRIS_EXPECT_SESSION=' + shlex.quote(row['session']) + '\n'
    script += 'sh ' + BASE + '/ctl.sh exec ' + str(row['id']) + ' sh -c ' + shlex.quote(command)
    return shell(script, timeout=timeout)


def target(number=2):
    return next(r for r in states() if r['number'] == number)


def account_signature(row, key):
    raw = guest(row, 'dumpsys account')
    accounts = sorted(set(re.findall(r'Account\s*\{name=([^}]+type=com\.google)\}', raw)))
    return {'googleCount': len(accounts), 'fingerprint': hmac.new(bytes.fromhex(key), '\n'.join(accounts).encode(), hashlib.sha256).hexdigest()}


def redact(text):
    return re.sub(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}', '[ACCOUNT]', text)


def baseline():
    path = PRIVATE / 'baseline.json'
    if path.exists(): raise RuntimeError('Existing regression baseline retained; do not overwrite it')
    row = target()
    key = secrets.token_hex(32)
    data = {'time': time.time(), 'key': key, 'state': row, 'accounts': account_signature(row, key), 'bootId': shell('cat /proc/sys/kernel/random/boot_id').strip()}
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Baseline saved: instance', row['id'], 'revision', row['revision'], 'Google account count', data['accounts']['googleCount'])


def compare():
    data = json.loads((PRIVATE / 'baseline.json').read_text(encoding='utf-8'))
    row = target()
    assert row['id'] == data['state']['id'] and row['revision'] == data['state']['revision'], 'User instance identity/revision changed'
    assert account_signature(row, data['key']) == data['accounts'], 'Google account identity changed'
    print('PASS: user instance identity, data revision and Google account identity retained')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['baseline', 'compare', 'summary', 'ui', 'tap'])
    parser.add_argument('--number', type=int, default=2)
    parser.add_argument('--text')
    args = parser.parse_args()
    if args.action == 'baseline': baseline(); return
    if args.action == 'compare': compare(); return
    row = target(args.number)
    if args.action == 'summary':
        output = guest(row, '''
echo PACKAGES
for P in com.google.android.gms com.android.vending com.topjohnwu.magisk; do
  dumpsys package "$P" | grep -E 'versionName=|userId=' | head -n 2
done
echo ROOT
/sbin/magisk -v
cat /data/adb/iris.status
echo SYSTEM
getprop sys.boot_completed
df -k /data
cat /proc/meminfo | head -n 3
settings get global http_proxy
settings get global auto_time
settings get global auto_time_zone
echo THIRD_PARTY
pm list packages -3
''')
        print(redact(output))
    elif args.action in ('ui', 'tap'):
        output = guest(row, 'uiautomator dump /data/local/tmp/irisbox-ui.xml', timeout=25)
        if 'dumped to:' not in output: raise RuntimeError(output)
        raw = guest(row, 'cat /data/local/tmp/irisbox-ui.xml')
        (PRIVATE / f'guest-ui-{row["id"]}.xml').write_text(raw, encoding='utf-8')
        import xml.etree.ElementTree as ET
        nodes=list(ET.fromstring(raw).iter('node'))
        if args.action == 'tap':
            found=[n for n in nodes if args.text in (n.get('text'), n.get('content-desc')) and n.get('enabled')=='true']
            if len(found)>1: found=[n for n in found if n.get('clickable')=='true']
            if len(found)!=1: raise RuntimeError('Expected exactly one visible node: '+str(args.text))
            x1,y1,x2,y2=map(int,re.findall(r'\d+',found[0].get('bounds')))
            print(guest(row, f'input tap {(x1+x2)//2} {(y1+y2)//2}')); return
        for node in nodes:
            if node.get('text') or node.get('content-desc'):
                print(redact(node.get('text') or node.get('content-desc')), node.get('resource-id'), node.get('bounds'), node.get('clickable'))


if __name__ == '__main__': main()
