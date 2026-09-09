"""Synthetic fixtures exercise the gate, not device performance."""
from copy import deepcopy
from pathlib import Path
import unittest
import check
from suite import load_suite


def report():
    result = {
        'device': {'id_sha256': 'device', 'fingerprint': 'os', 'model': 'phone',
                   'android': '16', 'soc': 'arm', 'i8mm': True},
        'build_settings': {'ndk': '29', 'flags': ['Release']},
        'harness_sha256': 'harness', 'config_yaml': {'en': 'mini-batch-words: 512'},
        'corpus_sha256': {'eng': 'corpus'}, 'model_files_sha256': {'en': 'model'},
        'versions': {'test': 'commit'}, 'binary_sha256': {'test': 'binary'},
        'build_manifests': {'test': {'commit': 'commit', 'binary_sha256': 'binary',
                                   'harness_sha256': 'harness', 'source_native_sha256': 'source',
                                   'build_settings': {'ndk': '29', 'flags': ['Release']}}},
        'scenarios': {'enzh': {'workers': 0, 'sentences': 200, 'stable_hash': True}},
        'protocol': {'rounds': 3, 'passes_per_process': 3},
        'runs': [{'version': 'test', 'scenario': 'enzh', 'round': r, 'returncode': 0,
                  'before': {'gate_wait_s': 0, 'battery_temp_c': 30,
                             'max_freq_khz': {'2': '2630400'}},
                  'samples': [{'pass': p, 'workers': 0, 'sentences': 200,
                               'elapsed_ms': 1000 if p == 0 else 800,
                               'peak_rss_mib': 200, 'output_bytes': 500,
                               'output_hash': 'abc'} for p in range(3)]}
                 for r in range(1, 4)]}
    suite, scenarios, digest = load_suite()
    result.update(suite_id=suite['id'], suite_sha256=digest,
                  report_kind='full', scenarios=scenarios, protocol=suite['protocol'])
    template = result['runs']
    result['runs'] = []
    for name, spec in scenarios.items():
        rows = deepcopy(template)
        for row in rows:
            row['scenario'] = name
            for sample in row['samples']:
                sample['workers'] = spec['workers']
        result['runs'].extend(rows)
    return result


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.base = report()
        self.new = deepcopy(self.base)

    def compare(self):
        return check.compare(self.base, self.new, 'test', 'test')

    def test_identical_passes(self):
        lines, failures = self.compare()
        self.assertEqual(len(lines), 24)
        self.assertEqual(failures, [])
        self.assertIn('cold_speed', lines[0])
        self.assertIn('200.00 -> 200.00 inputs/s (1.00x, +0.0%)', lines[0])
        self.assertIn('raw cold_ms: 1000.000 -> 1000.000 ms', lines[0])

    def test_halving_time_doubles_speed(self):
        self.assertEqual(check.speed_metrics(1000, 500, 200), {
            'before_inputs_per_second': 200,
            'after_inputs_per_second': 400,
            'speedup': 2,
            'speed_change_pct': 100,
        })

    def test_speed_examples_are_not_latency_reductions(self):
        direct = check.speed_metrics(7207.989, 4247.333, 200)
        pivot = check.speed_metrics(16076.976, 8660.776, 200)
        self.assertEqual(round(direct['speed_change_pct'], 1), 69.7)
        self.assertEqual(round(pivot['speed_change_pct'], 1), 85.6)

    def test_speed_display_keeps_original_latency_threshold(self):
        for row in self.new['runs']:
            for sample in row['samples']:
                sample['elapsed_ms'] *= 1.11
        # Time +11% is only speed -9.91%, but must still fail the +10% time gate.
        lines, failures = self.compare()
        self.assertEqual(len(failures), 16)
        self.assertIn('-9.9%', lines[0])

    def test_invalid_speed_inputs(self):
        for values in ((0, 1, 200), (1, 0, 200), (1, 1, 0), (float('nan'), 1, 200)):
            with self.subTest(values=values), self.assertRaises(check.EvidenceError):
                check.speed_metrics(*values)

    def test_latency_regression(self):
        for row in self.new['runs']:
            for sample in row['samples']:
                sample['elapsed_ms'] *= 1.2
        self.assertEqual(len(self.compare()[1]), 16)

    def test_memory_regression(self):
        for row in self.new['runs']:
            for sample in row['samples']:
                sample['peak_rss_mib'] *= 1.2
        self.assertEqual(len(self.compare()[1]), 8)

    def test_incompatible_metadata(self):
        for field in ('suite_id', 'suite_sha256', 'report_kind', 'device', 'build_settings', 'harness_sha256', 'config_yaml',
                      'corpus_sha256', 'model_files_sha256', 'protocol', 'scenarios'):
            with self.subTest(field=field):
                candidate = deepcopy(self.base)
                candidate[field] = 'changed'
                with self.assertRaises(check.EvidenceError):
                    check.compare(self.base, candidate, 'test', 'test')

    def test_missing_fingerprints(self):
        del self.base['model_files_sha256']
        del self.new['model_files_sha256']
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_manifest_mismatch(self):
        self.new['build_manifests']['test']['binary_sha256'] = 'different'
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_no_rounds(self):
        self.new['runs'] = []
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_duplicate_round(self):
        self.new['runs'][2]['round'] = 2
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_failed_process(self):
        self.new['runs'][0]['returncode'] = 134
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_partial_process(self):
        self.new['runs'][0]['samples'].pop()
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_gate_timeout(self):
        self.new['runs'][0]['before']['gate_wait_s'] = -1
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_temperature_mismatch(self):
        self.new['runs'][0]['before']['battery_temp_c'] = 40
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_frequency_mismatch(self):
        self.new['runs'][0]['before']['max_freq_khz']['2'] = '1800000'
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_noisy_samples(self):
        self.new['runs'][0]['samples'][0]['elapsed_ms'] *= 1.5
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_invalid_numbers(self):
        for value in (float('nan'), float('inf'), -1, 0, '1000', True):
            with self.subTest(value=value):
                self.new['runs'][0]['samples'][0]['elapsed_ms'] = value
                with self.assertRaises(check.EvidenceError):
                    self.compare()

    def test_changed_hash(self):
        for row in self.new['runs']:
            for sample in row['samples']:
                sample['output_hash'] = 'def'
        self.assertIn('hash changed', self.compare()[1][0])

    def test_unstable_hash(self):
        row = next(r for r in self.new['runs'] if r['scenario'] == 'enzh_b512p')
        row['samples'][0]['output_hash'] = 'def'
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_both_versions_cannot_drop_same_dimension(self):
        for raw in (self.base, self.new):
            del raw['scenarios']['pivot_b512p']
            raw['runs'] = [r for r in raw['runs'] if r['scenario'] != 'pivot_b512p']
        with self.assertRaisesRegex(check.EvidenceError, 'every fixed suite scenario'):
            self.compare()

    def test_exploratory_runs_cannot_pass_release_gate(self):
        self.base['report_kind'] = self.new['report_kind'] = 'exploratory'
        with self.assertRaisesRegex(check.EvidenceError, 'exploratory'):
            self.compare()

    def test_same_id_cannot_change_definition(self):
        self.base['suite_sha256'] = self.new['suite_sha256'] = 'changed'
        with self.assertRaisesRegex(check.EvidenceError, 'suite definition changed'):
            self.compare()

    def test_fixed_protocol_cannot_change_for_both_versions(self):
        self.base['protocol']['cache_size'] = self.new['protocol']['cache_size'] = 20000
        with self.assertRaisesRegex(check.EvidenceError, 'fixed suite protocol changed'):
            self.compare()

    def test_existing_suite_scenarios_have_stable_order(self):
        suite, scenarios, _ = load_suite()
        self.assertEqual(list(scenarios), ['enzh_w1', 'enzh_w2', 'enzh_w4', 'pivot_w1',
                                         'enzh_b512p', 'pivot_b512p', 'enzh_w2_512p', 'pivot_w2_512p'])
        self.assertEqual(list(suite['metrics']), ['cold_ms', 'warm_ms', 'peak_rss_mib'])

    def test_v1_definition_is_frozen(self):
        # Adding dimensions belongs in a new suite file. Do not refresh this
        # digest to silently redefine an existing historical measurement axis.
        self.assertEqual(load_suite('android-native-v1')[2],
                         '01fd8553d31a4ced2d1a17f19c92ab3b5ee7d6893b40a61744ac9446c2367e35')

    def test_workload_change(self):
        self.new['runs'][0]['samples'][0]['sentences'] = 150
        with self.assertRaises(check.EvidenceError):
            self.compare()

    def test_archives_are_not_silently_accepted(self):
        import json
        root = Path(__file__).resolve().parents[2]
        for path in (root / 'benchmarks').glob('v*/results*.json'):
            with self.subTest(path=path):
                raw = json.loads(path.read_text())
                version = next(iter(raw['versions']))
                with self.assertRaises(check.EvidenceError):
                    check.compare(raw, raw, version, version)


if __name__ == '__main__':
    unittest.main()
