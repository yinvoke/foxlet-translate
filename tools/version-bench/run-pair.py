#!/usr/bin/env python3
"""v0.2.0 vs main paired engine benchmark on one device. Mirrors tools/version-bench/run.py, plus
AAR-default scenarios (blocking, mini-batch-words 512, prefix table on). Usage: run-pair.py <mi10|mi14> <output.json> <build-root with smoke-v0.2.0 and smoke-main>"""
import hashlib, json, subprocess, sys, time
from pathlib import Path
dev, out = sys.argv[1], Path(sys.argv[2])
ADB = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
SP = Path(sys.argv[3]) if len(sys.argv) > 3 else Path('.')
repo = Path('/Users/yin/Desktop/work/bergamot-android')
layouts = {
  'mi10': dict(base='/data/local/tmp/bg', cpus=[4, 7], mask='f0', soc='Snapdragon 865', i8mm=False,
               cfg1024='config-mbw1024.yml', jaen1024='config-jaen.yml', cfg512p='config-mbw512on.yml', jaen512='config-jaen512.yml'),
  'mi14': dict(base='/data/local/tmp/bergamot', cpus=[2, 7], mask='7c', soc='Snapdragon 8 Gen 3', i8mm=True,
               cfg1024='config-mbw1024.yml', jaen1024='config-jaen.yml', cfg512p='config-mbw512on.yml', jaen512='config-jaen512.yml'),
}
L = layouts[dev]; remote = L['base'] + '/vb'
versions = ['v0.2.0', 'main']
def adb(*args): return subprocess.check_output([ADB, *args], text=True).strip()
def shell(c): return adb('shell', c)
def conditions():
    caps = {str(i): shell(f'cat /sys/devices/system/cpu/cpu{i}/cpufreq/scaling_max_freq') for i in L['cpus']}
    batt = shell('dumpsys battery'); temp = int(next(x.split(':')[1] for x in batt.splitlines() if x.strip().startswith('temperature:'))) / 10
    board = shell('cat /sys/class/thermal/thermal_message/board_sensor_temp 2>/dev/null || echo 0')
    return {'max_freq_khz': caps, 'battery_temp_c': temp, 'board_temp': board}
shell('mkdir -p ' + remote)
binaries = {}
for v in versions:
    b = SP / f'smoke-{v}'; binaries[v] = hashlib.sha256(b.read_bytes()).hexdigest()
    adb('push', str(b), f'{remote}/{v}')
shell(f'chmod 755 {remote}/*')
# configs: the v0.2.0 comparison config (mbw1024, no prefix table) and the current AAR defaults (mbw512 + en prefix table)
configs = {}
for name, src, fix in [('enzh1024', L['cfg1024'], ('mini-batch-words: 512', 'mini-batch-words: 1024')), ('jaen1024', L['jaen1024'], ('mini-batch-words: 512', 'mini-batch-words: 1024')),
                       ('enzh512p', L['cfg512p'], None), ('jaen512', L['jaen512'], None)]:
    c = shell(f"cat {L['base']}/{src}")
    if fix: c = c.replace(*fix)
    c += '\n'; configs[name] = c
    (out.parent / f'{name}.yml').write_text(c); adb('push', str(out.parent / f'{name}.yml'), f'{remote}/{name}.yml')
assert 'ssplit-prefix-file' in configs['enzh512p'] and 'mini-batch-words: 512' in configs['enzh512p'] and 'mini-batch-words: 1024' in configs['enzh1024']
for lang, src in [('eng', 'eng200.txt'), ('jpn', 'jpn200.txt')]:
    shell(f"cp {L['base']}/{src} {remote}/{lang}.txt")
    assert shell(f'cat {remote}/{lang}.txt').splitlines() == (repo / f'sample/src/main/assets/bench/{lang}.txt').read_text().splitlines()
report = {'measured_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'device': {'model': shell('getprop ro.product.model'), 'android': shell('getprop ro.build.version.release'), 'soc': L['soc'], 'i8mm': L['i8mm']},
  'versions': {v: subprocess.check_output(['git', 'rev-parse', v + '^{commit}'], cwd=repo, text=True).strip() for v in versions},
  'binary_sha256': binaries, 'harness_sha256': hashlib.sha256((repo / 'tools/version-bench/main.cpp').read_bytes()).hexdigest(), 'config_yaml': configs,
  'protocol': {'affinity': L['mask'], 'rounds': 3, 'version_order': ['AB', 'BA', 'AB'], 'passes_per_process': 3, 'cache_size': 0, 'cooldown_seconds': 4,
               'cold_ms': 'service + model creation + first translation', 'peak_rss_mib': 'VmHWM after first translation', 'warm_ms': 'passes 1 and 2'}, 'runs': []}
# (name, workers, corpus, configs): v0.2.0-doc scenarios at mbw1024/async, then AAR-default scenarios (blocking, mbw512, prefix on)
scenarios = [('enzh_w1', 1, 'eng', ['enzh1024']), ('enzh_w2', 2, 'eng', ['enzh1024']), ('enzh_w4', 4, 'eng', ['enzh1024']), ('pivot_w1', 1, 'jpn', ['jaen1024', 'enzh1024']),
             ('enzh_b512p', 0, 'eng', ['enzh512p']), ('pivot_b512p', 0, 'jpn', ['jaen512', 'enzh512p']), ('enzh_w2_512p', 2, 'eng', ['enzh512p']), ('pivot_w2_512p', 2, 'jpn', ['jaen512', 'enzh512p'])]
for r in range(3):
    order = versions if r % 2 == 0 else versions[::-1]
    for name, workers, corpus, cfgs in scenarios:
        for v in order:
            before = conditions()
            cmd = f"taskset {L['mask']} {remote}/{v} {workers} " + ' '.join(f'{remote}/{c}.yml' for c in cfgs) + f' < {remote}/{corpus}.txt'
            res = subprocess.run([ADB, 'shell', cmd], capture_output=True, text=True, timeout=300)
            samples = [json.loads(l) for l in res.stdout.splitlines() if l.startswith('{')]
            run = {'round': r + 1, 'scenario': name, 'version': v, 'command': cmd, 'before': before, 'after': conditions(), 'returncode': res.returncode, 'samples': samples}
            if res.returncode or len(samples) != 3: run['error'] = (res.stdout + res.stderr)[-3000:]
            report['runs'].append(run); out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            s0 = samples[0] if samples else run.get('error')
            print(r + 1, name, v, res.returncode, (f"cold={s0['elapsed_ms']:.0f} peak={s0['peak_rss_mib']:.0f} hash={s0['output_hash']}" if samples else s0), flush=True)
            time.sleep(4)
print('Saved', out, flush=True)
