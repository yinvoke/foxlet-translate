"""SYNTHETIC regression fixtures, never benchmark evidence or device operations.

Run with: python3 -B <this-directory>/test_verify.py -v
Only metadata comes from native-results.json. Every process, sensor value, hash
and timing is synthetic.
All report substitutions and checker execution happen in memory; no files are
created or modified, and no subprocess is launched.
"""
import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import verify


SYNTHETIC_REPORT = verify.HERE / '__synthetic_test_only__.json'
MEDIAN_FIELDS = {
    'cold_ms', 'warm_ms', 'peak_rss_mib', 'raw_values', 'spread_pct',
    'cold_inputs_per_second', 'warm_inputs_per_second',
}


class VerifyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        archived = verify.HERE / 'native-results.json'
        metadata = json.loads(archived.read_text())
        # Do not reuse any real process results, temperatures or timing values.
        cls.scaffold = {key: metadata[key] for key in (
            'suite_id', 'suite_sha256', 'scenarios', 'versions', 'report_kind',
            'protocol', 'collector_files_sha256', 'harness_sha256',
            'build_settings', 'binary_sha256', 'build_manifests', 'device',
            'config_yaml', 'corpus_sha256', 'model_files_sha256',
        )}
        cls.suite, cls.scenarios, _ = verify.load_suite(metadata['suite_id'])

    def fixture(self):
        raw = copy.deepcopy(self.scaffold)
        raw.update(measured_at='SYNTHETIC TEST FIXTURE — NOT A MEASUREMENT', runs=[])
        for round_index, order in enumerate(self.suite['protocol']['version_order']):
            for scenario, spec in self.scenarios.items():
                for letter in order:
                    version = verify.VERSIONS['AB'.index(letter)]
                    factor = 1 if letter == 'A' else 0.5
                    foreground = {
                        'wakefulness': 'Awake', 'lockscreen': False,
                        'focused_package': 'io.github.yinvoker.bergamot.bench',
                    }
                    before = {
                        'gate_wait_s': 0, 'battery_temp_c': 30,
                        'max_freq_khz': {'2': '2000000', '7': '2000000'},
                        'foreground': foreground,
                    }
                    raw['runs'].append({
                        'round': round_index + 1, 'scenario': scenario,
                        'version': version, 'returncode': 0,
                        'before': before, 'after': copy.deepcopy(before),
                        'samples': [{
                            'pass': p, 'workers': spec['workers'],
                            'sentences': spec['sentences'],
                            'elapsed_ms': (elapsed + 20 * round_index) * factor,
                            'peak_rss_mib': 200 + 2 * round_index,
                            'output_bytes': 100,
                            'output_hash': 'SYNTHETIC-STABLE-OUTPUT',
                        } for p, elapsed in enumerate((1000, 700, 900))],
                    })
        return raw

    def analyze(self, raw):
        original_read = Path.read_text
        payload = json.dumps(raw)

        def read(path, *args, **kwargs):
            if path == SYNTHETIC_REPORT:
                return payload
            return original_read(path, *args, **kwargs)

        def run_checker(command, **kwargs):
            self.assertEqual(command, [
                sys.executable, str(verify.COLLECTOR / 'tools/version-bench/check.py'),
                str(SYNTHETIC_REPORT), str(SYNTHETIC_REPORT),
                '--baseline-version', verify.VERSIONS[0],
                '--candidate-version', verify.VERSIONS[1],
            ])
            stdout, stderr = io.StringIO(), io.StringIO()
            # Execute the actual archived CLI against the same synthetic JSON;
            # do not hard-code a successful exploratory rejection response.
            with patch.object(sys, 'argv', command[1:]), \
                    contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = verify.check.main()
            return subprocess.CompletedProcess(command, code, stdout.getvalue(), stderr.getvalue())

        with patch.object(Path, 'read_text', read), \
                patch.object(verify.subprocess, 'run', side_effect=run_checker):
            return verify.analyze(SYNTHETIC_REPORT)

    def assert_no_medians(self, row):
        self.assertFalse(row['complete'])
        self.assertFalse(MEDIAN_FIELDS.intersection(row))
        self.assertIn('validation_error', row)

    def assert_invalid_cell(self, result, scenario, version, diagnostic):
        self.assertFalse(result['complete'])
        row = next(r for r in result['rows']
                   if (r['scenario'], r['version']) == (scenario, version))
        self.assert_no_medians(row)
        self.assertIn(diagnostic, row['validation_error'])
        pair = next(p for p in result['same_config'] if p['old_scenario'] == scenario)
        self.assertFalse(pair['complete'])
        self.assertNotIn('cold_speedup', pair)
        self.assertNotIn('warm_speedup', pair)

    def test_valid_complete(self):
        result = self.analyze(self.fixture())
        self.assertTrue(result['complete'])
        self.assertTrue(result['collection_complete'])
        self.assertEqual(result['recorded_processes'], 48)
        self.assertEqual(result['planned_processes'], 48)
        self.assertEqual(result['successful_processes'], 48)
        self.assertFalse(result['accepted_as_strict_baseline'])
        self.assertEqual(result['strict_checker_exit_code'], 2)
        self.assertIn('exploratory', result['strict_checker_diagnostic'])
        self.assertEqual(len(result['rows']), 16)
        for row in result['rows']:
            factor = 1 if row['version'] == verify.VERSIONS[0] else 0.5
            self.assertTrue(row['complete'])
            self.assertEqual(row['cold_ms'], 1020 * factor)
            self.assertEqual(row['warm_ms'], 820 * factor)
            self.assertEqual(row['peak_rss_mib'], 202)
        for pair in result['same_config'] + result['defaults']:
            self.assertTrue(pair['complete'])
            self.assertEqual(pair['cold_speedup'], 2)
            self.assertEqual(pair['warm_speedup'], 2)

    def test_incomplete_no_medians(self):
        for count in (0, 16, 47):
            with self.subTest(processes=count):
                raw = self.fixture()
                raw['runs'] = raw['runs'][:count]
                result = self.analyze(raw)
                self.assertFalse(result['complete'])
                for row in result['rows']:
                    if row['successful_processes'] < 3:
                        self.assert_no_medians(row)
        raw = self.fixture()
        run = raw['runs'][0]
        run['samples'].pop()
        self.assert_invalid_cell(self.analyze(raw), run['scenario'], run['version'], 'incomplete passes')

    def test_unstable_blocking_no_medians(self):
        for scenario, spec in self.scenarios.items():
            if not spec['stable_hash']:
                continue
            for version in verify.VERSIONS:
                for variation in ('within-process', 'between-rounds'):
                    with self.subTest(scenario=scenario, version=version, variation=variation):
                        raw = self.fixture()
                        run = next(r for r in raw['runs']
                                   if (r['scenario'], r['version']) == (scenario, version))
                        changed = run['samples'][1:2] if variation == 'within-process' else run['samples']
                        for sample in changed:
                            sample['output_hash'] = 'SYNTHETIC-DIFFERENT-OUTPUT'
                        self.assert_invalid_cell(self.analyze(raw), scenario, version,
                                                 'output unstable within a version')

    def test_missing_marker_rejected(self):
        raw = self.fixture()
        del raw['protocol']['foreground_checks']
        with self.assertRaisesRegex(KeyError, 'foreground_checks'):
            self.analyze(raw)
        raw['protocol']['foreground_checks'] = 'SYNTHETIC-UNKNOWN-PROTOCOL'
        with self.assertRaises(AssertionError):
            self.analyze(raw)

    def test_missing_foreground_rejected(self):
        for version in verify.VERSIONS:
            for side in ('before', 'after'):
                with self.subTest(version=version, side=side):
                    raw = self.fixture()
                    run = next(r for r in raw['runs'] if r['version'] == version)
                    del run[side]['foreground']
                    self.assert_invalid_cell(self.analyze(raw), run['scenario'], version,
                                             'foreground missing or not ready')


if __name__ == '__main__':
    unittest.main()
