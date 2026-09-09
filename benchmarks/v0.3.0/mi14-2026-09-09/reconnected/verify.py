"""Read-only checks for this archived session; also used by verify.ipynb."""
import hashlib
import json
from pathlib import Path
from statistics import mean
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def output_sha(outputs):
    return hashlib.sha256(('\n'.join(outputs) + '\n').encode()).hexdigest()


def quality_checks():
    quality = read(HERE / 'quality-comet-with-reference.json')
    native_dir = HERE / 'quality-reference'
    native = read(native_dir / 'manifest.json')
    previous = read(HERE.parent / 'quality-comet-completed.json')
    app_dir = HERE.parent / 'app-measure-foreground'
    assert quality['status'] == native['status'] == 'complete'
    assert quality['source_report_sha256'] == previous['source_report_sha256']
    assert previous['archived_source_report_sha256'] == sha(app_dir / 'results.json')
    assert quality['checkpoint_sha256'] == previous['checkpoint_sha256']
    assert quality['reference_slice_sha256'] == previous['reference_slice_sha256']
    assert quality['source_report_status'] == 'failed'
    assert quality['performance_validated'] is False
    assert native['model_files_sha256_before'] == native['model_files_sha256_after']
    for lang, digest in native['corpus_sha256'].items():
        assert digest == sha(native_dir / 'inputs' / f'{lang}.txt')
    for name, digest in native['harness_files'].items():
        assert digest == sha(native_dir / 'harness' / name)

    expected = {(r, v, d) for r in (1, 2, 3) for v in ('v0.2.0', 'v0.3.0') for d in ('enzh', 'jazh')}
    assert len(native['runs']) == 12
    assert {(r['round'], r['version'], r['direction']) for r in native['runs']} == expected
    exports = {}
    for run in native['runs']:
        assert run['status'] == 'complete' and run['returncode'] == 0
        path = native_dir / run['output_file']
        outputs = read(path)
        assert len(outputs) == 200 and all(isinstance(x, str) and x.strip() for x in outputs)
        assert sha(path) == run['file_sha256']
        assert output_sha(outputs) == run['output_sha256']
        exports[(run['version'], run['direction'], run['round'])] = outputs

    scores = {(s['direction'], s['output_sha256']): s for s in quality['scored_outputs']}
    assert len(scores) == len(quality['scored_outputs']) == 8
    for score in scores.values():
        assert len(score['sentence_scores_x100']) == 200
        assert abs(mean(score['sentence_scores_x100']) - score['comet_x100']) < 1e-5
    labels = set()
    for job in quality['runs']:
        assert job['label'] not in labels
        labels.add(job['label'])
        path = (native_dir if job['label'].startswith('native-') else app_dir) / Path(job['source_file']).name
        assert sha(path) == job['source_sha256']
        raw = read(path)
        outputs = raw if isinstance(raw, list) else raw['passes'][int(job['label'].rsplit('-p', 1)[1])]['outputs']
        assert output_sha(outputs) == job['output_sha256']
        assert scores[(job['direction'], job['output_sha256'])]['comet_x100'] == job['comet_x100']
    assert len(labels) == 42  # 10 completed app processes x 3 passes + 12 native exports

    rows = []
    for direction in ('enzh', 'jazh'):
        old = [exports[('v0.2.0', direction, r)] for r in (1, 2, 3)]
        new = [exports[('v0.3.0', direction, r)] for r in (1, 2, 3)]
        assert new[0] == new[1] == new[2]
        new_hash = output_sha(new[0])
        app_hashes = {r['output_sha256'] for r in quality['runs']
                      if r['label'].startswith('bergamot-') and r['direction'] == direction}
        assert app_hashes == {new_hash}
        before = [scores[(direction, output_sha(x))]['comet_x100'] for x in old]
        after = scores[(direction, new_hash)]['comet_x100']
        differences = [after - x for x in before]
        assert max(abs(x) for x in differences) < .12
        rows.append({'direction': direction, 'old_comet_range': [min(before), max(before)],
                     'new_comet': after, 'new_minus_old_range': [min(differences), max(differences)],
                     'old_unique_outputs': len({output_sha(x) for x in old}),
                     'old_repeats_changed_rows': [sum(a != b for a, b in zip(old[0], x)) for x in old],
                     'old_to_new_changed_rows': [sum(a != b for a, b in zip(x, new[0])) for x in old]})
    return rows


def app_checks(directory):
    command = [sys.executable, str(REPO / 'tools/app-bench/check.py'), str(directory)]
    result = subprocess.run(command, capture_output=True, text=True)
    assert result.returncode in (0, 2), result.stderr
    summary = json.loads(result.stdout)
    assert summary['accepted'] == (result.returncode == 0)
    if (directory / 'summary.json').exists():
        assert summary == read(directory / 'summary.json')
    return summary


def current_app_checks():
    """Map fresh outputs to existing scores only when all hashes match."""
    directory = HERE / 'app-measure'
    report = read(directory / 'results.json')
    verdict = app_checks(directory)
    quality = read(HERE / 'quality-comet-with-reference.json')
    scores = {(s['direction'], s['output_sha256']): s['comet_x100'] for s in quality['scored_outputs']}
    observed = {}
    passes = 0
    for run in report['runs']:
        if run.get('status') != 'complete':
            continue
        raw = read(directory / run['result_file'])
        for sample in raw['passes']:
            digest = output_sha(sample['outputs'])
            assert len(sample['outputs']) == 200 and digest == sample['output_sha256']
            key = (run['direction'], digest)
            assert key in scores, 'New translations need new COMET scoring'
            label = f"{run['direction']}/{run['engine']}"
            score = scores[key]
            if label in observed:
                assert observed[label] == score
            observed[label] = score
            passes += 1
    return {'verdict': verdict, 'scored_output_matched_passes': passes, 'comet_x100': observed}


def native_checks():
    path = HERE / 'native-results.json'
    result = subprocess.run([sys.executable, str(REPO / 'tools/version-bench/check.py'), str(path), str(path),
                             '--baseline-version', 'v0.2.0', '--candidate-version', 'v0.3.0'],
                            capture_output=True, text=True)
    assert result.returncode in (0, 1, 2), result.stderr
    return {'exit_code': result.returncode, 'accepted': result.returncode == 0,
            'diagnostic': (result.stdout + result.stderr).strip()}


if __name__ == '__main__':
    result = {'quality': quality_checks(),
              'read_race_rejected': not app_checks(HERE / 'app-read-race')['accepted']}
    if (HERE / 'app-measure').exists():
        result['current_app'] = current_app_checks()
    if (HERE / 'native-results.json').exists():
        result['native'] = native_checks()
    print(json.dumps(result, ensure_ascii=False, indent=2))
