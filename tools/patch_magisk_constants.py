"""Reproduce the documented native adapter changes on an upstream Magisk binary.

Extract the appropriate architecture's original magisk executable from the
official v30.7 APK first. Input/output must differ; the signing key is unrelated.
"""
import argparse
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--arch',choices=['arm64','arm32'],required=True)
parser.add_argument('--input',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
assert args.input.resolve()!=args.output.resolve()
assert not args.output.exists(),'Never overwrite an existing binary'
meta=json.loads((ROOT/'engine/runtime/root/magisk_30_7/adaptation.json').read_text())
prefix='native32' if args.arch=='arm32' else 'native'
data=bytearray(args.input.read_bytes())
assert hashlib.sha256(data).hexdigest()==meta[prefix+'OriginalSha256']
for entry in meta[prefix+'Constants']:
    old=entry['old'].encode();new=entry['new'].encode();assert len(new)<=len(old)
    for offset in entry['offsets']:
        assert data[offset:offset+len(old)]==old
        data[offset:offset+len(old)]=new+b'\0'*(len(old)-len(new))
assert hashlib.sha256(data).hexdigest()==meta[prefix+'PatchedSha256']
args.output.write_bytes(data)
print('Verified native adapter reproduced:',args.arch,len(data))
