"""Convert this single BPF ELF section to C instructions + explicit map FD slots.

No external ELF library is needed. Reject unexpected relocations/layout rather
than interpreting other objects. Build output is kept outside source control.
"""
import struct
import sys
from pathlib import Path

data = Path(sys.argv[1]).read_bytes()
assert data[:6] == b'\x7fELF\x02\x01', 'ELF64 little endian required'
assert struct.unpack_from('<H', data, 18)[0] == 247, 'BPF object required'
off = struct.unpack_from('<Q', data, 40)[0]
size, count, strings_index = struct.unpack_from('<HHH', data, 58)
assert size == 64
sections = [struct.unpack_from('<IIQQQQIIQQ', data, off + i * size) for i in range(count)]
def body(s):
    return data[s[4]:s[4] + s[5]]
def cstr(buf, i):
    return buf[i:buf.index(0, i)].decode('ascii')
names = body(sections[strings_index])
indexes = {cstr(names, s[0]): i for i, s in enumerate(sections)}
index = indexes['cgroup/sock']
code = body(sections[index])
assert len(code) % 8 == 0
slots = {}
for s in sections:
    if s[1] != 9 or s[7] != index:  # SHT_REL, sh_info=target section
        continue
    symbols = sections[s[6]]
    symbol_names = body(sections[symbols[6]])
    for i in range(0, s[5], 16):
        address, info = struct.unpack_from('<QQ', body(s), i)
        assert info & 0xffffffff == 1, 'only R_BPF_64_64 map relocations supported'
        name_off = struct.unpack_from('<I', body(symbols), (info >> 32) * 24)[0]
        name = cstr(symbol_names, name_off)
        assert name in ('iris_domains', 'host_permissions') and name not in slots
        assert address % 8 == 0 and code[address] == 0x18
        slots[name] = address // 8
assert set(slots) == {'iris_domains', 'host_permissions'}
lines = ['/* Generated from policy.bpf.c; do not edit. */',
         'static const struct bpf_insn iris_instructions[] = {']
for i in range(0, len(code), 8):
    op, regs, jump, immediate = struct.unpack_from('<BBhi', code, i)
    lines.append(f'    {{.code={op}, .dst_reg={regs & 15}, .src_reg={regs >> 4}, .off={jump}, .imm={immediate}}},')
lines += ['};']
lines += [f'#define SLOT_{name.upper()} {slot}' for name, slot in sorted(slots.items())]
Path(sys.argv[2]).write_text('\n'.join(lines) + '\n', encoding='ascii')
