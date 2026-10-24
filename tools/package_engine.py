from pathlib import Path
import hashlib, json, zipfile, io
ROOT=Path(__file__).resolve().parents[1]
def main():
    engine=ROOT/'engine/runtime'
    # jar工具默认写入构建时刻；规范时间后相同源码具有相同引擎摘要。
    for jar in engine.rglob('*.jar'):
        out=io.BytesIO()
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as target:
            for name in sorted(source.namelist()):
                entry=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
                target.writestr(entry,source.read(name))
        jar.write_bytes(out.getvalue())
    files={}
    for p in sorted(engine.rglob('*')):
        if p.is_file():
            data=p.read_bytes()
            if p.suffix in ('.sh','.rc'):assert b'\r' not in data, 'Android script must use LF: '+str(p)
            files[p.relative_to(engine).as_posix()]={'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    for required in ('rootvmos_ns','rootvmos_userns','security/userns/libalarm_jni.so','browser/IrisBoxBrowser.apk','iris_inet_policy','display/buffer_bridge/iris_buffer_producer','display/buffer_viewer/broker.jar','display/buffer_viewer/gateway.jar'):
        assert required in files, 'Engine executable missing: '+required
    manifest={'formatVersion':1,'compatibility':'rpi4-a13-v1','version':'0.1.4-e21','files':files}
    out=ROOT/'app/viewer/assets/engine.zip';out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        z.writestr('engine.json',json.dumps(manifest,separators=(',',':')))
        for name in files:z.write(engine/name,name)
    (ROOT/'out').mkdir(exist_ok=True)
    (ROOT/'out/engine-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    profile={p.relative_to(ROOT/'rom').as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'rom/compat').rglob('*')) if p.is_file() and 'google' not in p.relative_to(ROOT/'rom/compat').parts}
    (ROOT/'app/viewer/assets/rom-profile.json').write_text(json.dumps(profile,separators=(',',':')),encoding='utf-8')
    print(f'Engine: {len(files)} files, {out.stat().st_size} bytes, SHA256 {hashlib.sha256(out.read_bytes()).hexdigest()}')
if __name__=='__main__':main()
