"""Recompute descriptive results without promoting an ungated run to a baseline."""
import argparse
import hashlib
import json
from pathlib import Path
from statistics import median
import subprocess
import sys

HERE = Path(__file__).resolve().parent
COLLECTOR = HERE / 'collector'
sys.path.insert(0, str(COLLECTOR / 'tools/version-bench'))
import check
from suite import load_suite

VERSIONS = ('v0.1.0', 'v0.3.0')


def analyze(path=HERE / 'native-results.json'):
    path = Path(path).resolve()
    raw = json.loads(path.read_text())
    suite, scenarios, digest = load_suite(raw['suite_id'])
    assert raw['suite_sha256'] == digest and raw['scenarios'] == scenarios
    assert list(raw['versions']) == list(VERSIONS)
    assert raw['report_kind'] == 'exploratory'
    assert raw['protocol']['gate_khz'] is None
    assert raw['protocol']['frequency_gate_mode'] == 'skipped-explicitly'
    assert raw['protocol']['reference_gate_khz'] == 2630400
    assert raw['protocol']['foreground_checks'] == check.FOREGROUND_CHECKS
    for key, value in suite['protocol'].items():
        assert raw['protocol'][key] == value, key
    assert raw['protocol']['affinity'] == '7c'
    assert raw['protocol']['cooldown_seconds'] == 8
    for name, expected in raw['collector_files_sha256'].items():
        assert hashlib.sha256((COLLECTOR / name).read_bytes()).hexdigest() == expected, name
    assert hashlib.sha256((COLLECTOR / 'tools/version-bench/main.cpp').read_bytes()).hexdigest() == raw['harness_sha256']
    planned = [(r + 1, scenario, VERSIONS['AB'.index(letter)])
               for r, order in enumerate(suite['protocol']['version_order'])
               for scenario in scenarios for letter in order]
    observed = [(r['round'], r['scenario'], r['version']) for r in raw['runs']]
    assert observed == planned[:len(observed)], 'missing, duplicated or reordered processes'
    assert len(observed) <= len(planned)
    verdict = subprocess.run([sys.executable, str(COLLECTOR / 'tools/version-bench/check.py'),
                              str(path), str(path), '--baseline-version', VERSIONS[0],
                              '--candidate-version', VERSIONS[1]], capture_output=True, text=True)
    assert verdict.returncode == 2 and 'exploratory' in verdict.stdout + verdict.stderr
    rows, by_key = [], {}
    for scenario, spec in scenarios.items():
        for version in VERSIONS:
            runs = [r for r in raw['runs'] if (r['scenario'], r['version']) == (scenario, version)]
            successful = [r for r in runs if r['returncode'] == 0 and not r.get('error') and len(r['samples']) == 3]
            row = {'scenario': scenario, 'version': version, 'successful_processes': len(successful),
                   'complete': False, 'errors': [r['error'] for r in runs if r.get('error')]}
            try:
                # Disable only the spread rejection for descriptive medians;
                # retain its exact values and 10% flags below. Never call this PASS.
                metrics, temperatures, caps, hashes = check.measurements(raw, version, scenario, float('inf'))
                if spec['stable_hash'] and len(set(hashes)) != 1:
                    raise check.EvidenceError(f'{version}/{scenario}: output unstable within a version')
            except check.EvidenceError as error:
                row['validation_error'] = str(error)
            else:
                values = {'cold_ms': [r['samples'][0]['elapsed_ms'] for r in runs],
                          'warm_ms': [median(s['elapsed_ms'] for s in r['samples'][1:]) for r in runs],
                          'peak_rss_mib': [r['samples'][0]['peak_rss_mib'] for r in runs]}
                row.update(metrics)
                row.update(complete=True, raw_values=values,
                           spread_pct={k: 100 * (max(v) - min(v)) / median(v) for k, v in values.items()},
                           starting_temperatures_c=temperatures, starting_caps_khz=caps,
                           unique_output_hashes=sorted(set(hashes)))
                row['cold_inputs_per_second'] = spec['sentences'] * 1000 / metrics['cold_ms']
                row['warm_inputs_per_second'] = spec['sentences'] * 1000 / metrics['warm_ms']
            rows.append(row)
            by_key[scenario, version] = row

    def pair(old_scenario, new_scenario):
        old, new = by_key[old_scenario, VERSIONS[0]], by_key[new_scenario, VERSIONS[1]]
        if not old['complete'] or not new['complete']:
            return {'old_scenario': old_scenario, 'new_scenario': new_scenario, 'complete': False}
        caps = old['starting_caps_khz'] + new['starting_caps_khz']
        temperatures = old['starting_temperatures_c'] + new['starting_temperatures_c']
        cold_ratio = old['cold_ms'] / new['cold_ms']
        return {'old_scenario': old_scenario, 'new_scenario': new_scenario, 'complete': True,
                'old_cold_inputs_per_second': old['cold_inputs_per_second'],
                'new_cold_inputs_per_second': new['cold_inputs_per_second'],
                'cold_speedup': cold_ratio, 'cold_speed_change_pct': (cold_ratio - 1) * 100,
                'warm_speedup': old['warm_ms'] / new['warm_ms'],
                'old_peak_rss_mib': old['peak_rss_mib'], 'new_peak_rss_mib': new['peak_rss_mib'],
                'rss_reduction_pct': (1 - new['peak_rss_mib'] / old['peak_rss_mib']) * 100,
                'per_round_cold_speedups': [a / b for a, b in zip(old['raw_values']['cold_ms'], new['raw_values']['cold_ms'])],
                'all_starting_caps_equal': all(c == caps[0] for c in caps),
                'starting_temperature_range_c': [min(temperatures), max(temperatures)],
                'spreads_above_10pct': [{'version': r['version'], 'scenario': r['scenario'], 'metric': k, 'spread_pct': v}
                                        for r in (old, new) for k, v in r['spread_pct'].items() if v > 10],
                'stable_output_hashes_equal': (old['unique_output_hashes'] == new['unique_output_hashes']
                                              and len(old['unique_output_hashes']) == 1)
                    if scenarios[old_scenario]['stable_hash'] and scenarios[new_scenario]['stable_hash'] else None}

    starts = [r['before'] for r in raw['runs']]
    return {'measured_at': raw['measured_at'], 'accepted_as_strict_baseline': False,
            'strict_checker_exit_code': verdict.returncode,
            'strict_checker_diagnostic': (verdict.stdout + verdict.stderr).strip(),
            'collection_complete': observed == planned and sum(r['successful_processes'] for r in rows) == len(planned),
            'complete': observed == planned and all(r['complete'] for r in rows),
            'recorded_processes': len(observed), 'planned_processes': len(planned),
            'successful_processes': sum(r['successful_processes'] for r in rows),
            'starting_temperature_range_c': [min(r['battery_temp_c'] for r in starts), max(r['battery_temp_c'] for r in starts)] if starts else None,
            'starting_frequency_caps_khz': {cpu: sorted({int(r['max_freq_khz'][cpu]) for r in starts}) for cpu in ('2', '7')},
            'rows': rows, 'same_config': [pair(s, s) for s in scenarios],
            'defaults': [pair('enzh_w1', 'enzh_b512p'), pair('pivot_w1', 'pivot_b512p')]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', nargs='?', type=Path, default=HERE / 'native-results.json')
    args = parser.parse_args()
    print(json.dumps(analyze(args.report), ensure_ascii=False, indent=2))
