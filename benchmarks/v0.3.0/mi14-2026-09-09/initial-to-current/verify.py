"""Read-only checks for the Mi 14 initial/current native comparison."""
import hashlib
import json
from pathlib import Path
from statistics import median
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
VERSIONS = ('v0.1.0', 'v0.3.0')


def verify(filename):
    path = HERE / filename
    report = json.loads(path.read_text())
    assert set(report['versions']) == set(VERSIONS)
    for name, expected in report['collector_files_sha256'].items():
        assert hashlib.sha256((HERE / 'collector' / Path(name).name).read_bytes()).hexdigest() == expected
    assert hashlib.sha256((HERE / 'collector/main.cpp').read_bytes()).hexdigest() == report['harness_sha256']
    result = subprocess.run([sys.executable, str(REPO / 'tools/version-bench/check.py'), str(path), str(path),
                             '--baseline-version', VERSIONS[0], '--candidate-version', VERSIONS[1]],
                            capture_output=True, text=True)
    assert result.returncode in (0, 1, 2), result.stderr
    rows = []
    for scenario, spec in report['scenarios'].items():
        for version in VERSIONS:
            runs = [r for r in report['runs'] if r['version'] == version and r['scenario'] == scenario]
            success = [r for r in runs if r.get('returncode') == 0 and not r.get('error')
                       and len(r.get('samples', [])) == 3]
            complete = len(runs) == len(success) == 3 and {r['round'] for r in runs} == {1, 2, 3}
            row = {'scenario': scenario, 'version': version, 'successful_processes': len(success),
                   'complete': complete, 'errors': [r['error'] for r in runs if r.get('error')]}
            if complete:
                metrics = {'cold_ms': [r['samples'][0]['elapsed_ms'] for r in runs],
                           'warm_ms': [median(s['elapsed_ms'] for s in r['samples'][1:]) for r in runs],
                           'peak_rss_mib': [r['samples'][0]['peak_rss_mib'] for r in runs]}
                row.update({key: median(values) for key, values in metrics.items()})
                row['spread_pct'] = {key: 100 * (max(values) - min(values)) / median(values)
                                     for key, values in metrics.items()}
                row['cold_inputs_per_second'] = spec['sentences'] * 1000 / row['cold_ms']
                row['warm_inputs_per_second'] = spec['sentences'] * 1000 / row['warm_ms']
            rows.append(row)
    return {'file': filename, 'accepted': result.returncode == 0, 'exit_code': result.returncode,
            'successful_processes': sum(row['successful_processes'] for row in rows),
            'planned_processes': 48, 'diagnostic': (result.stdout + result.stderr).strip(), 'rows': rows}


if __name__ == '__main__':
    files = [p.name for p in sorted(HERE.glob('attempt-*.json'))]
    if (HERE / 'native-results.json').exists():
        files.append('native-results.json')
    print(json.dumps([verify(name) for name in files], ensure_ascii=False, indent=2))
