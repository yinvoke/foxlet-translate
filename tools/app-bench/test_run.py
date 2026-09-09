import copy
import unittest
from run import SCENARIOS, decode_snapshot, sha256, validate_result


class SnapshotTest(unittest.TestCase):
    def test_complete_utf8(self):
        self.assertEqual(decode_snapshot('{"text":"译文"}'.encode()), {"text": "译文"})

    def test_partial_json(self):
        self.assertIsNone(decode_snapshot(b'{"text":'))

    def test_split_multibyte_character(self):
        self.assertIsNone(decode_snapshot('{"text":"译'.encode()[:-1]))

    def test_invalid_bytes_are_not_replaced(self):
        self.assertIsNone(decode_snapshot(b'{"text":"\xe4x"}'))


class ResultValidationTest(unittest.TestCase):
    def setUp(self):
        outputs = [f"translation {i}" for i in range(200)]
        self.data = {"suite_id": "android-app-v1", "run_id": "test", "mode": "measure", "direction": "enzh",
                     "engine": "bergamot", "threads": 1, "input_count": 200, "corpus_sha256": "corpus",
                     "status": "complete", "passes": [{"pass": p, "elapsed_ms": 1000, "outputs": outputs,
                         "output_sha256": sha256(("\n".join(outputs) + "\n").encode()),
                         "metrics": {"peakPssMb": 100, "curve": [{"pssKb": 102400}]}} for p in range(3)]}

    def validate(self, data=None):
        validate_result(self.data if data is None else data, "test", "enzh", "bergamot", 1, "corpus")

    def test_valid(self):
        self.validate()

    def test_fixed_scenarios(self):
        self.assertEqual(len(SCENARIOS), 8)
        self.assertEqual(len(set(SCENARIOS)), 8)
        self.assertEqual([t for _, e, t in SCENARIOS if e == "mlkit"], [1, 1])

    def test_reject_changed_identity(self):
        for key, value in (("suite_id", "v2"), ("run_id", "stale"), ("corpus_sha256", "changed"),
                           ("input_count", 150), ("status", "failed"), ("mode", "prepare")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                data = copy.deepcopy(self.data)
                data[key] = value
                self.validate(data)

    def test_reject_incomplete_or_duplicate_passes(self):
        for passes in (self.data["passes"][:2], [self.data["passes"][0]] * 3):
            with self.assertRaises(ValueError):
                self.validate(dict(self.data, passes=passes))

    def test_reject_bad_time(self):
        for value in (0, -1, float("nan"), float("inf")):
            with self.subTest(value=value), self.assertRaises(ValueError):
                data = copy.deepcopy(self.data)
                data["passes"][0]["elapsed_ms"] = value
                self.validate(data)

    def test_reject_changed_output(self):
        self.data["passes"][0]["outputs"][0] = "changed"
        with self.assertRaises(ValueError):
            self.validate()

    def test_reject_missing_pss(self):
        self.data["passes"][0]["metrics"]["curve"] = []
        with self.assertRaises(ValueError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
