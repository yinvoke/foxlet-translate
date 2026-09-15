#!/usr/bin/env python3
"""Verify archived evidence; success does not certify performance stability."""
import hashlib
import json
import math
from pathlib import Path
import re
import runpy
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'raw'
VERSIONS = ('v0.4.0', 'v0.5.0')
SCENARIOS = {(d, w) for d in ('en-zh', 'ja-zh') for w in (1, 2, 4)} | {('ru-en', 1), ('ru-en', 2)}


def read(path):
    return json.loads((DATA / path).read_text(encoding='utf-8'))


def close(actual, expected):
    assert math.isclose(actual, expected, rel_tol=1e-10, abs_tol=1e-9), (actual, expected)


def verify_performance(group):
    base = f'mi14/aar-100/{group}'
    runs, summary, stability = (read(f'{base}/{f}.json') for f in ('runs', 'summary', 'stability'))
    assert len(runs) == summary['processes'] == 48
    expected = {(repeat, version, d, w) for repeat in range(3) for version in VERSIONS for d, w in SCENARIOS}
    assert {(r['repeat'], r['variant'], r['direction'], r['workers']) for r in runs} == expected
    assert len(summary['rows']) == 8
    assert {(r['direction'], r['workers']) for r in summary['rows']} == SCENARIOS
    issues = []
    for row in summary['rows']:
        for version in VERSIONS:
            subset = [r for r in runs if (r['variant'], r['direction'], r['workers']) == (version, row['direction'], row['workers'])]
            hashes = set()
            metrics = {key: [] for key in ('first_ms', 'warm_ms', 'first_peak_pss_kib', 'warm_peak_pss_kib')}
            for run in subset:
                result = run['result']
                assert result['foreground_before_after_passed'] and result['count'] == 100
                assert (result['direction'], result['workers']) == (row['direction'], row['workers'])
                samples = result['samples']
                assert [s['iteration'] for s in samples] == [0, 1, 2]
                hashes.update(s['output_sha256'] for s in samples)
                assert all(s['elapsed_ms'] > 0 and s['peak_pss_kib'] > 0 for s in samples)
                metrics['first_ms'].append(samples[0]['elapsed_ms'])
                metrics['warm_ms'].append(statistics.median(s['elapsed_ms'] for s in samples[1:]))
                metrics['first_peak_pss_kib'].append(samples[0]['peak_pss_kib'])
                metrics['warm_peak_pss_kib'].append(max(s['peak_pss_kib'] for s in samples[1:]))
            assert len(hashes) == 1
            for metric, values in metrics.items():
                median = statistics.median(values)
                name = metric.replace('_kib', '_mib')
                close(row[version][name], median / 1024 if metric.endswith('_kib') else median)
                spread = 100 * (max(values) - min(values)) / median
                if spread > 10:
                    issues.append((row['direction'], row['workers'], version, metric, spread))
                if metric == 'warm_ms':
                    close(row[version]['warm_sentences_per_second'], 100000 / median)
                    close(row[version]['warm_range_over_median_percent'], spread)
            assert row[version]['process_first_ms'] == metrics['first_ms']
            assert row[version]['process_warm_ms'] == metrics['warm_ms']
        for field, metric in [('warm_time_change_percent', 'warm_ms'), ('warm_pss_change_percent', 'warm_peak_pss_mib')]:
            close(row[field], 100 * (row['v0.5.0'][metric] / row['v0.4.0'][metric] - 1))
    recorded = stability['metrics_with_spread_over_10percent']
    assert len(issues) == len(recorded)
    for actual, item in zip(issues, recorded):
        assert actual[:4] == (item['direction'], item['workers'], item['version'], item['metric'])
        close(actual[4], item['range_over_median_percent'])
    temperatures = [int(re.search(r'temperature: (\d+)', r[k]['battery']).group(1)) / 10 for r in runs for k in ('before', 'after')]
    span = max(temperatures) - min(temperatures)
    close(stability['temperature_min_c'], min(temperatures))
    close(stability['temperature_max_c'], max(temperatures))
    close(stability['temperature_span_c'], span)
    assert stability['complete'] and stability['foreground_checks_passed']
    assert stability['temperature_span_within_3c'] == (span <= 3)
    assert stability['stability_passed'] == (not issues and span <= 3)
    print(f'{group}: 48 processes verified; stability_passed={stability["stability_passed"]}, {len(issues)} spread failures')


def main():
    manifest = json.loads((ROOT / 'manifest.json').read_text(encoding='utf-8'))['files']
    actual_files = {str(p.relative_to(ROOT)) for p in ROOT.rglob('*')
                    if p.is_file() and p != ROOT / 'manifest.json'
                    and '__pycache__' not in p.parts}
    assert actual_files == set(manifest), 'Archive file inventory changed'
    for path, digest in manifest.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest, path
    for group in ('initial', 'followup'):
        verify_performance(group)
    for name in ('current', 'ssplit-baseline'):
        data = read(f'sentence-quality/{name}.json')
        assert len(data['sources']) == 11
        assert sum(s['sentences'] for s in data['sources'].values()) == 20877
        for key in ('tp', 'fp', 'fn'):
            assert sum(s[key] for s in data['sources'].values()) == data['total'][key]
        for s in [*data['sources'].values(), data['total']]:
            close(s['precision'], s['tp'] / (s['tp'] + s['fp']))
            close(s['recall'], s['tp'] / (s['tp'] + s['fn']))
            close(s['f1'], 2 * s['tp'] / (2 * s['tp'] + s['fp'] + s['fn']))
        assert data['manifest_sha256'] == hashlib.sha256((DATA / 'sentence-quality/corpora.json').read_bytes()).hexdigest()
    runs = read('host/quality/runs.json')
    assert len(runs) == 32 and sum(r['count'] for r in runs) == 20240
    indexed = {r['key']: r for r in runs}
    comparison = read('host/quality/pre-trim-comparison.json')
    assert comparison['all_identical'] and comparison['total_outputs'] == 10120
    for r in comparison['rows']:
        assert r['matches_accepted_pre_trim']
        run = indexed[f"{r['direction']}.{r['shape']}.current"]
        assert (r['sha256'], r['count']) == (run['output_sha256'], run['count'])
    host = read('host/quality/scores.json')['results']
    assert len(host) == 16
    for r in host:
        for metric in ('chrfpp', 'bleu'):
            close(r[f'{metric}_delta'], r['scores']['current'][metric] - r['scores']['legacy'][metric])
    for r in read('host/quality/comet.json')['results']:
        assert r['n'] == 1012 and len(r['pairs']) == r['changed_count']
        for pair in r['pairs']:
            close(pair['delta'], pair['current'] - pair['legacy'])
        close(r['comet_x100_mean_delta'], sum(p['delta'] for p in r['pairs']) / r['n'])
    hashes = {}
    for version in VERSIONS:
        result = read(f'mi14/aar-quality/{version}-result.json')
        assert result['passed'] and result['row_translations'] == 4088
        rows = read(f'mi14/aar-quality/{version}-output-hashes.json')['results']
        assert len(rows) == 16 and sum(r['count'] for r in rows) * 2 == 4088
        assert all(r['repeat_equal'] for r in rows)
        hashes[version] = {}
        for r in rows:
            assert len(r['row_sha256']) == r['count']
            key = (r['direction'], r['workers'])
            assert hashes[version].setdefault(key, r['row_sha256']) == r['row_sha256']
        assert len(hashes[version]) == 16
    for r in read('mi14/aar-quality/scores.json')['results']:
        key = (r['direction'], r['workers'])
        start, stop = (0, 100) if r['shape'] == 'single' else (100, 125)
        assert r['count'] == stop - start
        assert r['changed_rows'] == sum(a != b for a, b in zip(hashes['v0.4.0'][key][start:stop], hashes['v0.5.0'][key][start:stop]))
        for metric in ('chrfpp', 'bleu'):
            close(r[f'{metric}_delta'], r['v0.5.0'][metric] - r['v0.4.0'][metric])
    a, b = read('artifacts.json'), read('build-checks.json')
    assert a['candidate']['aar_sha256'] == b['aar_sha256']
    assert a['candidate']['aar_bytes'] == b['aar_bytes']
    saved = a['baseline']['aar_bytes'] - a['candidate']['aar_bytes']
    assert saved == b['saved_vs_published_v0.4.0_bytes']
    close(b['saved_vs_published_v0.4.0_percent'], 100 * saved / a['baseline']['aar_bytes'])
    standard = ROOT / 'checks/standard.py'
    if standard.is_file():
        subprocess.run([sys.executable, '-B', str(standard)], check=True)
        index = json.loads((ROOT / 'index.json').read_text(encoding='utf-8'))
        generator = runpy.run_path(str((ROOT / index['collectors']['collector/generate.py']).resolve()))
        config, derived = generator['derive'](ROOT / 'index.json')
        summary = json.loads((ROOT / 'derived/summary.json').read_text(encoding='utf-8'))
        assert derived == summary, 'Derived displays no longer match raw records'
        for en, name in ((False, 'README.md'), (True, 'README.en.md')):
            assert generator['render'](config, derived, en) == (ROOT / 'derived' / name).read_text()
    print(f'Archive verified: {len(manifest)} files. Scores checked arithmetically; model scoring was not rerun.')


if __name__ == '__main__':
    main()
