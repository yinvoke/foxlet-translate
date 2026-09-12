#!/usr/bin/env python3
"""Reject incomplete notices, missing prefix variants, and evaluation data in public binaries."""
import argparse
import io
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = 'io/github/yinvoker/foxlet/'
REQUIRED = ['licenses/NOTICE.txt', 'licenses/THIRD_PARTY_NOTICES.txt', 'licenses/SOURCE.txt', 'models.tsv']

def inspect(path, prefixes=None):
    with zipfile.ZipFile(path) as archive:
        contents = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
    if path.suffix == '.aar':
        for name in ['jni/arm64-v8a/libbergamot.so', 'jni/arm64-v8a/libc++_shared.so', 'proguard.txt']:
            if name not in contents: raise ValueError(f'{path}: missing {name}')
        with zipfile.ZipFile(io.BytesIO(contents['classes.jar'])) as jar:
            contents.update({name: jar.read(name) for name in jar.namelist() if not name.endswith('/')})
    for name in REQUIRED:
        if not contents.get(BASE + name): raise ValueError(f'{path}: missing {name}')
    actual = sum(name.startswith(BASE + 'nonbreaking_prefixes/nonbreaking_prefix.') for name in contents)
    if prefixes is not None and actual != prefixes:
        raise ValueError(f'{path}: expected {prefixes} prefix tables, got {actual}')
    source = contents[BASE + 'licenses/SOURCE.txt']
    if b'https://github.com/yinvoke/foxlet-translate' not in source: raise ValueError('No source offer')
    notice = contents[BASE + 'licenses/THIRD_PARTY_NOTICES.txt']
    for license_marker in [b'Mozilla Public License', b'Apache License', b'MIT License', b'LLVM Exceptions']:
        if license_marker not in notice: raise ValueError(f'{path}: missing license {license_marker}')
    snippets = []
    for lang in ['eng', 'jpn']:
        snippets.extend((ROOT / f'sample/src/main/assets/bench/{lang}.txt').read_text().splitlines()[:3])
    for name, data in contents.items():
        if '/bench/' in name or name.startswith('assets/bench/'):
            raise ValueError(f'{path}: benchmark file packaged: {name}')
        if any(line.encode() in data for line in snippets if line):
            raise ValueError(f'{path}: FLORES sample text found in {name}')
    print(f'PASS {path}: notices, source offer, {actual} prefix tables, no benchmark corpus')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--no-prefixes', type=Path)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    inspect(args.aar, 25)
    if args.no_prefixes: inspect(args.no_prefixes, 0)
    if args.apk: inspect(args.apk, 25)
