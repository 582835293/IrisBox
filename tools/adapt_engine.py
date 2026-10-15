"""提取后的一次性产品化迁移；后续直接维护目标源码，不反复执行。"""
from pathlib import Path
import shutil
ROOT = Path(__file__).resolve().parents[1]
def edit(name, old, new):
    p = ROOT / name
    text = p.read_text(encoding='utf-8')
    assert old in text, (name, old)
    p.write_text(text.replace(old, new), encoding='utf-8', newline='\n')

def main():
    e = 'engine/runtime/'
    edit(e+'common.sh', 'ROM=$BASE/rom/a13_20260916', '''# 每个实例锁定 ROM ID；导入另一个 ROM 不改变已有实例。
ROM_ID=
ROM_RECORD=$BASE/config/rom_v1/${1:-7}.id
if [ -f "$ROM_RECORD" ] && [ ! -L "$ROM_RECORD" ]; then ROM_ID=$(cat "$ROM_RECORD")
elif [ -f "$BASE/config/default_rom" ] && [ ! -L "$BASE/config/default_rom" ]; then ROM_ID=$(cat "$BASE/config/default_rom"); fi
case "$ROM_ID" in ''|*[!a-z0-9._-]*) ROM_ID=unselected ;; esac
[ "${#ROM_ID}" -le 64 ] || exit 3
ROM=$BASE/roms/$ROM_ID
COMPAT=$ROM/compat''')
    for file in ('inner.sh', 'ctl.sh'):
        for old in ('iris_init', 'payload', 'guest.rc', 'guest_vendor.rc', 'rc_candidate', 'loaded_files.txt'):
            p = ROOT/e/file
            text = p.read_text(encoding='utf-8')
            text = text.replace('$BASE/'+old, '$COMPAT/'+old)
            p.write_text(text, encoding='utf-8', newline='\n')
    edit(e+'prepare_rom.sh', 'common.sh 7', 'common.sh "${1:-7}"')
    edit(e+'ctl.sh', '"$BASE/prepare_rom.sh"', '"$BASE/prepare_rom.sh" "$ID"')
    edit(e+'ctl.sh', '[ "$AVAILABLE" -ge 1310720 ] || { echo \'Less than 1280 MiB available\' >&2; exit 4; }', '''REQUIRED=$(((MEMORY_MIB + 256) * 1024))
        [ "$REQUIRED" -ge 786432 ] || REQUIRED=786432
        [ "$AVAILABLE" -ge "$REQUIRED" ] || { echo "Insufficient free memory: need ${REQUIRED} KiB; existing instances preserved" >&2; exit 4; }''')
    p=ROOT/e/'instances.sh'; text=p.read_text(encoding='utf-8')
    start=text.index('        for I in 7 8; do'); end=text.index('        printf \'1\\n\' > "$C/initialized"',start)
    text=text[:start]+'''        [ ! -L "$C/next_id" ] || exit 3
        printf '1007\\n' > "$C/next_id"
'''+text[end:]
    text=text.replace('for K in resolution_v1 memory_v1 graphics_v1 root_v1;', 'for K in resolution_v1 memory_v1 graphics_v1 root_v1 rom_v1;')
    text=text.replace('    # 先消耗内部ID', '''    [ -f "$ROM/rom.json" ] && [ -f "$ROM/system.img" ] && [ -f "$ROM/vendor.img" ] || {
        echo 'Import a supported ROM in the App first' >&2; exit 4;
    }
    [ ! -e "$BASE/config/rom_v1/$NEXT.id" ] && [ ! -L "$BASE/config/rom_v1/$NEXT.id" ] || exit 3
    # 先消耗内部ID''')
    text=text.replace('    write_record "$NEXT"', '    printf \'%s\\n\' "$ROM_ID" > "$BASE/config/rom_v1/$NEXT.id"\n    write_record "$NEXT"')
    text=text.replace('"$BASE/config/root_v1/$ID.profile";', '"$BASE/config/root_v1/$ID.profile" "$BASE/config/rom_v1/$ID.id";')
    p.write_text(text,encoding='utf-8',newline='\n')
    # 本产品没有旧的显示前端，去掉启动时对它们的停止调用及无用二进制。
    p=ROOT/e/'display/buffer_viewer/manager.sh'; text=p.read_text(encoding='utf-8')
    start=text.index('    # 清理历史旧版'); end=text.index('    stop_current_display',start)
    text=text[:start]+text[end:]
    p.write_text(text,encoding='utf-8',newline='\n')
    for file in ('display/broker.jar','display/manager.sh','display/capture_loop.sh','display/stop_capture.sh','display/stream/broker.jar','display/stream/manager.sh','display/stream/iris_stream_capture','display/buffer_bridge/gateway.jar','display/buffer_bridge/probe.sh'):
        (ROOT/e/file).unlink(missing_ok=True)
    # Google程序层成为ROM内的可选材料；绝不再从宿主 /system 复制。
    g='google/a13_arm64'
    src=ROOT/e/g
    (ROOT/'rom/compat/google').mkdir(parents=True,exist_ok=True)
    for name in ('component.id','component_manifest.json','files.sha256'):
        shutil.move(str(src/name),str(ROOT/'rom/compat/google'/name))
    edit(e+g+'/guest_layer.sh', 'MODULE=$BASE/google/a13_arm64\nLAYER=$MODULE/rootfs', 'MODULE=$BASE/google/a13_arm64\nLAYER=')
    edit(e+g+'/guest_layer.sh', '. "$BASE/common.sh" "$ID"', '. "$BASE/common.sh" "$ID"\nMODULE=$COMPAT/google\nLAYER=$MODULE/rootfs')
    p=ROOT/e/g/'guest_layer.sh';text=p.read_text(encoding='utf-8')
    start=text.index('    prepare)');end=text.index('    enable|disable)',start)
    text=text[:start]+'''    prepare)
        echo 'Google must be included in the imported ROM compat/google/rootfs; Host files are never copied' >&2
        exit 4
        ;;
'''+text[end:]
    text=text.replace('"$MODULE/guest_layer.sh"', '"$BASE/google/a13_arm64/guest_layer.sh"')
    p.write_text(text,encoding='utf-8',newline='\n')
    for file in ('instances.sh','inner.sh'):
        edit(e+file, '$BASE/google/a13_arm64/component.id', '$COMPAT/google/component.id')
    edit(e+'inner.sh', '$BASE/google/a13_arm64/rootfs', '$COMPAT/google/rootfs')
    # 原策略已存在时只允许复用当前已挂接的同一分派器，禁止抢占或恢复旧策略。
    policy='engine/native/inet/policy.c'
    edit(policy, '    ensure_objects();', '''    int existing_dispatcher = access(PROGRAM, F_OK) == 0;
    ensure_objects();
    if (existing_dispatcher && attached() != prog_id(program_fd)) {
        errno = EBUSY; fail("existing dispatcher changed; refusing Host policy replacement");
    }''')
    p=ROOT/policy;text=p.read_text(encoding='utf-8');start=text.index('    } else if (!strcmp(argv[1], "restore")');end=text.index('    } else return 2;',start)
    text=text[:start]+text[end:];p.write_text(text,encoding='utf-8',newline='\n')
    # 单实例的 abstract socket 也使用本产品名称，Guest文件名仍沿用协议。
    for folder in ('engine/runtime','app'):
        for p in (ROOT/folder).rglob('*'):
            if p.suffix in ('.java','.sh','.cpp'):
                text=p.read_text(encoding='utf-8').replace('iris_buffer_viewer_', 'rootvmos_viewer_')
                p.write_text(text,encoding='utf-8',newline='\n')
    edit('app/viewer/AndroidManifest.xml', 'android:versionCode="1" android:versionName="0.1"', 'android:versionCode="100" android:versionName="0.1.0"')
    edit('app/viewer/AndroidManifest.xml', '<activity android:name=".ListActivity"', '<activity android:name=".SetupActivity" android:exported="true" android:screenOrientation="landscape"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity>\n        <activity android:name=".ListActivity"')
    edit('app/viewer/AndroidManifest.xml', '            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>\n', '')
    edit('app/viewer/src/com/iris/viewer/buffer/ListActivity.java', 'heading.addView(reconnect);', '''heading.addView(reconnect);
        Button roms = new Button(this);
        roms.setText("ROM与引擎");
        roms.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        heading.addView(roms);''')
    edit('app/viewer/src/com/iris/viewer/buffer/ListActivity.java', 'Iris · 选择实例', 'RootVMOS · 实例')
    for p in (ROOT/'app/viewer/res').rglob('*.xml'):
        text=p.read_text(encoding='utf-8').replace('Iris', 'RootVMOS');p.write_text(text,encoding='utf-8',newline='\n')
    old=Path(r'PATH_TO_PREVIOUS_IRIS_PROJECT\experiments\init_migration\runtime_v1\compatibility\inet_permissions')
    for name in ('policy_insns.h',): shutil.copy2(old/'build'/name, ROOT/'engine/native/inet'/name)
    for name in ('embed_bpf.py','guest_inet.patch','build_native.sh'): shutil.copy2(old/name, ROOT/'engine/native/inet'/name)

if __name__=='__main__': main()
