"""Exercise report collection with a fake adb; never contacts a real device."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check


SCRIPT = Path(__file__).with_name('run-pair.py')
ROOT = SCRIPT.resolve().parents[2]


class RunnerTest(unittest.TestCase):
    def run_fake_device(self, directory, mode='success', scenarios=None, skip_frequency_gate=False):
        root = Path(directory)
        for version in ('old', 'new'):
            (root / version).mkdir()
            binary = root / f'smoke-{version}'
            binary.write_bytes(b'fake native binary')
            manifest = {'commit': version, 'source_native_sha256': 'source',
                        'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                        'harness_sha256': 'common-harness', 'build_settings': {'ndk': 'test'}}
            (root / version / 'build-manifest.json').write_text(json.dumps(manifest))
        output = root / 'report.json'

        def fake_adb(command, **kwargs):
            if 'get-serialno' in command:
                return 'fake-device'
            if 'push' in command:
                return 'pushed'
            shell = command[-1]
            if shell == 'getprop ro.product.model':
                return '23127PN0CC' if skip_frequency_gate else 'Mi 10'
            if shell.startswith('getprop '):
                return 'test-os'
            if 'scaling_max_freq' in shell:
                return '2000000'
            if shell == 'dumpsys battery':
                return 'temperature: 300'
            if shell == 'dumpsys power':
                return 'mWakefulness=Awake'
            if shell == 'dumpsys window':
                locked = mode == 'locked'
                return ('mDreamingLockscreen=' + str(locked).lower() + '\n'
                        'mCurrentFocus=Window{abc u0 io.github.yinvoker.bergamot.bench/.MainActivity}')
            if 'board_sensor_temp' in shell:
                return '30000'
            if shell.startswith('sha256sum'):
                return 'a' * 64 + '  file'
            if shell.startswith('cat ') and shell.endswith('.txt'):
                lang = 'jpn' if shell.endswith('/jpn.txt') else 'eng'
                return (ROOT / f'sample/src/main/assets/bench/{lang}.txt').read_text()
            if shell.startswith('cat ') and shell.endswith('.yml'):
                ja = 'jaen' in shell
                lang = 'ja' if ja else 'en'
                paths = [f'/models/{lang}/{name}' for name in ('model', 'vocab', 'lex')]
                if not ja:
                    paths.append('/models/en/trgvocab')
                config = '\n'.join('  - ' + path for path in paths)
                config += '\nmini-batch-words: ' + ('512' if '512' in shell else '1024')
                if '512on' in shell:
                    config += '\nssplit-prefix-file: /models/prefix.en'
                return config
            if shell.startswith(('mkdir ', 'chmod ', 'cp ')):
                return ''
            raise AssertionError(f'unexpected fake adb command: {command}')

        def fake_process(command, **kwargs):
            if mode == 'timeout':
                raise subprocess.TimeoutExpired(command, 300)
            workers = int(shlex.split(command[-1])[3])
            samples = [{'pass': p, 'workers': workers, 'sentences': 200, 'elapsed_ms': 1000,
                        'peak_rss_mib': 200, 'rss_mib': 180, 'output_bytes': 100,
                        'output_hash': 'abc'} for p in range(3)]
            stdout = '\n'.join(json.dumps(s) for s in samples)
            if mode == 'malformed':
                stdout = '{not valid json'
            return subprocess.CompletedProcess(command, 134 if mode == 'crash' else 0, stdout, '')

        argv = [str(SCRIPT), 'mi14' if skip_frequency_gate else 'mi10', str(output), str(root), '--adb', 'fake-adb',
                '--serial', 'fake-device', '--baseline', 'old', '--candidate', 'new']
        if scenarios:
            argv += ['--scenarios', *scenarios]
        if skip_frequency_gate:
            argv += ['--skip-frequency-gate']
        with patch('sys.argv', argv), patch('subprocess.check_output', side_effect=fake_adb), \
                patch('subprocess.run', side_effect=fake_process), patch('time.sleep'), \
                contextlib.redirect_stdout(io.StringIO()):
            try:
                runpy.run_path(str(SCRIPT), run_name='__main__')
            except SystemExit:
                if mode not in ('timeout', 'locked'):
                    raise
        return json.loads(output.read_text())

    def test_complete_report_passes_checker(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp)
        self.assertEqual(len(raw['runs']), 48)
        self.assertEqual(raw['report_kind'], 'full')
        self.assertEqual(check.compare(raw, raw, 'old', 'new')[1], [])

    def test_subset_is_explicitly_exploratory(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp, scenarios=['enzh_b512p'])
        self.assertEqual(len(raw['runs']), 6)
        self.assertEqual(raw['report_kind'], 'exploratory')
        with self.assertRaisesRegex(check.EvidenceError, 'exploratory'):
            check.compare(raw, raw, 'old', 'new')

    def test_frequency_bypass_keeps_all_rows_and_conditions_but_is_exploratory(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp, skip_frequency_gate=True)
        self.assertEqual(len(raw['runs']), 48)
        self.assertEqual(raw['report_kind'], 'exploratory')
        self.assertIsNone(raw['protocol']['gate_khz'])
        self.assertEqual(raw['protocol']['reference_gate_khz'], 2630400)
        self.assertEqual(raw['protocol']['frequency_gate_mode'], 'skipped-explicitly')
        self.assertEqual({r['version'] for r in raw['runs']}, {'old', 'new'})
        self.assertTrue(all(r['before']['gate_wait_s'] == 0 for r in raw['runs']))
        self.assertTrue(all(r['before']['max_freq_khz']['7'] == '2000000' for r in raw['runs']))
        with self.assertRaisesRegex(check.EvidenceError, 'exploratory'):
            check.compare(raw, raw, 'old', 'new')

    def test_frequency_bypass_does_not_bypass_foreground_check(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp, 'locked', skip_frequency_gate=True)
        self.assertEqual(len(raw['runs']), 1)
        self.assertEqual(raw['runs'][0]['samples'], [])
        self.assertEqual(raw['runs'][0]['before']['gate_wait_s'], -2)

    def test_crash_and_malformed_output_retained(self):
        for mode in ('crash', 'malformed', 'timeout'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                raw = self.run_fake_device(temp, mode)
                self.assertTrue(raw['runs'][0]['error'])
                with self.assertRaises(check.EvidenceError):
                    check.compare(raw, raw, 'old', 'new')

    def test_locked_device_never_starts_native_process(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp, 'locked')
        self.assertEqual(len(raw['runs']), 1)
        self.assertEqual(raw['runs'][0]['samples'], [])
        self.assertEqual(raw['runs'][0]['before']['gate_wait_s'], -2)
        self.assertIn('not awake/unlocked', raw['runs'][0]['error'])

    def test_new_report_cannot_omit_foreground_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = self.run_fake_device(temp)
        del raw['runs'][0]['after']['foreground']
        with self.assertRaisesRegex(check.EvidenceError, 'foreground missing'):
            check.compare(raw, raw, 'old', 'new')


if __name__ == '__main__':
    unittest.main()
