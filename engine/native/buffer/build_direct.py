"""Compile only buffer_producer.cpp with existing AOSP 13 screencap flags."""
from pathlib import Path
import hashlib
import json
import re
import shlex
import subprocess
import sys

aosp = Path(sys.argv[1]).resolve()
source = Path(sys.argv[2]).resolve()
output = source / "build"
output.mkdir(exist_ok=True)
object_suffix = "/obj/frameworks/base/cmds/screencap/screencap.o"
compile_block = []
variables = {}
cache = output / "compile_variables.json"
ninja_stat = (aosp / "out/soong/build.ninja").stat()
cache_key = [str(aosp), ninja_stat.st_size, ninja_stat.st_mtime_ns]
def logical_lines(stream):
    pending = ""
    for line in stream:
        line = line.rstrip("\n")
        if pending:
            line = pending + line.lstrip()
        if line.endswith("$"):
            pending = line[:-1]
            continue
        pending = ""
        yield line
cached = json.loads(cache.read_text()) if cache.exists() else {}
if cached.get("key") == cache_key:
    variables = cached["variables"]
else:
    with (aosp / "out/soong/build.ninja").open() as stream:
        lines = logical_lines(stream)
        for line in lines:
            if line.startswith(("g.", "m.screencap_")):
                key, separator, value = line.partition(" = ")
                if separator:
                    variables[key] = value
            if object_suffix + ":" in line:
                compile_block.append(line.rstrip())
                for line in lines:
                    if not line.startswith(" "):
                        break
                    compile_block.append(line.rstrip())
                break
    if not compile_block:
        raise SystemExit("Cached screencap compile block not found")
    (output / "screencap_compile_block.txt").write_text("\n".join(compile_block) + "\n")
    for line in compile_block[1:]:
        key, separator, value = line.strip().partition(" = ")
        if separator:
            variables[key] = value
    cache.write_text(json.dumps({"key": cache_key, "variables": variables}, indent=2))
def expanded(value):
    for _ in range(40):
        if not re.search(r"\$\{([^}]+)\}", value):
            return value.replace("$ ", " ").replace("$:", ":").replace("$$", "$")
        value = re.sub(r"\$\{([^}]+)\}", lambda m: variables[m[1]], value)
    raise RuntimeError("Ninja flag expansion did not terminate")
compiler = expanded(variables["ccCmd"])
flags = shlex.split(expanded(variables["cFlags"]))
obj = output / "buffer_producer.o"
binary = output / "iris_buffer_producer"
compile_command = [compiler, *flags, "-c", str(source / "buffer_producer.cpp"), "-o", str(obj)]
link_command = [
    compiler, "--target=aarch64-linux-android33", "--sysroot=out/soong/ndk/sysroot",
    "-fuse-ld=lld", "-fPIE", "-pie", "-nostdlib", "-Wl,--no-undefined",
    "-Wl,--dynamic-linker=/system/bin/linker64",
    "out/soong/.intermediates/bionic/libc/crtbegin_dynamic/android_arm64_armv8-a_cortex-a72_apex30/crtbegin_dynamic.o",
    str(obj),
    "prebuilts/clang/host/linux-x86/clang-r450784d/lib64/clang/14.0.6/lib/linux/libclang_rt.builtins-aarch64-android.a",
    "-Lout/target/product/rpi4/system/lib64", "-lc++", "-lgui", "-lui", "-lbinder",
    "-lnativewindow", "-lutils", "-ljnigraphics",
    "-Lout/soong/ndk/platforms/android-REL/arch-arm64/usr/lib", "-lc", "-ldl", "-lm",
    "out/soong/.intermediates/bionic/libc/crtend_android/android_arm64_armv8-a_cortex-a72_apex30/obj/bionic/libc/arch-common/bionic/crtend.o",
    "-o", str(binary),
]
(output / "commands.json").write_text(json.dumps([compile_command, link_command], indent=2) + "\n")
for command in (compile_command, link_command):
    print(shlex.join(command), flush=True)
    subprocess.run(command, cwd=aosp, check=True)
inspection = subprocess.check_output([str(aosp / "prebuilts/clang/host/linux-x86/clang-r450784d/bin/llvm-readelf"),
    "-h", "-l", "-d", str(binary)], text=True)
(output / "elf_inspection.txt").write_text(inspection)
print(inspection)
manifest = {"source_sha256": hashlib.sha256((source / "buffer_producer.cpp").read_bytes()).hexdigest(),
    "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(), "binary_bytes": binary.stat().st_size,
    "compiled_only": True, "device_tested": False}
(output / "build_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
