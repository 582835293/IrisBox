"""Rebuild vold Utils only, with read-only matched AOSP inputs and a private output.

Run: python3 build_direct.py /path/to/aosp-rpi4 /tmp/irisbox-storage-UNIQUE
No Ninja build command or AOSP source/output mutation is performed.
"""
import difflib,hashlib,json,re,shlex,shutil,subprocess,sys
from pathlib import Path

aosp=Path(sys.argv[1]).resolve();stage=Path(sys.argv[2]).resolve()
assert stage.parent==Path('/tmp') and stage.name.startswith('irisbox-storage-')
out=stage/'build';out.mkdir(exist_ok=True)
source=aosp/'system/vold/Utils.cpp';original=source.read_text()
assert hashlib.sha256(original.encode()).hexdigest()=='c97c2487d1bd146fb630f804a34b1d09a07651a539a83e531d880609d02a6c1d', 'Require the recorded matched AOSP source'
helper='''// IrisBox mapped guests cannot change initial-namespace project quota state.
// Keep normal fs_prepare_dir/chown/mode/default ACL checks. Only quota tagging
// is unavailable on this shared-kernel profile; never fall back to Host UID0.
static bool IrisBoxMappedGuest() {
    std::string map;
    unsigned inside, outside, span; char extra;
    return getuid() == 0 && android::base::ReadFileToString("/proc/self/uid_map", &map) &&
        sscanf(map.c_str(), "%u %u %u %c", &inside, &outside, &span, &extra) == 3 &&
        inside == 0 && outside >= 2000000000U && outside < 2012800000U &&
        outside % 100000U == 0 && span == 100000U;
}

'''
anchor='int SetQuotaInherit(const std::string& path) {'
assert original.count(anchor)==1 and original.count('int SetQuotaProjectId(const std::string& path, long projectId) {')==1
patched=original.replace(anchor,helper+anchor+'\n    if (IrisBoxMappedGuest()) return 0;')
patched=patched.replace('int SetQuotaProjectId(const std::string& path, long projectId) {','int SetQuotaProjectId(const std::string& path, long projectId) {\n    if (IrisBoxMappedGuest()) return 0;')
(stage/'Utils.cpp').write_text(patched)
(out/'vold-userns.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),patched.splitlines(True),fromfile='a/system/vold/Utils.cpp',tofile='b/system/vold/Utils.cpp')))

def logical(stream):
    pending=''
    for raw in stream:
        line=pending+raw.rstrip('\n').lstrip() if pending else raw.rstrip('\n')
        if line.endswith('$'):pending=line[:-1];continue
        pending='';yield line

suffixes={'compile':'libvold/android_arm64_armv8-a_cortex-a72_static/obj/system/vold/Utils.o:',
          'link':'vold/android_arm64_armv8-a_cortex-a72/unstripped/vold:'}
globals={};blocks={};active=None
with (aosp/'out/soong/build.ninja').open() as stream:
    for line in logical(stream):
        if line.startswith(('g.','m.libvold_','m.vold_')) and ' = ' in line:
            key,_,value=line.partition(' = ');globals[key]=value
        matched=next((key for key,value in suffixes.items() if value in line and line.startswith('build ')),None)
        if matched:
            active=matched;blocks[active]={'header':line,'values':{}};continue
        if active:
            if line.startswith(' ') and ' = ' in line:
                key,_,value=line.strip().partition(' = ');blocks[active]['values'][key]=value
            elif not line.startswith(' '):
                active=None
                if len(blocks)==2:break
assert len(blocks)==2, 'Matched vold compile/link rules required'
(out/'ninja-inputs.json').write_text(json.dumps({'globals':globals,'blocks':blocks},indent=2))

def expand(value,variables):
    for _ in range(50):
        if not re.search(r'\$\{([^}]+)\}',value):return value.replace('$ ', ' ').replace('$:',':').replace('$$','$')
        value=re.sub(r'\$\{([^}]+)\}',lambda m:variables[m[1]],value)
    raise RuntimeError('Ninja expansion did not terminate')

cv={**globals,**blocks['compile']['values']};lv={**globals,**blocks['link']['values']}
cc=expand(cv['ccCmd'],cv);obj=out/'Utils.o'
compile=[cc,*shlex.split(expand(cv['cFlags'],cv)),'-c',str(stage/'Utils.cpp'),'-o',str(obj)]
archive_rel='out/soong/.intermediates/system/vold/libvold/android_arm64_armv8-a_cortex-a72_static/libvold.a'
archive=out/'libvold.a';shutil.copyfile(aosp/archive_rel,archive)
llvm=Path(cc).parent
members=subprocess.check_output([str(aosp/llvm/'llvm-ar'),'t',str(archive)],text=True).splitlines()
assert members.count('Utils.o')==1
libflags=shlex.split(expand(lv['libFlags'],lv));assert libflags.count(archive_rel)==1
libflags=[str(archive) if f==archive_rel else f for f in libflags]
header=blocks['link']['header'].split(': ',1)[1].split(' |',1)[0].split()[1:]
crtbegin=shlex.split(expand(lv['crtBegin'],lv));crtend=shlex.split(expand(lv['crtEnd'],lv))
objects=[p for p in header if p.endswith('.o') and p not in crtbegin+crtend]
assert objects and all((aosp/p).is_file() for p in objects)
binary=out/'vold.unstripped'
link=[expand(lv['ldCmd'],lv),*shlex.split(expand(lv['ldFlags'],lv)),*crtbegin,*objects,*libflags,*crtend,'-o',str(binary)]
commands=[compile,[str(aosp/llvm/'llvm-ar'),'rD',str(archive),str(obj)],link,
          [str(aosp/llvm/'llvm-strip'),'--strip-unneeded','-o',str(out/'vold'),str(binary)]]
(out/'commands.json').write_text(json.dumps(commands,indent=2))
for command in commands:subprocess.run(command,cwd=aosp,check=True)
assert source.read_text()==original,'AOSP source changed unexpectedly'
data=(out/'vold').read_bytes();assert data[:4]==b'\x7fELF' and int.from_bytes(data[18:20],'little')==183
manifest={'source':'system/vold/Utils.cpp','baseline':'android-13.0.0_r75','sourceSha256':hashlib.sha256(original.encode()).hexdigest(),
          'patchedSourceSha256':hashlib.sha256(patched.encode()).hexdigest(),'binarySha256':hashlib.sha256(data).hexdigest(),
          'binaryBytes':len(data),'compiledOnlyUtils':True,'aospInputsReadOnly':True,'guestProjectQuotaEnforced':False}
(out/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest))
