import copy
import json
from pathlib import Path
import tempfile
import unittest
from check import summarize
from run import SCENARIOS, sha256
from bench_device import FOREGROUND_CHECKS, PACKAGE


class ReportValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        source = self.directory / "source-files.json"
        source.write_text('{}\n')
        self.report = {"suite_id": "android-app-v1", "mode": "measure", "status": "complete", "apk_sha256": "apk",
                       "source_files_sha256": sha256(source.read_bytes()), "device": {"fingerprint": "device"},
                       "corpus_sha256": {"enzh": "en", "jazh": "ja"},
                       "model_files_sha256_before": ["model"], "model_files_sha256_after": ["model"],
                       "protocol": {"rounds": 3, "passes": 3, "gate_khz": 2630400,
                                    "order": "forward/reverse/forward", "cooldown_seconds": 15}, "runs": []}
        outputs = [f"translation {i}" for i in range(200)]
        output_hash = sha256(("\n".join(outputs) + "\n").encode())
        for round_index in range(1, 4):
            for direction, engine, threads in SCENARIOS:
                run_id = f"r{round_index}_{direction}_{engine}_{threads}"
                count = None if engine == "mlkit" else threads
                data = {"suite_id": "android-app-v1", "mode": "measure", "run_id": run_id, "status": "complete",
                        "direction": direction, "engine": engine, "threads": count, "input_count": 200,
                        "corpus_sha256": self.report["corpus_sha256"][direction], "device_fingerprint": "device",
                        "passes": [{"pass": p, "elapsed_ms": 1000, "outputs": outputs, "output_sha256": output_hash,
                                    "metrics": {"peakPssMb": 100, "curve": [{"pssKb": 102400}]}} for p in range(3)]}
                filename = run_id + ".json"
                (self.directory / filename).write_text(json.dumps(data))
                self.report["runs"].append({"round": round_index, "direction": direction, "engine": engine,
                    "threads": count, "run_id": run_id, "status": "complete", "result_file": filename,
                    "before": {"battery_temp_c": 30, "max_freq_khz": {"2": 2630400, "7": 2630400}}})

    def check(self):
        (self.directory / "results.json").write_text(json.dumps(self.report))
        return summarize(self.directory)

    def test_complete_report(self):
        result = self.check()
        self.assertTrue(result["accepted"], result["errors"])
        self.assertEqual(result["scenarios"][0]["cold_inputs_per_second"], 200)

    def test_quality_mode_never_accepts_timings(self):
        self.report["mode"] = "quality"
        self.assertFalse(self.check()["accepted"])

    def test_missing_and_duplicate_cells(self):
        self.report["runs"][-1] = copy.deepcopy(self.report["runs"][0])
        self.assertFalse(self.check()["accepted"])

    def test_temperature_span(self):
        self.report["runs"][-1]["before"]["battery_temp_c"] = 34
        self.assertFalse(self.check()["accepted"])

    def test_frequency_gate(self):
        self.report["runs"][0]["before"]["max_freq_khz"]["7"] = 2169600
        self.assertFalse(self.check()["accepted"])

    def test_changed_model(self):
        self.report["model_files_sha256_after"] = ["other"]
        self.assertFalse(self.check()["accepted"])

    def test_missing_source_manifest(self):
        (self.directory / "source-files.json").unlink()
        self.assertFalse(self.check()["accepted"])

    def test_noisy_cell(self):
        path = self.directory / self.report["runs"][0]["result_file"]
        data = json.loads(path.read_text())
        data["passes"][0]["elapsed_ms"] = 1300
        path.write_text(json.dumps(data))
        self.assertFalse(self.check()["accepted"])

    def test_new_reports_require_both_foreground_snapshots(self):
        self.report['protocol']['foreground_checks'] = FOREGROUND_CHECKS
        self.assertFalse(self.check()['accepted'])
        state = {'wakefulness': 'Awake', 'lockscreen': False, 'focused_package': PACKAGE}
        for row in self.report['runs']:
            row['before']['foreground'] = dict(state)
            row['after'] = {'foreground': dict(state)}
        self.assertTrue(self.check()['accepted'])
        self.report['runs'][0]['after']['foreground']['lockscreen'] = True
        self.assertFalse(self.check()['accepted'])


if __name__ == "__main__":
    unittest.main()
