#!/usr/bin/env python3
"""Replay complete archived protocols and verify score-to-output linkage offline.

An archive verification pass does not override a benchmark rejection and does
not rerun device measurements or the COMET model.
"""
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'shared'))
from archive import open_archive

A = open_archive(Path(__file__).resolve().parents[1])
HERE = A.path('mi14/standard')
VERSIONS = ('v0.3.0', 'v0.5.0')
# Native quality exports whose translations came out identical are stored once;
# every record carries the sha256 that locates the stored copy.
NATIVE_EXPORTS = A.by_content(HERE / 'quality/native')


def stored(rel, sha256):
    """The archived payload for a recorded file name, wherever it is stored."""
    path = HERE / rel
    return path if path.is_file() else NATIVE_EXPORTS[sha256]


def session_v1(rel):
    """The interrupted v1 session record; its name inside the report is unchanged."""
    return HERE / rel.replace('app-interrupted-v1/', 'app/interrupted-v1/')


def read(path):
    return json.loads((HERE / path).read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256((HERE / path).read_bytes()).hexdigest()


def output_digest(outputs):
    assert len(outputs) == 200 and all(isinstance(x, str) for x in outputs)
    return hashlib.sha256(('\n'.join(outputs) + '\n').encode()).hexdigest()


def close(a, b):
    assert math.isclose(a, b, rel_tol=1e-10, abs_tol=1e-9), (a, b)


def replay(kind, package=None):
    with tempfile.TemporaryDirectory(prefix='foxlet-standard-check-') as tmp:
        root = Path(tmp)
        shutil.copyfile(A.collector('collector/bench_device.py'), root / 'bench_device.py')
        sub = root / kind
        sub.mkdir()
        shutil.copyfile(A.collector(f'collector/{kind}-check.py'), sub / 'check.py')
        if kind == 'native':
            shutil.copyfile(A.collector('collector/suite.py'), sub / 'suite.py')
            shutil.copytree(A.collector('collector/suites/android-native-v1.json').parent, sub / 'suites')
            path = HERE / 'native/results.json'
            args = [str(sub / 'check.py'), str(path), str(path), '--baseline-version', VERSIONS[0], '--candidate-version', VERSIONS[1]]
        else:
            shutil.copyfile(A.collector('collector/app-run.py'), sub / 'run.py')
            # Package rename is explicit in captured evidence, not rewritten.
            # check.main uses argparse, so invoke a small wrapper instead.
            wrapper = sub / 'wrapper.py'
            wrapper.write_text('import sys\nsys.path.insert(0, '+repr(str(root))+')\nimport bench_device\nbench_device.PACKAGE='+repr(package)+'\nimport check\ncheck.main()\n')
            version = VERSIONS[0] if 'bergamot' in package else VERSIONS[1]
            args = [str(wrapper), str(HERE / 'app' / version)]
        return subprocess.run([sys.executable, '-B', *args], capture_output=True, text=True)


def native_checks():
    report = read('native/results.json')
    assert len(report['runs']) == 48 and len(report['scenarios']) == 8
    summary = read('native/summary.json')
    assert len(summary['rows']) == 8
    for row in summary['rows']:
        for version in VERSIONS:
            runs = [r for r in report['runs'] if (r['scenario'], r['version']) == (row['scenario'], version)]
            assert len(runs) == 3 and {r['round'] for r in runs} == {1, 2, 3}
            assert all(r['returncode'] == 0 and len(r['samples']) == 3 for r in runs)
            values = {'cold_ms': [r['samples'][0]['elapsed_ms'] for r in runs], 'warm_ms': [statistics.median(s['elapsed_ms'] for s in r['samples'][1:]) for r in runs], 'peak_rss_mib': [r['samples'][0]['peak_rss_mib'] for r in runs]}
            assert row[version]['process_values'] == values
            for metric, numbers in values.items():
                close(row[version][metric], statistics.median(numbers))
                close(row[version]['spread_percent'][metric], 100*(max(numbers)-min(numbers))/statistics.median(numbers))
            for state in ('cold', 'warm'):
                close(row[version][state+'_inputs_per_second'], 200000/row[version][state+'_ms'])
        for metric in ('cold_ms', 'warm_ms', 'peak_rss_mib'):
            close(row['change_percent'][metric], 100*(row[VERSIONS[1]][metric]/row[VERSIONS[0]][metric]-1))
    result = replay('native')
    assert result.returncode == read('native/check-exit.json')['exit_code'], result.stderr
    assert result.stdout.strip() == (HERE / 'native/check.log').read_text().strip()
    print('Native: 48 processes verified; original checker exit', result.returncode)
    return report


def score_checks(path):
    data = read(path)
    assert data['input_count'] == 200
    assert data['reference_slice_sha256'] == digest(A.corpus('zho_Hans.devtest'))
    scores = {}
    for row in data['scored_outputs']:
        assert len(row['sentence_scores_x100']) == 200
        close(row['comet_x100'], statistics.mean(row['sentence_scores_x100']))
        key = (row['direction'], row['output_sha256'])
        assert key not in scores
        scores[key] = row
    for row in data['runs']:
        path = stored(row['source_file'], row['source_sha256'])
        assert digest(path) == row['source_sha256'], row['source_file']
        source = read(path)
        candidates = [source] if isinstance(source, list) else [p['outputs'] for p in source['passes']]
        assert row['output_sha256'] in {output_digest(o) for o in candidates}
        close(row['comet_x100'], scores[(row['direction'], row['output_sha256'])]['comet_x100'])
    return data, scores


def main():
    provenance = read('collector-provenance.json')['files']
    for name, hashes in provenance.items():
        assert digest(A.collector('collector/'+name)) == hashes['archive_sha256']
    native = native_checks()
    assert native['harness_sha256'] == provenance['main.cpp']['original_sha256']
    for key, value in native['collector_files_sha256'].items():
        name = {'run-pair.py':'native-run-pair.py', 'check.py':'native-check.py'}.get(Path(key).name, Path(key).name)
        assert value == provenance[name]['original_sha256'], key
    sampler = read('sampler-provenance.json')
    assert sampler['baseline_sha256'] == provenance['MetricsSampler-v0.3.0.kt']['original_sha256']
    assert sampler['candidate_sha256'] == provenance['MetricsSampler.kt']['original_sha256']
    assert A.collector('collector/MetricsSampler-v0.3.0.kt').read_text().replace('io.github.yinvoker.bergamot.bench','io.github.yinvoker.foxlet.bench') == A.collector('collector/MetricsSampler.kt').read_text()
    models = []
    app_outputs, app_scores = {}, {}
    for version, package in zip(VERSIONS, ('io.github.yinvoker.bergamot.bench', 'io.github.yinvoker.foxlet.bench')):
        report = read(f'app/{version}/results.json')
        assert report['package'] == package and len(report['runs']) <= 18
        assert report['suite_id'] == 'android-app-v2'
        mapping = {'app_pair.py':'app_pair.py','app_pair_v2.py':'app_pair_v2.py','tools/app-bench/run.py':'app-run-v1.py','tools/app-bench/run-v2.py':'app-run.py','tools/bench_device.py':'bench_device.py'}
        for key, value in report['collector_files_sha256'].items():
            assert value == provenance[mapping[key]]['original_sha256'], key
        if version == 'v0.3.0':
            source = read(f'app/{version}/source-files.json')
            assert source['sample/src/main/kotlin/io/github/yinvoker/bergamot/bench/IsolatedBenchRunner.kt'] == provenance['IsolatedBenchRunner-v0.3.0.kt']['original_sha256']
        v1_record = session_v1(report['continued_from']['file'])
        original = read(v1_record)
        assert digest(v1_record) == report['continued_from']['sha256']
        for run_id in report['continued_from']['retained_run_ids']:
            old = next(r for r in original['runs'] if r['run_id']==run_id)
            new = next(r for r in report['runs'] if r['run_id']==run_id)
            assert old == new and old['threads'] != 4
            # Both sessions name the same payload, which the archive stores once.
            assert old['result_file'] == new['result_file']
            assert (HERE / f'app/{version}' / new['result_file']).is_file()
        result = replay('app', package)
        assert result.returncode in (0, 2), result.stderr
        verdict = json.loads(result.stdout)
        assert verdict == read(f'app/{version}/summary.json')
        assert all(1 <= s['successful_processes'] <= 3 for s in verdict['scenarios'])
        assert not verdict['accepted'], 'Stopped run must not be promoted to a completed baseline'
        # Preserve any metadata/model mutation as an acceptance failure.
        models.append(report['model_files_sha256_before'])
        quality, scores = score_checks(f'quality/{version}-comet.json')
        app_scores[version] = scores
        assert quality['status'] == 'complete'
        completed = sum(r.get('status') == 'complete' for r in report['runs'])
        assert len(quality['runs']) == completed*3 == quality['completed_processes']*3
        assert quality['planned_processes'] == 18 and quality['uncompleted_cells']
        assert quality['source_report_sha256'] == digest(f'app/{version}/results.json')
        for run in report['runs']:
            if run.get('status') != 'complete':
                continue
            for sample in read(f'app/{version}/'+run['result_file'])['passes']:
                assert output_digest(sample['outputs']) == sample['output_sha256']
                assert (run['direction'], sample['output_sha256']) in scores
                if run['engine'] == 'bergamot' and run['threads'] == 1:
                    key = (version, run['direction'])
                    assert app_outputs.setdefault(key, sample['outputs']) == sample['outputs']
        print(version, f'App: {completed}/18 processes; existing outputs scored; accepted=', verdict['accepted'], 'issues=', verdict['errors'])
    assert models[0] == models[1]
    for direction, row in read('quality/app-comparison.json')['results'].items():
        assert row['input_count'] == 200
        assert row['changed_rows'] == sum(a != b for a,b in zip(app_outputs[(VERSIONS[0], direction)], app_outputs[(VERSIONS[1], direction)]))
        for version in VERSIONS:
            close(row[version], app_scores[version][(direction, output_digest(app_outputs[(version, direction)]))]['comet_x100'])
        close(row['delta'], row[VERSIONS[1]]-row[VERSIONS[0]])
    quality, scores = score_checks('quality/native-comet.json')
    manifest = read('quality/native/manifest.json')
    assert manifest['native_timing_report_sha256'] == digest('native/results.json')
    assert len(manifest['runs']) == len(quality['runs']) == 12
    exports = {(r['version'], r['direction'], r['repeat']): r['file_sha256'] for r in manifest['runs']}
    for row in manifest['runs']:
        path = stored('quality/native/'+row['file'], row['file_sha256'])
        outputs = read(path)
        assert digest(path) == row['file_sha256']
        assert output_digest(outputs) == row['output_sha256']
        fnv = 14695981039346656037
        for byte in ('\n'.join(outputs)+'\n').encode():
            fnv = ((fnv ^ byte)*1099511628211) & ((1<<64)-1)
        assert f'{fnv:016x}' == row['native_fnv1a64']
        scenario = 'enzh_b512p' if row['direction'] == 'enzh' else 'pivot_b512p'
        hashes = {s['output_hash'] for r in native['runs'] if r['version']==row['version'] and r['scenario']==scenario for s in r['samples']}
        assert hashes == {row['native_fnv1a64']}
        assert (row['direction'], row['output_sha256']) in scores
    for direction, row in read('quality/native-comparison.json')['results'].items():
        outputs = {v: read(NATIVE_EXPORTS[exports[(v, direction, 1)]]) for v in VERSIONS}
        assert row['changed_rows'] == sum(a != b for a,b in zip(*outputs.values()))
        for version in VERSIONS:
            close(row[version], scores[(direction, output_digest(outputs[version]))]['comet_x100'])
        close(row['delta'], row[VERSIONS[1]]-row[VERSIONS[0]])
    print('Standard evidence verified; device benchmarks and model scoring were not rerun.')


if __name__ == '__main__':
    main()
