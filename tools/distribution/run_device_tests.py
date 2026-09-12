#!/usr/bin/env python3
"""Install exact CI-built release demo/test APKs on an attached ARM64 device; no skipped tests."""
import argparse
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--test-apk', type=Path, required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, default=Path('device-test-result.json'))
a = parser.parse_args()

def adb(*args):
    return subprocess.check_output(['adb', '-s', a.serial, *args], text=True, stderr=subprocess.STDOUT)

abi = adb('shell', 'getprop', 'ro.product.cpu.abilist').strip()
if 'arm64-v8a' not in abi: raise SystemExit(f'ARM64 device required, got {abi}')
for apk in [a.apk, a.test_apk]:
    if not apk.is_file(): raise SystemExit(f'Missing {apk}')
    print(adb('install', '-r', '-t', str(apk)))
# Device must be unlocked and able to reach Mozilla for the first pinned model download.
result = adb('shell', 'am', 'instrument', '-w', '-r',
             'io.github.yinvoker.foxlet.demo.test/androidx.test.runner.AndroidJUnitRunner')
print(result)
a.output.parent.mkdir(parents=True, exist_ok=True)
import hashlib
passed = 'OK (1 test)' in result and not any(x in result for x in ['FAILURES', 'INSTRUMENTATION_FAILED', 'shortMsg='])
a.output.write_text(json.dumps({'passed': passed, 'timestamp': time.time(),
    'apk_sha256': hashlib.sha256(a.apk.read_bytes()).hexdigest(), 'abi': abi,
    'android': adb('shell', 'getprop', 'ro.build.version.release').strip(),
    'instrumentation': result}, indent=2) + '\n')
if not passed: raise SystemExit('Device gate failed or did not execute its mandatory test')
