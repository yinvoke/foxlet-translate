"""Read-only verification of the charged-device session and score linkage."""
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
from statistics import median

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def app_checks():
    directory = HERE / 'app-measure'
    report = read(directory / 'results.json')
    assert report['protocol']['foreground_checks'] == 'awake-unlocked-focused-before-after-v1'
    assert report['source_files_sha256'] == digest(directory / 'source-files.json')
    sources = read(directory / 'source-files.json')
    for source, snapshot in (('tools/app-bench/run.py', 'app-run.py'),
                             ('tools/app-bench/check.py', 'app-check.py'),
                             ('tools/bench_device.py', 'bench_device.py')):
        assert sources[source] == digest(HERE / 'collector' / snapshot)
    result = subprocess.run([sys.executable, str(REPO / 'tools/app-bench/check.py'), str(directory)],
                            capture_output=True, text=True)
    assert result.returncode in (0, 2), result.stderr
    verdict = json.loads(result.stdout)
    if (directory / 'summary.json').exists():
        assert verdict == read(directory / 'summary.json')

    previous = HERE.parent / 'reconnected'
    # Revalidate the archived score/reference/export chain, not just its label.
    runpy.run_path(str(previous / 'verify.py'))['quality_checks']()
    previous_app = read(previous / 'app-measure/results.json')
    assert report['apk_sha256'] == previous_app['apk_sha256']
    assert report['corpus_sha256'] == previous_app['corpus_sha256']
    assert report['device'] == previous_app['device']
    quality = read(previous / 'quality-comet-with-reference.json')
    scores = {(s['direction'], s['output_sha256']): s['comet_x100'] for s in quality['scored_outputs']}
    matched, unknown, observed, metrics = 0, [], {}, {}
    for run in report['runs']:
        if run.get('status') != 'complete':
            continue
        raw = read(directory / run['result_file'])
        label = f"{run['direction']}/{run['engine']}/{run['threads']}t"
        passes = raw['passes']
        metrics.setdefault(label, []).append({
            'cold_ms': passes[0]['elapsed_ms'],
            'warm_ms': median(p['elapsed_ms'] for p in passes[1:]),
            'cold_pss_mib': passes[0]['metrics']['peakPssMb'],
            'warm_pss_mib': max(p['metrics']['peakPssMb'] for p in passes[1:]),
        })
        for sample in raw['passes']:
            outputs = sample['outputs']
            assert len(outputs) == 200
            key = (run['direction'], hashlib.sha256(('\n'.join(outputs) + '\n').encode()).hexdigest())
            assert key[1] == sample['output_sha256']
            if key not in scores:
                unknown.append({'run_id': run['run_id'], 'pass': sample['pass'], 'output_sha256': key[1]})
                continue
            matched += 1
            observed.setdefault(f"{run['direction']}/{run['engine']}", set()).add(scores[key])
    spreads = []
    for scenario, measurements in metrics.items():
        if len(measurements) != 3:
            continue
        for metric in ('cold_ms', 'warm_ms', 'cold_pss_mib', 'warm_pss_mib'):
            values = [m[metric] for m in measurements]
            spread = 100 * (max(values) - min(values)) / median(values)
            if spread > 10:
                spreads.append({'scenario': scenario, 'metric': metric,
                                'values': values, 'spread_pct': spread})
    ratios = {}
    for direction in ('enzh', 'jazh'):
        rows = {s['scenario']: s for s in verdict['scenarios']}
        ml = rows[f'{direction}/mlkit/Nonet']
        bg = rows[f'{direction}/bergamot/1t']
        if ml['successful_processes'] == bg['successful_processes'] == 3:
            ratios[direction] = {'cold_speed': ml['cold_ms'] / bg['cold_ms'],
                                 'warm_speed': ml['warm_ms'] / bg['warm_ms'],
                                 'cold_pss': bg['cold_pss_mib'] / ml['cold_pss_mib'],
                                 'warm_pss': bg['warm_pss_mib'] / ml['warm_pss_mib']}
    temperatures = [r['before']['battery_temp_c'] for r in report['runs'] if 'before' in r]
    return {'verdict': verdict, 'matched_scored_passes': matched, 'needs_scoring': unknown,
            'comet_x100': {key: sorted(values) for key, values in observed.items()},
            'spreads_exceeding_10pct': spreads, 'single_thread_vs_mlkit': ratios,
            'starting_battery_temperature_c': [min(temperatures), max(temperatures)] if temperatures else [],
            'quality_source_sha256': digest(previous / 'quality-comet-with-reference.json')}


def native_checks(filename='native-results.json'):
    path = HERE / filename
    report = read(path)
    if filename == 'native-results.json':
        assert report['protocol']['foreground_checks'] == 'awake-unlocked-focused-before-after-v1'
        for source, expected in report['collector_files_sha256'].items():
            assert digest(HERE / 'collector' / Path(source).name) == expected
    result = subprocess.run([sys.executable, str(REPO / 'tools/version-bench/check.py'), str(path), str(path),
                             '--baseline-version', 'v0.2.0', '--candidate-version', 'v0.3.0'],
                            capture_output=True, text=True)
    assert result.returncode in (0, 1, 2), result.stderr
    return {'exit_code': result.returncode, 'accepted': result.returncode == 0,
            'successful_processes': sum(r.get('returncode') == 0 for r in report['runs']),
            'planned_processes': 48, 'diagnostic': (result.stdout + result.stderr).strip()}


def native_table():
    """Show every fixed row; descriptive medians do not override acceptance."""
    report = read(HERE / 'native-results.json')
    rows = []
    for scenario, spec in report['scenarios'].items():
        for version in ('v0.2.0', 'v0.3.0'):
            runs = [r for r in report['runs'] if r['scenario'] == scenario and r['version'] == version]
            complete = len(runs) == 3 and {r['round'] for r in runs} == {1, 2, 3}
            complete = complete and all(r.get('returncode') == 0 and not r.get('error')
                                        and len(r.get('samples', [])) == 3 for r in runs)
            row = {'scenario': scenario, 'version': version, 'complete': complete}
            if complete:
                values = {
                    'cold_ms': [r['samples'][0]['elapsed_ms'] for r in runs],
                    'warm_ms': [median(s['elapsed_ms'] for s in r['samples'][1:]) for r in runs],
                    'peak_rss_mib': [r['samples'][0]['peak_rss_mib'] for r in runs],
                }
                row.update({metric: median(nums) for metric, nums in values.items()})
                row['spread_pct'] = {metric: 100 * (max(nums) - min(nums)) / median(nums)
                                     for metric, nums in values.items()}
                row['cold_inputs_per_second'] = spec['sentences'] * 1000 / row['cold_ms']
                row['warm_inputs_per_second'] = spec['sentences'] * 1000 / row['warm_ms']
            rows.append(row)
    return rows


if __name__ == '__main__':
    result = {'unverified_attempt': native_checks('native-before-foreground-check.json')}
    if (HERE / 'native-results.json').exists():
        result['native'] = native_checks()
        result['native_rows'] = native_table()
    if (HERE / 'app-measure').exists():
        result['app'] = app_checks()
    print(json.dumps(result, ensure_ascii=False, indent=2))
