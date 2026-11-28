#!/usr/bin/env python3
"""Cross-platform IrisBox builder (Linux, macOS, and Windows).

Mirrors build.ps1 and all module build.ps1 scripts without relying on
PowerShell, GNU-only coreutils, or platform-specific binary extensions.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
import glob
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUILD_TOOLS = '34.0.0'
DEFAULT_PLATFORM = 'android-30'
DEFAULT_NDK_VERSION = '28.2.13676358'
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'


@dataclass(frozen=True)
class Toolchain:
    system: str
    sdk_dir: Path
    jdk_dir: Path
    build_tools_version: str
    platform_name: str
    ndk_version: str
    tool_dir: Path
    android_jar: Path
    ndk_bin: Path
    java: Path
    javac: Path
    jar: Path
    keytool: Path
    aapt: Path
    aapt2: Path
    zipalign: Path
    d8_jar: Path
    apksigner_jar: Path
    clang_inet_ns: Path
    clangpp_viewer: Path


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def _detect_sdk_dir(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    for env_key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME'):
        val = os.environ.get(env_key)
        if val:
            candidates.append(Path(val).expanduser())
    system = platform.system()
    home = Path.home()
    if system == 'Windows':
        local_app = os.environ.get('LOCALAPPDATA')
        if local_app:
            candidates.append(Path(local_app) / 'Android' / 'Sdk')
    elif system == 'Darwin':
        candidates.extend([
            home / 'Library' / 'Android' / 'sdk',
            home / 'Android' / 'Sdk',
        ])
    else:
        candidates.extend([
            home / 'Android' / 'Sdk',
            home / 'Android' / 'sdk',
            Path('/opt/android-sdk'),
            Path('/usr/lib/android-sdk'),
        ])
    for cand in candidates:
        if (cand / 'platforms').is_dir() or (cand / 'build-tools').is_dir():
            return cand.resolve()
    searched = ', '.join(str(p) for p in candidates) or '(none)'
    raise FileNotFoundError(
        f'Android SDK not found. Pass --sdk-dir or set ANDROID_SDK_ROOT/ANDROID_HOME. Checked: {searched}'
    )


def _is_valid_jdk(path: Path, exe_ext: str) -> bool:
    return (path / 'bin' / f'javac{exe_ext}').is_file() and (path / 'bin' / f'jar{exe_ext}').is_file()


def _jdk_major_version(path: Path) -> int | None:
    rel = path / 'release'
    if not rel.is_file():
        return None
    for line in rel.read_text(encoding='utf-8', errors='replace').splitlines():
        if line.startswith('JAVA_VERSION='):
            ver = line.split('=', 1)[1].strip().strip('"')
            head = ver.split('.', 1)[0]
            return int(head) if head.isdigit() else None
    return None


def _detect_jdk_dir(explicit: str | None) -> Path:
    system = platform.system()
    exe_ext = '.exe' if system == 'Windows' else ''
    if explicit:
        explicit_path = Path(explicit).expanduser()
        if not _is_valid_jdk(explicit_path, exe_ext):
            raise FileNotFoundError(f'Explicit JDK missing javac/jar: {explicit_path}')
        return explicit_path.resolve()

    candidates: list[Path] = []
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidates.append(Path(java_home).expanduser())
    if system == 'Darwin':
        for args in (['/usr/libexec/java_home', '-v', '17'], ['/usr/libexec/java_home']):
            try:
                out = subprocess.check_output(args, text=True, stderr=subprocess.DEVNULL).strip()
                if out:
                    candidates.append(Path(out))
            except (OSError, subprocess.CalledProcessError):
                pass
        candidates.extend([
            Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'),
            Path('/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'),
            Path('/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home'),
        ])
        for pattern in ('/Library/Java/JavaVirtualMachines/*17*/Contents/Home',):
            candidates.extend(Path(p) for p in sorted(glob.glob(pattern)))
    elif system == 'Linux':
        for pattern in (
            '/usr/lib/jvm/java-17-openjdk*',
            '/usr/lib/jvm/temurin-17*',
            '/usr/lib/jvm/jdk-17*',
            '/usr/lib/jvm/java-17*',
        ):
            candidates.extend(Path(p) for p in sorted(glob.glob(pattern)))
    elif system == 'Windows':
        candidates.append(Path(r'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'))
        for pattern in (
            r'C:\Program Files\Eclipse Adoptium\jdk-17*',
            r'C:\Program Files\Java\jdk-17*',
        ):
            candidates.extend(Path(p) for p in sorted(glob.glob(pattern)))
    javac_bin = shutil.which('javac')
    if javac_bin:
        candidates.append(Path(javac_bin).resolve().parent.parent)

    valid = [cand.resolve() for cand in candidates if _is_valid_jdk(cand, exe_ext)]
    for cand in valid:
        if _jdk_major_version(cand) == 17:
            return cand
    for cand in valid:
        major = _jdk_major_version(cand)
        if major is not None and 11 <= major <= 21:
            return cand
    if valid:
        print(
            f'[IrisBox build] Warning: JDK 17 not found; falling back to {valid[0]} '
            '(build-tools d8.jar may require JDK 17).',
            file=sys.stderr,
        )
        return valid[0]
    raise FileNotFoundError(
        'JDK 17 not found (need javac/jar/keytool). Pass --jdk-dir or set JAVA_HOME.'
    )


def _detect_ndk_bin(sdk_dir: Path, ndk_version: str) -> Path:
    prebuilt_root = sdk_dir / 'ndk' / ndk_version / 'toolchains' / 'llvm' / 'prebuilt'
    if not prebuilt_root.is_dir():
        raise FileNotFoundError(f'Android NDK toolchain directory missing: {prebuilt_root}')
    system = platform.system()
    machine = platform.machine().lower()
    if system == 'Windows':
        tags = ['windows-x86_64']
    elif system == 'Darwin':
        # NDK r25-r28 ships universal macOS host binaries inside darwin-x86_64.
        tags = ['darwin-arm64', 'darwin-x86_64']
    else:
        tags = ['linux-aarch64', 'linux-arm64', 'linux-x86_64'] if machine in ('aarch64', 'arm64') else ['linux-x86_64']
    for tag in tags:
        cand = prebuilt_root / tag / 'bin'
        if cand.is_dir():
            return cand
    existing = [p / 'bin' for p in sorted(prebuilt_root.iterdir()) if (p / 'bin').is_dir()]
    if len(existing) == 1:
        return existing[0]
    raise FileNotFoundError(
        f'Unsupported or missing NDK prebuilt host directory under {prebuilt_root} for {system}/{machine}'
    )


def resolve_toolchain(args: argparse.Namespace) -> Toolchain:
    system = platform.system()
    exe_ext = '.exe' if system == 'Windows' else ''
    cmd_ext = '.cmd' if system == 'Windows' else ''
    sdk_dir = _detect_sdk_dir(args.sdk_dir)
    jdk_dir = _detect_jdk_dir(args.jdk_dir)
    tool_dir = sdk_dir / 'build-tools' / args.build_tools_version
    android_jar = sdk_dir / 'platforms' / args.platform / 'android.jar'
    if not android_jar.is_file():
        raise FileNotFoundError(f'Android SDK platform missing: {android_jar}')
    if not tool_dir.is_dir():
        raise FileNotFoundError(f'Android build-tools missing: {tool_dir}')
    ndk_bin = _detect_ndk_bin(sdk_dir, args.ndk_version)

    java = jdk_dir / 'bin' / f'java{exe_ext}'
    javac = jdk_dir / 'bin' / f'javac{exe_ext}'
    jar = jdk_dir / 'bin' / f'jar{exe_ext}'
    keytool = jdk_dir / 'bin' / f'keytool{exe_ext}'
    aapt = tool_dir / f'aapt{exe_ext}'
    aapt2 = tool_dir / f'aapt2{exe_ext}'
    zipalign = tool_dir / f'zipalign{exe_ext}'
    d8_jar = tool_dir / 'lib' / 'd8.jar'
    apksigner_jar = tool_dir / 'lib' / 'apksigner.jar'
    clang_inet_ns = ndk_bin / f'aarch64-linux-android30-clang{cmd_ext}'
    clangpp_viewer = ndk_bin / f'aarch64-linux-android29-clang++{cmd_ext}'

    for req in (
        java, javac, jar, keytool, aapt, aapt2, zipalign,
        d8_jar, apksigner_jar, clang_inet_ns, clangpp_viewer,
    ):
        if not req.is_file():
            raise FileNotFoundError(f'Required build tool missing: {req}')

    return Toolchain(
        system=system,
        sdk_dir=sdk_dir,
        jdk_dir=jdk_dir,
        build_tools_version=args.build_tools_version,
        platform_name=args.platform,
        ndk_version=args.ndk_version,
        tool_dir=tool_dir,
        android_jar=android_jar,
        ndk_bin=ndk_bin,
        java=java,
        javac=javac,
        jar=jar,
        keytool=keytool,
        aapt=aapt,
        aapt2=aapt2,
        zipalign=zipalign,
        d8_jar=d8_jar,
        apksigner_jar=apksigner_jar,
        clang_inet_ns=clang_inet_ns,
        clangpp_viewer=clangpp_viewer,
    )


def _run(cmd: list[str | Path], cwd: Path | None = None, log_lines: list[str] | None = None) -> None:
    str_cmd = [str(c) for c in cmd]
    proc = subprocess.run(
        str_cmd,
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding='utf-8',
        errors='replace',
    )
    if proc.stdout:
        if log_lines is not None:
            log_lines.append(proc.stdout)
        else:
            sys.stdout.write(proc.stdout)
            sys.stdout.flush()
    if proc.returncode != 0:
        if log_lines is not None and proc.stdout:
            sys.stderr.write(proc.stdout)
        raise RuntimeError(f'Command failed ({proc.returncode}): {" ".join(str_cmd)}')


def _run_python_tool(script: Path, extra_args: list[str] | None = None) -> None:
    _run([sys.executable, script, *(extra_args or [])], cwd=ROOT)


def _new_work_dir(build_dir: Path, prefix: str = 'work_') -> Path:
    stamp = datetime.now().strftime('%Y%m%d_%H%M%S_%f')[:-3]
    work = build_dir / f'{prefix}{stamp}'
    work.mkdir(parents=True, exist_ok=True)
    return work


def _set_exec_if_posix(path: Path) -> None:
    if platform.system() != 'Windows':
        mode = path.stat().st_mode
        path.chmod(mode | 0o755)


def _copy_with_parent(src: Path, dst: Path, executable: bool = False) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    if executable:
        _set_exec_if_posix(dst)


def _collect_sorted(root: Path, suffix: str) -> list[Path]:
    return sorted(p for p in root.rglob(f'*{suffix}') if p.is_file())


def _write_json_utf8(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + '\n', encoding='utf-8', newline='\n')


def _build_dex_jar(
    tc: Toolchain,
    module_dir: Path,
    java_sources: list[Path],
    output_jar: Path,
    min_api: int,
    work_parent: Path | None = None,
    work_prefix: str = 'work_',
    include_source_hashes: bool = True,
    log_path: Path | None = None,
) -> Path:
    build_dir = work_parent or (module_dir / 'build')
    task_build = _new_work_dir(build_dir, prefix=work_prefix)
    classes_dir = task_build / 'classes'
    dex_dir = task_build / 'dex'
    classes_dir.mkdir(parents=True, exist_ok=True)
    dex_dir.mkdir(parents=True, exist_ok=True)

    log_lines: list[str] | None = [] if log_path else None
    _run(
        [tc.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', tc.android_jar, '-d', classes_dir, *java_sources],
        log_lines=log_lines,
    )
    class_files = _collect_sorted(classes_dir, '.class')
    _run(
        [tc.java, '-cp', tc.d8_jar, 'com.android.tools.r8.D8', '--min-api', str(min_api), '--lib', tc.android_jar, '--output', dex_dir, *class_files],
        log_lines=log_lines,
    )
    output_jar.parent.mkdir(parents=True, exist_ok=True)
    _run([tc.jar, 'cf', output_jar, '-C', dex_dir, 'classes.dex'], log_lines=log_lines)

    if work_parent is None:
        info: dict = {
            'builtAt': datetime.now().astimezone().isoformat(),
            'jar': {'bytes': output_jar.stat().st_size, 'sha256': _sha256(output_jar)},
        }
        if include_source_hashes:
            info['sources'] = [
                {'file': p.relative_to(module_dir).as_posix(), 'sha256': _sha256(p)}
                for p in java_sources
            ]
        _write_json_utf8(build_dir / 'build-info.json', info)
        if log_lines is not None:
            log_lines.append(json.dumps(info, indent=2, ensure_ascii=False) + '\n')

    if log_path is not None and log_lines is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(''.join(log_lines), encoding='utf-8', newline='\n')
    return output_jar


def _build_apk_module(
    tc: Toolchain,
    module_dir: Path,
    min_api: int,
    keystore_name: str,
    key_alias: str,
    key_dname: str,
    apk_name: str,
    native_cpp: Path | None = None,
    native_lib_name: str | None = None,
    include_assets: bool = False,
    verify_verbose: bool = False,
    write_build_info: bool = False,
    log_path: Path | None = None,
) -> Path:
    build_dir = module_dir / 'build'
    task_build = _new_work_dir(build_dir)
    classes_dir = task_build / 'classes'
    dex_dir = task_build / 'dex'
    gen_dir = task_build / 'generated'
    classes_dir.mkdir(parents=True, exist_ok=True)
    dex_dir.mkdir(parents=True, exist_ok=True)
    gen_dir.mkdir(parents=True, exist_ok=True)

    log_lines: list[str] | None = [] if log_path else None
    native_lib: Path | None = None
    if native_cpp is not None and native_lib_name is not None:
        abi_dir = task_build / 'lib' / 'arm64-v8a'
        abi_dir.mkdir(parents=True, exist_ok=True)
        native_lib = abi_dir / native_lib_name
        _run(
            [
                tc.clangpp_viewer,
                '-std=c++17', '-O2', '-fPIC', '-shared', '-static-libstdc++',
                '-Wall', '-Wextra', f'-Wl,-soname,{native_lib_name}',
                native_cpp, '-o', native_lib, '-landroid', '-llog',
            ],
            log_lines=log_lines,
        )

    rel_build = task_build.relative_to(module_dir).as_posix()
    _run([tc.aapt2, 'compile', '--dir', 'res', '-o', f'{rel_build}/resources.zip'], cwd=module_dir, log_lines=log_lines)
    _run(
        [
            tc.aapt2, 'link', '-I', tc.android_jar,
            '--manifest', 'AndroidManifest.xml',
            '--java', f'{rel_build}/generated',
            '-o', f'{rel_build}/base.apk',
            f'{rel_build}/resources.zip',
        ],
        cwd=module_dir,
        log_lines=log_lines,
    )

    java_sources = _collect_sorted(module_dir / 'src', '.java') + _collect_sorted(gen_dir, '.java')
    _run(
        [tc.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', tc.android_jar, '-d', classes_dir, *java_sources],
        log_lines=log_lines,
    )
    class_files = _collect_sorted(classes_dir, '.class')
    _run(
        [tc.java, '-cp', tc.d8_jar, 'com.android.tools.r8.D8', '--min-api', str(min_api), '--lib', tc.android_jar, '--output', dex_dir, *class_files],
        log_lines=log_lines,
    )

    unaligned = task_build / 'unaligned.apk'
    shutil.copy2(task_build / 'base.apk', unaligned)
    _run([tc.aapt, 'add', unaligned, 'classes.dex'], cwd=dex_dir, log_lines=log_lines)

    if include_assets and (module_dir / 'assets').is_dir():
        asset_files = sorted(
            p.relative_to(module_dir).as_posix()
            for p in (module_dir / 'assets').rglob('*')
            if p.is_file()
        )
        if asset_files:
            _run([tc.aapt, 'add', unaligned, *asset_files], cwd=module_dir, log_lines=log_lines)

    if native_lib is not None and native_lib_name is not None:
        _run([tc.aapt, 'add', 'unaligned.apk', f'lib/arm64-v8a/{native_lib_name}'], cwd=task_build, log_lines=log_lines)

    aligned = task_build / 'aligned.apk'
    _run([tc.zipalign, '-f', '-p', '4', unaligned, aligned], log_lines=log_lines)

    keystore = build_dir / keystore_name
    if not keystore.is_file():
        _run(
            [
                tc.keytool, '-genkeypair', '-keystore', keystore,
                '-alias', key_alias, '-keyalg', 'RSA', '-keysize', '2048',
                '-validity', '10000', '-storepass', 'android', '-keypass', 'android',
                '-dname', key_dname,
            ],
            log_lines=log_lines,
        )

    out_apk = build_dir / apk_name
    _run(
        [
            tc.java, '-jar', tc.apksigner_jar, 'sign',
            '--ks', keystore, '--ks-key-alias', key_alias,
            '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
            '--out', out_apk, aligned,
        ],
        log_lines=log_lines,
    )
    verify_cmd: list[str | Path] = [tc.java, '-jar', tc.apksigner_jar, 'verify']
    if verify_verbose:
        verify_cmd.append('--verbose')
    verify_cmd.append(out_apk)
    _run(verify_cmd, log_lines=log_lines)

    if write_build_info:
        sources_list = []
        for sub in ('src', 'res', 'native'):
            sub_dir = module_dir / sub
            if sub_dir.is_dir():
                sources_list.extend(sorted(p for p in sub_dir.rglob('*') if p.is_file()))
        for top_file in ('AndroidManifest.xml', 'build.ps1'):
            cand = module_dir / top_file
            if cand.is_file():
                sources_list.append(cand)
        info: dict = {
            'builtAt': datetime.now().astimezone().isoformat(),
            'buildTools': tc.build_tools_version,
            'platform': tc.platform_name,
            'jdk': str(tc.jdk_dir),
            'ndk': tc.ndk_version,
            'apk': {'file': apk_name, 'bytes': out_apk.stat().st_size, 'sha256': _sha256(out_apk)},
            'sources': [{'file': p.relative_to(module_dir).as_posix(), 'sha256': _sha256(p)} for p in sources_list],
        }
        if native_lib is not None:
            info['native'] = {
                'abi': 'arm64-v8a',
                'bytes': native_lib.stat().st_size,
                'sha256': _sha256(native_lib),
            }
        _write_json_utf8(build_dir / 'build-info.json', info)

    if log_path is not None and log_lines is not None:
        log_lines.append(f'SHA256({out_apk.name}) = {_sha256(out_apk)}\n')
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(''.join(log_lines), encoding='utf-8', newline='\n')
    return out_apk


def _manifest_version_name(manifest_path: Path) -> str:
    root = ET.fromstring(manifest_path.read_text(encoding='utf-8'))
    ver = root.attrib.get(f'{ANDROID_NS}versionName')
    if not ver:
        raise ValueError(f'android:versionName not found in {manifest_path}')
    return ver


def build_all(args: argparse.Namespace) -> Path:
    tc = resolve_toolchain(args)
    out_dir = ROOT / 'out'
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f'[IrisBox build] OS={tc.system} SDK={tc.sdk_dir} JDK={tc.jdk_dir} NDK={tc.ndk_bin}')
    _run_python_tool(ROOT / 'tools' / 'generate_i18n.py')

    _copy_with_parent(
        ROOT / 'engine' / 'runtime' / 'root' / 'magisk_30_7' / 'busybox',
        ROOT / 'app' / 'viewer' / 'assets' / 'host-tools' / 'busybox',
        executable=True,
    )

    if not args.app_only:
        browser_apk = _build_apk_module(
            tc,
            ROOT / 'app' / 'guest-browser',
            min_api=29,
            keystore_name='browser-test.keystore',
            key_alias='browser-test',
            key_dname='CN=IrisBox Guest Browser,O=IrisBox,C=CN',
            apk_name='IrisBoxBrowser.apk',
            log_path=out_dir / 'guest-browser-build.log',
        )
        _copy_with_parent(browser_apk, ROOT / 'engine' / 'runtime' / 'browser' / 'IrisBoxBrowser.apk')

        if args.rebuild_producer:
            if not args.producer_host or not args.aosp_dir:
                raise ValueError('--rebuild-producer requires --producer-host and --aosp-dir (or IRISBOX_BUILD_HOST / IRISBOX_AOSP_DIR)')
            producer_args = ['--host', args.producer_host, '--aosp', args.aosp_dir]
            if args.producer_key:
                producer_args.extend(['--key', args.producer_key])
            _run_python_tool(ROOT / 'tools' / 'build_producer.py', producer_args)

        audio_jar = _build_dex_jar(
            tc,
            ROOT,
            [ROOT / 'engine' / 'audio' / 'AudioBridge.java'],
            out_dir / 'IrisBox-Audio.jar',
            min_api=29,
            work_parent=out_dir,
            work_prefix='audio-',
            include_source_hashes=False,
            log_path=out_dir / 'audio-build.log',
        )
        _copy_with_parent(audio_jar, ROOT / 'engine' / 'runtime' / 'audio' / 'audio.jar')

        exchange_dir = ROOT / 'app' / 'file-exchange'
        proto_java = ROOT / 'app' / 'broker' / 'src' / 'com' / 'iris' / 'buffer' / 'viewer' / 'broker' / 'ExchangeProtocol.java'
        exchange_jar = _build_dex_jar(
            tc,
            exchange_dir,
            [proto_java, *_collect_sorted(exchange_dir / 'src', '.java')],
            exchange_dir / 'build' / 'guest.jar',
            min_api=28,
            include_source_hashes=False,
            log_path=out_dir / 'file-exchange-build.log',
        )
        _copy_with_parent(exchange_jar, ROOT / 'engine' / 'runtime' / 'file_exchange' / 'guest.jar')

        inet_out = ROOT / 'engine' / 'runtime' / 'iris_inet_policy'
        _run([
            tc.clang_inet_ns, '-static', '-O2', '-Wall', '-Wextra', '-Werror',
            '-I', ROOT / 'engine' / 'native' / 'inet',
            ROOT / 'engine' / 'native' / 'inet' / 'policy.c', '-o', inet_out,
        ])
        _set_exec_if_posix(inet_out)

        ns_out = ROOT / 'engine' / 'runtime' / 'irisbox_ns'
        _run([
            tc.clang_inet_ns, '-static', '-O2', '-Wall', '-Wextra', '-Werror',
            ROOT / 'engine' / 'native' / 'iris_runtime_ns.c', '-o', ns_out,
        ])
        _set_exec_if_posix(ns_out)

        userns_out = ROOT / 'engine' / 'runtime' / 'irisbox_userns'
        _run([
            tc.clang_inet_ns, '-std=c11', '-O2', '-static', '-Wall', '-Wextra', '-Werror',
            ROOT / 'engine' / 'native' / 'userns' / 'irisbox_userns.c', '-o', userns_out,
        ])
        _set_exec_if_posix(userns_out)

        broker_dir = ROOT / 'app' / 'broker'
        broker_jar = _build_dex_jar(
            tc,
            broker_dir,
            _collect_sorted(broker_dir / 'src', '.java'),
            broker_dir / 'build' / 'IrisBufferBroker.jar',
            min_api=28,
            log_path=out_dir / 'broker-build.log',
        )
        gateway_dir = ROOT / 'app' / 'gateway'
        gateway_jar = _build_dex_jar(
            tc,
            gateway_dir,
            _collect_sorted(gateway_dir / 'src', '.java'),
            gateway_dir / 'build' / 'IrisBufferViewerGateway.jar',
            min_api=28,
            log_path=out_dir / 'gateway-build.log',
        )
        _copy_with_parent(broker_jar, ROOT / 'engine' / 'runtime' / 'display' / 'buffer_viewer' / 'broker.jar')
        _copy_with_parent(gateway_jar, ROOT / 'engine' / 'runtime' / 'display' / 'buffer_viewer' / 'gateway.jar')

        _run_python_tool(ROOT / 'tools' / 'board_profiles.py', ['sync'])
        _run_python_tool(ROOT / 'tools' / 'package_engine.py')

    viewer_dir = ROOT / 'app' / 'viewer'
    viewer_apk = _build_apk_module(
        tc,
        viewer_dir,
        min_api=29,
        keystore_name='viewer-test.keystore',
        key_alias='viewer-test',
        key_dname='CN=Iris Local Test,O=Iris,C=CN',
        apk_name='IrisBufferViewer.apk',
        native_cpp=viewer_dir / 'native' / 'buffer_viewer.cpp',
        native_lib_name='libirisbufferviewer.so',
        include_assets=True,
        verify_verbose=True,
        write_build_info=True,
    )
    version_name = _manifest_version_name(viewer_dir / 'AndroidManifest.xml')
    final_apk = out_dir / f'IrisBox-{version_name}.apk'
    shutil.copy2(viewer_apk, final_apk)
    print(f'Output APK: {final_apk} ({final_apk.stat().st_size} bytes)')
    print(f'SHA256:     {_sha256(final_apk)}')
    return final_apk


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description='Build IrisBox engine payloads and APK on Linux, macOS, or Windows.')
    p.add_argument('--sdk-dir', default=None, help='Path to Android SDK (defaults to ANDROID_SDK_ROOT / ANDROID_HOME / OS standard path)')
    p.add_argument('--jdk-dir', default=None, help='Path to JDK 17 (defaults to JAVA_HOME / OS standard JDK 17)')
    p.add_argument('--ndk-version', default=DEFAULT_NDK_VERSION, help=f'Android NDK version (default: {DEFAULT_NDK_VERSION})')
    p.add_argument('--build-tools-version', default=DEFAULT_BUILD_TOOLS, help=f'Android build-tools version (default: {DEFAULT_BUILD_TOOLS})')
    p.add_argument('--platform', default=DEFAULT_PLATFORM, help=f'Android compile SDK platform (default: {DEFAULT_PLATFORM})')
    p.add_argument('--app-only', action='store_true', help='Only rebuild app/viewer using existing engine.zip and profiles')
    p.add_argument('--rebuild-producer', action='store_true', help='Also rebuild iris_buffer_producer on a remote AOSP build host')
    p.add_argument('--producer-host', default=os.environ.get('IRISBOX_BUILD_HOST'), help='SSH user@host for remote producer build')
    p.add_argument('--producer-key', default=os.environ.get('IRISBOX_BUILD_KEY'), help='SSH private key path for remote producer build')
    p.add_argument('--aosp-dir', default=os.environ.get('IRISBOX_AOSP_DIR'), help='Remote AOSP root path for producer build')
    return p.parse_args(argv)


if __name__ == '__main__':
    build_all(parse_args())
