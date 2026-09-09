#!/usr/bin/env python3
"""Paired native benchmark with source/input fingerprints and retained failures.

Prepare model/config files using the device layouts below. Build binaries and
manifests with build.py, then choose labels with --baseline and --candidate.
This measures the native harness, not JNI, the AAR API or app PSS.
"""
import argparse
import hashlib, json, os, subprocess, time
from pathlib import Path
import re
import shlex
import shutil
import tempfile
import uuid
import sys
from suite import DEFAULT_SUITE, load_suite

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bench_device import FOREGROUND_CHECKS, capture_foreground, foreground_ready

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('device', choices=['mi10', 'mi14'])
parser.add_argument('output', type=Path)
parser.add_argument('build_root', type=Path)
parser.add_argument('--baseline', default='v0.2.0')
parser.add_argument('--candidate', default='main')
parser.add_argument('--adb', default=shutil.which('adb') or 'adb')
parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
parser.add_argument('--suite', default=DEFAULT_SUITE)
parser.add_argument('--scenarios', nargs='+', help='Exploratory subset only; not a full version baseline')
parser.add_argument('--skip-frequency-gate', action='store_true',
                    help='Exploratory run: record frequencies but do not wait for the frequency gate; applies to both versions')
args = parser.parse_args()
suite, suite_scenarios, suite_digest = load_suite(args.suite)
dev, out, SP = args.device, args.output, args.build_root
versions = [args.baseline, args.candidate]
if len(set(versions)) != 2 or any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', v) for v in versions):
    parser.error('choose two different simple version labels')
if out.exists():
    parser.error('output already exists; preserve it and choose a new run filename')
out.parent.mkdir(parents=True, exist_ok=True)
ADB = [args.adb] + (['-s', args.serial] if args.serial else [])
repo = Path(__file__).resolve().parents[2]
layouts = {
  'mi10': dict(model='Mi 10', base='/data/local/tmp/bg', cpus=[4, 7], mask='f0', soc='Snapdragon 865', i8mm=False,
               cfg1024='config-mbw1024.yml', jaen1024='config-jaen.yml', cfg512p='config-mbw512on.yml', jaen512='config-jaen512.yml'),
  'mi14': dict(model='23127PN0CC', base='/data/local/tmp/bergamot', cpus=[2, 7], mask='7c', soc='Snapdragon 8 Gen 3', i8mm=True, gate_khz=2630400, cooldown=8,
               cfg1024='config-mbw1024.yml', jaen1024='config-jaen.yml', cfg512p='config-mbw512on.yml', jaen512='config-jaen512.yml'),
}
L = layouts[dev]; remote = L['base'] + '/vb-' + uuid.uuid4().hex[:12]
GATE, COOLDOWN = L.get('gate_khz'), L.get('cooldown', 4)  # with several devices attached, select one via ANDROID_SERIAL
if args.skip_frequency_gate:
    GATE = None
def adb(*args): return subprocess.check_output([*ADB, *args], text=True, timeout=30).strip()
def shell(c): return adb('shell', c)
def conditions():
    caps = {str(i): shell(f'cat /sys/devices/system/cpu/cpu{i}/cpufreq/scaling_max_freq') for i in L['cpus']}
    batt = shell('dumpsys battery'); temp = int(next(x.split(':')[1] for x in batt.splitlines() if x.strip().startswith('temperature:'))) / 10
    board = shell('cat /sys/class/thermal/thermal_message/board_sensor_temp 2>/dev/null || echo 0')
    return {'max_freq_khz': caps, 'battery_temp_c': temp, 'board_temp': board,
            'foreground': capture_foreground(shell)}
def gate():
    """Check foreground readiness, then wait up to 180 s for the configured cap gate.

    Recovery depends on device policy; idling does not guarantee it. The
    explicit exploratory bypass skips this wait, never the foreground check.
    """
    if not foreground_ready(capture_foreground(shell)): return -2
    if not GATE: return 0
    t0 = time.time()
    while time.time() - t0 < 180:
        if not foreground_ready(capture_foreground(shell)): return -2
        caps = [int(shell(f'cat /sys/devices/system/cpu/cpu{i}/cpufreq/scaling_max_freq')) for i in L['cpus']]
        if all(c >= GATE for c in caps): return round(time.time() - t0)
        time.sleep(5)
    return -1
binaries, manifests = {}, {}
for v in versions:
    b = SP / f'smoke-{v}'; binaries[v] = hashlib.sha256(b.read_bytes()).hexdigest()
    manifests[v] = json.loads((SP / v / 'build-manifest.json').read_text())
    if manifests[v]['binary_sha256'] != binaries[v]:
        parser.error(f'{v}: binary does not match build manifest')
if manifests[versions[0]]['build_settings'] != manifests[versions[1]]['build_settings']:
    parser.error('toolchain/build settings differ')
if manifests[versions[0]]['harness_sha256'] != manifests[versions[1]]['harness_sha256']:
    parser.error('measurement harnesses differ')
selected = args.scenarios if args.scenarios is not None else list(suite_scenarios)
if set(selected) - set(suite_scenarios):
    parser.error('unknown scenario; choices: ' + ', '.join(suite_scenarios))
scenarios = [(name, spec['workers'], spec['corpus'], spec['configs'])
             for name, spec in suite_scenarios.items() if name in selected]
# Fail before copying files if the selected device is absent or ambiguous.
serial = adb('get-serialno')
device_model = shell('getprop ro.product.model')
if device_model != L['model']:
    parser.error(f'{dev} profile expects {L["model"]}, got {device_model}; add a verified device profile first')
shell('mkdir -p ' + remote)
for v in versions:
    b = SP / f'smoke-{v}'
    adb('push', str(b), f'{remote}/{v}')
    shell(f'chmod 755 {remote}/{v}')
# configs: the v0.2.0 comparison config (mbw1024, no prefix table) and the current AAR defaults (mbw512 + en prefix table)
configs = {}
for name, src, fix in [('enzh1024', L['cfg1024'], ('mini-batch-words: 512', 'mini-batch-words: 1024')), ('jaen1024', L['jaen1024'], ('mini-batch-words: 512', 'mini-batch-words: 1024')),
                       ('enzh512p', L['cfg512p'], None), ('jaen512', L['jaen512'], None)]:
    c = shell(f"cat {L['base']}/{src}")
    if fix: c = c.replace(*fix)
    c += '\n'; configs[name] = c
    with tempfile.TemporaryDirectory(prefix='bergamot-bench-config-') as temp:
        path = Path(temp) / f'{name}.yml'
        path.write_text(c)
        adb('push', str(path), f'{remote}/{name}.yml')
assert 'ssplit-prefix-file' in configs['enzh512p'] and 'mini-batch-words: 512' in configs['enzh512p'] and 'mini-batch-words: 1024' in configs['enzh1024']
corpus_hashes, model_hashes = {}, {}
for lang, src in [('eng', 'eng200.txt'), ('jpn', 'jpn200.txt')]:
    shell(f"cp {L['base']}/{src} {remote}/{lang}.txt")
    assert shell(f'cat {remote}/{lang}.txt').splitlines() == (repo / f'sample/src/main/assets/bench/{lang}.txt').read_text().splitlines()
    corpus_hashes[lang] = shell(f'sha256sum {remote}/{lang}.txt').split()[0]
for config in configs.values():
    # These controlled YAML templates contain plain absolute paths. Reject
    # unsupported quoting instead of silently omitting an input fingerprint.
    for line in config.splitlines():
        match = re.match(r'\s*(?:-\s+|ssplit-prefix-file:\s*)(/[^\n]+)$', line)
        if match:
            path = match.group(1).strip()
            model_hashes[path] = shell('sha256sum ' + shlex.quote(path)).split()[0]
if len(model_hashes) < 8:
    parser.error('expected enzh/jaen model, vocab, shortlist and prefix-file fingerprints')
report = {'measured_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'device': {'model': device_model, 'android': shell('getprop ro.build.version.release'), 'soc': L['soc'], 'i8mm': L['i8mm'],
  'id_sha256': hashlib.sha256(serial.encode()).hexdigest(), 'fingerprint': shell('getprop ro.build.fingerprint')},
  'versions': {v: manifests[v]['commit'] for v in versions},
  'build_manifests': manifests, 'build_settings': manifests[versions[0]]['build_settings'],
  'suite_id': suite['id'], 'suite_sha256': suite_digest,
  'report_kind': 'full' if args.scenarios is None and not args.skip_frequency_gate else 'exploratory',
  'corpus_sha256': corpus_hashes, 'model_files_sha256': model_hashes,
  'binary_sha256': binaries, 'harness_sha256': manifests[versions[0]]['harness_sha256'], 'config_yaml': configs,
  'collector_files_sha256': {name: hashlib.sha256((repo / name).read_bytes()).hexdigest()
                            for name in ('tools/version-bench/run-pair.py', 'tools/bench_device.py')},
  'scenarios': {name: suite_scenarios[name] for name, *_ in scenarios},
  'protocol': {**suite['protocol'], 'affinity': L['mask'], 'cooldown_seconds': COOLDOWN, 'gate_khz': GATE,
               'foreground_checks': FOREGROUND_CHECKS,
               'cold_ms': 'service + model creation + first translation', 'peak_rss_mib': 'VmHWM after first translation', 'warm_ms': 'passes 1 and 2'}, 'runs': []}
if args.skip_frequency_gate:
    report['protocol']['frequency_gate_mode'] = 'skipped-explicitly'
    report['protocol']['reference_gate_khz'] = L.get('gate_khz')
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
for r in range(suite['protocol']['rounds']):
    order = [versions['AB'.index(letter)] for letter in suite['protocol']['version_order'][r]]
    for name, workers, corpus, cfgs in scenarios:
        for v in order:
            waited = gate(); before = conditions(); before['gate_wait_s'] = waited
            if waited < 0 or not foreground_ready(before['foreground']):
                error = ('device not awake/unlocked with benchmark app foreground'
                         if waited == -2 or not foreground_ready(before['foreground'])
                         else 'frequency gate timeout; not measured')
                report['runs'].append({'round': r + 1, 'scenario': name, 'version': v,
                    'before': before, 'returncode': 2, 'samples': [], 'error': error})
                out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                raise SystemExit(error + '. Partial report retained; start a new session when ready.')
            cmd = f"taskset {L['mask']} {remote}/{v} {workers} " + ' '.join(f'{remote}/{c}.yml' for c in cfgs) + f' < {remote}/{corpus}.txt'
            try:
                res = subprocess.run([*ADB, 'shell', cmd], capture_output=True, text=True, timeout=300)
            except subprocess.TimeoutExpired as error:
                report['runs'].append({'round': r + 1, 'scenario': name, 'version': v,
                    'command': cmd, 'before': before, 'returncode': 124, 'samples': [], 'error': str(error)})
                out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                raise SystemExit('Process timeout; partial report retained. Check the device before rerunning.')
            try:
                samples = [json.loads(l) for l in res.stdout.splitlines() if l.startswith('{')]
            except json.JSONDecodeError:
                samples = []
            run = {'round': r + 1, 'scenario': name, 'version': v, 'command': cmd,
                   'before': before, 'returncode': res.returncode, 'samples': samples}
            if res.returncode or len(samples) != 3:
                run['error'] = 'failed or incomplete process: ' + (res.stdout + res.stderr)[-3000:]
            # Persist the process result before querying sensors: a disconnected
            # device must not erase the failing process's evidence.
            report['runs'].append(run)
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            try:
                run['after'] = conditions()
            except (subprocess.SubprocessError, ValueError, StopIteration) as error:
                run['error'] = run.get('error', '') + f'; post-run sensors unavailable: {error}'
                out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                raise SystemExit('Device/sensor read failed; process result retained.')
            if not foreground_ready(run['after']['foreground']):
                run['error'] = run.get('error', '') + '; foreground lost during process'
                out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
                raise SystemExit('Foreground lost; process retained but not accepted.')
            out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            s0 = samples[0] if samples else run.get('error')
            print(r + 1, name, v, res.returncode, (f"cold={s0['elapsed_ms']:.0f} peak={s0['peak_rss_mib']:.0f} hash={s0['output_hash']}" if samples else s0), flush=True)
            time.sleep(COOLDOWN)
print('Saved', out, flush=True)
