"""单源板卡 Profile 管理与产物同步工具（基于 engine/boards/*/board.json）。"""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOARDS_SRC = ROOT / 'engine' / 'boards'
RUNTIME_BOARDS = ROOT / 'engine' / 'runtime' / 'boards'
ASSETS_DIR = ROOT / 'app' / 'viewer' / 'assets'

SAFE_TOKEN_RE = re.compile(r'^[A-Za-z0-9_./,:@+\- ]+$')
SAFE_IDENT_RE = re.compile(r'^[a-z0-9][a-z0-9_-]{0,63}$')
SAFE_ENV_RE = re.compile(r'^[A-Z0-9_]{1,48}=[A-Za-z0-9_.:-]{1,64}$')
FORBIDDEN_META = '\r\n\0$`;&|><\'"\\(){}'


def _check_label(value, field):
    if not isinstance(value, str) or not value.strip() or any(ch in value for ch in FORBIDDEN_META):
        raise ValueError(f'Unsafe label string in {field}: {value!r}')
    return value


def _check_safe(value, field, allow_empty=False):
    if value == '' and allow_empty:
        return value
    if not isinstance(value, str) or any(ch in value for ch in FORBIDDEN_META):
        raise ValueError(f'Unsafe string in {field}: {value!r}')
    if not SAFE_TOKEN_RE.match(value):
        raise ValueError(f'Disallowed characters in {field}: {value!r}')
    return value


def _check_rel_list(items, field):
    out = []
    for item in items:
        _check_safe(item, field)
        if ' ' in item or item.startswith('/') or '..' in item.split('/'):
            raise ValueError(f'Invalid relative path in {field}: {item!r}')
        out.append(item)
    return out


def _check_abs_list(items, field, prefix):
    out = []
    for item in items:
        _check_safe(item, field)
        if ' ' in item or not item.startswith(prefix) or '..' in item.split('/'):
            raise ValueError(f'Invalid absolute path in {field}: {item!r}')
        out.append(item)
    return out


def validate_board(board):
    profile = board['profile']
    if not SAFE_IDENT_RE.match(profile):
        raise ValueError(f'Invalid board profile id: {profile!r}')
    host = board['host']
    guest = board['guest']
    gpu = guest['gpu']
    mem = guest['memory']
    _check_label(board['displayName'], 'displayName')
    _check_label(board.get('preflightLabel', f"支持配置：{board['displayName']} / {profile}"), 'preflightLabel')
    for d in host['devices']:
        _check_safe(d, 'host.devices')
        if ' ' in d or '|' in d:
            raise ValueError(f'Invalid device name: {d!r}')
    _check_safe(host['abi'], 'host.abi')
    if not isinstance(host['androidApi'], int) or host['androidApi'] < 29:
        raise ValueError('host.androidApi must be >= 29')
    for fp in host['fingerprints']:
        _check_safe(fp, 'host.fingerprints')
    _check_abs_list(host.get('requiredDevices', []), 'host.requiredDevices', '/dev/')
    for iface in host.get('uplinks', []):
        if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,16}', iface):
            raise ValueError(f'Invalid uplink interface: {iface!r}')
    _check_abs_list([host['stockBpfProg']], 'host.stockBpfProg', '/sys/fs/bpf/')
    _check_abs_list([host['stockBpfMap']], 'host.stockBpfMap', '/sys/fs/bpf/')
    if 'requireExternalMount' in host and not isinstance(host['requireExternalMount'], bool):
        raise ValueError('host.requireExternalMount must be boolean')
    if not re.fullmatch(r'[a-z0-9_-]{1,32}', guest['hardwareName']):
        raise ValueError(f"Invalid guest.hardwareName: {guest['hardwareName']!r}")
    if guest.get('fstabName'):
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,48}', guest['fstabName']):
            raise ValueError(f"Invalid guest.fstabName: {guest['fstabName']!r}")
    _check_safe(gpu['driver'], 'guest.gpu.driver')
    for d in gpu.get('devDirs', []):
        if not re.fullmatch(r'[a-zA-Z0-9_.-]{1,32}', d):
            raise ValueError(f'Invalid gpu.devDirs item: {d!r}')
    _check_abs_list(gpu.get('devNodes', []), 'guest.gpu.devNodes', '/dev/')
    if gpu.get('vendorGrallocPayload'):
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,96}', gpu['vendorGrallocPayload']):
            raise ValueError('Invalid vendorGrallocPayload')
        _check_abs_list([gpu['vendorGrallocTarget']], 'guest.gpu.vendorGrallocTarget', '/vendor/')
    _check_rel_list(gpu.get('vulkanRemoveFiles', []), 'guest.gpu.vulkanRemoveFiles')
    if gpu.get('openglEnv') and not SAFE_ENV_RE.match(gpu['openglEnv']):
        raise ValueError(f"Invalid openglEnv: {gpu['openglEnv']!r}")
    for key in ('minMiB', 'maxMiB', 'defaultMiB', 'hostReserveMiB', 'hostStopBelowKiB', 'maxTasks', 'googleMaxTasks'):
        if not isinstance(mem[key], int) or mem[key] <= 0:
            raise ValueError(f'Invalid memory field {key}: {mem[key]!r}')
    if not (256 <= mem['minMiB'] <= mem['defaultMiB'] <= mem['maxMiB'] <= 32768):
        raise ValueError('Invalid memory bounds in board.json')
    return board


def load_boards(root=ROOT, include_unverified=False):
    boards_dir = Path(root) / 'engine' / 'boards'
    boards = []
    for path in sorted(boards_dir.glob('*/board.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        if path.parent.name != data.get('profile'):
            raise ValueError(f'Folder {path.parent.name} != profile {data.get("profile")}')
        validate_board(data)
        if include_unverified or data.get('verified') is True:
            boards.append(data)
    if not boards:
        raise ValueError('No verified board profiles found')
    return boards


def render_board_env(board):
    host = board['host']
    guest = board['guest']
    gpu = guest['gpu']
    mem = guest['memory']
    media = guest.get('media', {})
    lines = [
        f"BOARD_PROFILE={board['profile']}",
        f"BOARD_DISPLAY_NAME={board['displayName']}",
        f"BOARD_ALLOWED_DEVICES={'|'.join(host['devices'])}",
        f"BOARD_ABI={host['abi']}",
        f"BOARD_ANDROID_API={host['androidApi']}",
        f"BOARD_ALLOWED_FINGERPRINTS={'|'.join(host['fingerprints'])}",
        f"BOARD_REQUIRED_DEVS={' '.join(host.get('requiredDevices', []))}",
        f"BOARD_UPLINK_CANDIDATES={' '.join(host.get('uplinks', ['wlan0']))}",
        f"BOARD_BPF_MODE={host.get('bpfMode', 'a13-legacy-single')}",
        f"BOARD_STOCK_BPF_PROG={host['stockBpfProg']}",
        f"BOARD_STOCK_BPF_MAP={host['stockBpfMap']}",
        f"BOARD_REQUIRE_EXTERNAL_MOUNT={1 if host.get('requireExternalMount', False) else 0}",
        f"BOARD_HARDWARE={guest['hardwareName']}",
        f"BOARD_FSTAB_NAME={guest.get('fstabName', '')}",
        f"BOARD_GPU_DRIVER={gpu['driver']}",
        f"BOARD_GPU_DEV_DIRS={' '.join(gpu.get('devDirs', []))}",
        f"BOARD_GPU_DEV_NODES={' '.join(gpu.get('devNodes', []))}",
        f"BOARD_VENDOR_GRALLOC_PAYLOAD={gpu.get('vendorGrallocPayload', '')}",
        f"BOARD_VENDOR_GRALLOC_TARGET={gpu.get('vendorGrallocTarget', '')}",
        f"BOARD_VULKAN_REMOVE_FILES={' '.join(gpu.get('vulkanRemoveFiles', []))}",
        f"BOARD_EXPECTED_STOCK_GLES={gpu.get('expectedStockGlesVersion', '')}",
        f"BOARD_OPENGL_MODE_GLES={gpu.get('openglModeGlesVersion', '')}",
        f"BOARD_GLES_OVERRIDE_ENV={gpu.get('openglEnv', '')}",
        f"BOARD_REMOVE_CODEC_INCLUDES={' '.join(media.get('removeCodecIncludes', []))}",
        f"BOARD_MEM_MIN_MIB={mem['minMiB']}",
        f"BOARD_MEM_MAX_MIB={mem['maxMiB']}",
        f"BOARD_MEM_DEFAULT_MIB={mem['defaultMiB']}",
        f"BOARD_HOST_RESERVE_MIB={mem['hostReserveMiB']}",
        f"BOARD_HOST_STOP_BELOW_KIB={mem['hostStopBelowKiB']}",
        f"BOARD_MAX_TASKS={mem['maxTasks']}",
        f"BOARD_GOOGLE_MAX_TASKS={mem['googleMaxTasks']}",
    ]
    return '\n'.join(lines) + '\n'


def render_preflight_block(boards):
    lines = [
        '# BEGIN IRIS_BOARDS',
        'IRIS_DEV=$(/system/bin/getprop ro.product.device)',
        'IRIS_ABI=$(/system/bin/getprop ro.product.cpu.abi)',
        'IRIS_SDK=$(/system/bin/getprop ro.build.version.sdk)',
        'IRIS_FP=$(/system/bin/getprop ro.build.fingerprint)',
        'IRIS_PROFILE=""',
        'IRIS_DEV_MATCHED=0',
        'IRIS_ABI_MATCHED=0',
        'IRIS_SDK_MATCHED=0',
    ]
    for b in boards:
        h = b['host']
        dev_case = '|'.join(h['devices'])
        fp_case = '|'.join(h['fingerprints'])
        req_devs = ' '.join(h.get('requiredDevices', []))
        uplinks = ' '.join(h.get('uplinks', ['wlan0']))
        req_ext = 1 if h.get('requireExternalMount', False) else 0
        support = b.get('preflightLabel', f"支持配置：{b['displayName']} / {b['profile']}")
        lines += [
            f'if [ -z "$IRIS_PROFILE" ]; then',
            f'    case "$IRIS_DEV" in {dev_case})',
            f'        IRIS_DEV_MATCHED=1',
            f'        if [ "$IRIS_ABI" = "{h["abi"]}" ]; then',
            f'            IRIS_ABI_MATCHED=1',
            f'            if [ "$IRIS_SDK" = "{h["androidApi"]}" ]; then',
            f'                IRIS_SDK_MATCHED=1',
            f'                case "$IRIS_FP" in {fp_case})',
            f'                    IRIS_PROFILE="{b["profile"]}"',
            f'                    IRIS_REQ_DEVS="{req_devs}"',
            f'                    IRIS_UPLINKS="{uplinks}"',
            f'                    IRIS_REQ_EXT_MOUNT="{req_ext}"',
            f'                    IRIS_SUPPORT_LINE="{support}"',
            f'                    ;;',
            f'                esac',
            f'            fi',
            f'        fi',
            f'        ;;',
            f'    esac',
            f'fi',
        ]
    lines += [
        '[ "$IRIS_DEV_MATCHED" = 1 ] || { echo "DEVICE_UNSUPPORTED：当前设备（$IRIS_DEV）尚未适配"; exit 4; }',
        '[ "$IRIS_ABI_MATCHED" = 1 ] || exit 4',
        '[ "$IRIS_SDK_MATCHED" = 1 ] || { echo "ANDROID_UNSUPPORTED：宿主系统版本（SDK $IRIS_SDK）与板卡要求不符"; exit 4; }',
        '[ -n "$IRIS_PROFILE" ] || { echo "BUILD_UNSUPPORTED：宿主构建指纹尚未适配"; exit 4; }',
        'for _REQ_DEV in $IRIS_REQ_DEVS; do [ -e "$_REQ_DEV" ] || { echo "GPU_MISSING：缺少图形设备节点 $_REQ_DEV"; exit 4; }; done',
        '# END IRIS_BOARDS',
    ]
    return '\n'.join(lines)


def _update_preflight_script(path, boards):
    text = path.read_text(encoding='utf-8').replace('\r\n', '\n')
    block = render_preflight_block(boards)
    pattern = re.compile(r'# BEGIN IRIS_BOARDS.*?# END IRIS_BOARDS', re.DOTALL)
    if not pattern.search(text):
        raise ValueError('preflight.sh is missing # BEGIN IRIS_BOARDS ... # END IRIS_BOARDS markers')
    updated = pattern.sub(lambda _: block, text, count=1)
    path.write_text(updated, encoding='utf-8', newline='\n')


def _rom_compat_hashes(compat_dir):
    if not compat_dir.is_dir():
        return None
    base_parent = compat_dir.parent
    result = {}
    for p in sorted(compat_dir.rglob('*')):
        if not p.is_file():
            continue
        rel_inside = p.relative_to(compat_dir)
        if 'google' in rel_inside.parts:
            continue
        result[p.relative_to(base_parent).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    return result


def sync_board_artifacts(root=ROOT, include_unverified=False):
    root = Path(root)
    boards = load_boards(root, include_unverified=include_unverified)

    runtime_boards = root / 'engine' / 'runtime' / 'boards'
    if runtime_boards.exists():
        shutil.rmtree(runtime_boards)
    runtime_boards.mkdir(parents=True, exist_ok=True)

    assets = root / 'app' / 'viewer' / 'assets'
    rom_profiles_dir = assets / 'rom-profiles'
    if rom_profiles_dir.exists():
        shutil.rmtree(rom_profiles_dir)
    rom_profiles_dir.mkdir(parents=True, exist_ok=True)

    for b in boards:
        pdir = runtime_boards / b['profile']
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / 'board.json').write_text(json.dumps(b, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
        (pdir / 'board.env').write_text(render_board_env(b), encoding='utf-8', newline='\n')

        compat_rel = b.get('rom', {}).get('compatDir', 'rom/compat')
        hashes = _rom_compat_hashes(root / compat_rel)
        if hashes is not None:
            payload = json.dumps(hashes, separators=(',', ':'))
            (rom_profiles_dir / f"{b['profile']}.json").write_text(payload, encoding='utf-8', newline='\n')
            if b['profile'] == 'rpi4-a13-v1':
                (assets / 'rom-profile.json').write_text(payload, encoding='utf-8', newline='\n')

    (assets / 'boards.json').write_text(
        json.dumps({'formatVersion': 1, 'boards': boards}, ensure_ascii=False, separators=(',', ':')) + '\n',
        encoding='utf-8',
        newline='\n',
    )
    _update_preflight_script(assets / 'preflight.sh', boards)
    return boards


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', nargs='?', default='sync', choices=['sync'])
    parser.add_argument('--include-unverified', action='store_true')
    args = parser.parse_args()
    boards = sync_board_artifacts(ROOT, include_unverified=args.include_unverified)
    print('Synced board profiles:', [b['profile'] for b in boards])


if __name__ == '__main__':
    main()
