#!/usr/bin/env python3
"""Check actual Android release compile commands, not just CMake source text."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[2]
# AGP's release AAR uses RelWithDebInfo; this project's debug variant explicitly
# selects Release. Check both independently so a newer debug build cannot hide
# missing hardening in the shipped release configuration.
for config in ['RelWithDebInfo', 'Release']:
    files = list((root / 'bergamot/.cxx' / config).glob('**/compile_commands.json'))
    if not files:
        if config == 'RelWithDebInfo':
            raise SystemExit('No release AAR compile_commands.json; build the AAR first')
        continue
    path = max(files, key=lambda p: p.stat().st_mtime_ns)
    commands = json.loads(path.read_text())
    for source in ['common/binary.cpp', 'sentencepiece_processor.cc', 'jni/bergamot_jni.cpp']:
        matches = [x for x in commands if x['file'].endswith(source)]
        if not matches: raise SystemExit(f'{path}: no compile command for {source}')
        for item in matches:
            cmd = item.get('command', ' '.join(item.get('arguments', [])))
            for flag in ['-fstack-protector-strong', '-D_FORTIFY_SOURCE=2']:
                if flag not in cmd: raise SystemExit(f'{config} {source}: missing {flag}')
    print(f'PASS Android hardening: {path}')
