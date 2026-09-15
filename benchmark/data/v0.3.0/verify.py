#!/usr/bin/env python3
"""Run every archived v0.3.0 check.

Success means the archive is internally consistent. It does not re-measure the
device, does not rerun the COMET model, and does not turn a recorded
acceptance failure into a pass.
"""
import json
from pathlib import Path
import runpy
import subprocess
import sys

CHECKS = Path(__file__).resolve().parent / 'checks'


def main():
    report = {}
    for name in ('initial', 'quality', 'native'):
        report[name] = runpy.run_path(str(CHECKS / f'{name}.py'), run_name='__not_main__')
    initial = report['initial']['analyze']()
    quality = report['quality']['quality_checks']()
    native = report['native']['native_checks']()
    app = report['native']['app_checks']()
    subprocess.run([sys.executable, '-B', str(CHECKS / 'test_initial.py')], check=True)
    print(json.dumps({
        'initial_comparison': {'processes': initial['successful_processes'],
                               'accepted_as_strict_baseline': initial['accepted_as_strict_baseline']},
        'native': {'exit_code': native['exit_code'], 'accepted': native['accepted'],
                   'processes': native['successful_processes']},
        'app': {'accepted': app['verdict']['accepted'],
                'scored_passes': app['matched_scored_passes'],
                'metrics_over_10pct_spread': len(app['spreads_exceeding_10pct'])},
        'quality': quality,
    }, ensure_ascii=False, indent=2))
    print('\nArchive verified. Device benchmarks and model scoring were not rerun.',
          file=sys.stderr)


if __name__ == '__main__':
    main()
