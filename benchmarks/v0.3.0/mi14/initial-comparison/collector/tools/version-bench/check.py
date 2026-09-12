#!/usr/bin/env python3
"""Compare native benchmark reports; exit 0=pass, 1=regression, 2=invalid evidence.

Only comparable, complete measurements may pass. Historical reports without
input/build/device fingerprints remain useful documentation, not a CI gate.
Uses the standard library so CI can test the checker without an Android device.
"""
import argparse
import json
import math
from pathlib import Path
from statistics import median
import sys
from suite import load_suite

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bench_device import FOREGROUND_CHECKS, foreground_ready

class EvidenceError(ValueError):
    """Missing, incompatible, or noisy measurements require a rerun."""


def positive(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError(f"not a numeric measurement: {value!r}")
    if not math.isfinite(value) or value <= 0:
        raise EvidenceError(f"not a positive finite measurement: {value!r}")
    return value


def required(report, key):
    value = report.get(key)
    if value is None or value == {} or value == "":
        raise EvidenceError(f"missing {key}; historical reports may need a fresh baseline")
    return value


def speed_metrics(before_ms, after_ms, input_count):
    """Display fixed-workload speed without changing the measured time window.

    Count source input entries, not splitter sentences or output tokens. A
    shorter elapsed time increases speed by the reciprocal ratio, so a 50%
    latency reduction means a 100% speed gain, not a 50% speed gain.
    """
    before_ms, after_ms, input_count = map(positive, (before_ms, after_ms, input_count))
    ratio = before_ms / after_ms
    return {
        'before_inputs_per_second': input_count * 1000 / before_ms,
        'after_inputs_per_second': input_count * 1000 / after_ms,
        'speedup': ratio,
        'speed_change_pct': (ratio - 1) * 100,
    }


def compatible(base, candidate):
    # Versions/binary hashes are deliberately NOT equal: they are the variable
    # under test. Inputs, invocation, hardware, and toolchain must be equal.
    for key in ("suite_id", "suite_sha256", "report_kind", "device", "build_settings", "harness_sha256", "config_yaml",
                "corpus_sha256", "model_files_sha256", "protocol", "scenarios"):
        if required(base, key) != required(candidate, key):
            raise EvidenceError(f"incompatible {key}")
    if base['report_kind'] != 'full':
        raise EvidenceError('exploratory runs cannot be a complete version baseline')
    suite, scenarios, digest = load_suite(base['suite_id'])
    if set(suite['metrics']) != {'cold_ms', 'warm_ms', 'peak_rss_mib'}:
        raise EvidenceError('unsupported suite metrics; extend collection and checking before use')
    if base['suite_sha256'] != digest:
        raise EvidenceError('suite definition changed; use a new suite id and rerun both versions')
    if base['scenarios'] != scenarios:
        raise EvidenceError('full version baseline must include every fixed suite scenario')
    for key, value in suite['protocol'].items():
        if base['protocol'].get(key) != value:
            raise EvidenceError(f'fixed suite protocol changed: {key}')
    for key in ("id_sha256", "fingerprint", "model", "android", "soc", "i8mm"):
        if key not in base["device"] or base["device"][key] in (None, ""):
            raise EvidenceError(f"missing device.{key}")
    if base["protocol"].get("rounds", 0) < 3:
        raise EvidenceError("at least three independent processes are required")
    if base["protocol"].get("passes_per_process") != 3:
        raise EvidenceError("expected first pass plus two warm passes")


def measurements(report, version, scenario, max_spread_pct):
    for key in ("versions", "binary_sha256", "build_manifests"):
        if not required(report, key).get(version):
            raise EvidenceError(f"missing {key}.{version}")
    manifest = report['build_manifests'][version]
    for field, expected in (("commit", report['versions'][version]),
                            ("binary_sha256", report['binary_sha256'][version]),
                            ("harness_sha256", report['harness_sha256']),
                            ("build_settings", report['build_settings'])):
        if manifest.get(field) != expected:
            raise EvidenceError(f"{version}: build manifest mismatch for {field}")
    if not manifest.get('source_native_sha256'):
        raise EvidenceError(f"{version}: missing native source fingerprint")
    rows = [r for r in report["runs"]
            if r["version"] == version and r["scenario"] == scenario]
    rounds = report["protocol"]["rounds"]
    if len(rows) != rounds or {r["round"] for r in rows} != set(range(1, rounds + 1)):
        raise EvidenceError(f"{version}/{scenario}: missing or duplicate rounds")
    values = {"cold_ms": [], "warm_ms": [], "peak_rss_mib": []}
    temperatures, caps, hashes = [], [], []
    for row in sorted(rows, key=lambda r: r["round"]):
        if row["returncode"] != 0 or row.get("error"):
            raise EvidenceError(f"{version}/{scenario}: failed process, do not discard it")
        if row["before"].get("gate_wait_s", 0) < 0:
            raise EvidenceError(f"{version}/{scenario}: frequency gate timed out")
        if 'foreground_checks' in report['protocol']:
            if report['protocol']['foreground_checks'] != FOREGROUND_CHECKS:
                raise EvidenceError('unsupported foreground check protocol')
            if not all(foreground_ready(row.get(side, {}).get('foreground')) for side in ('before', 'after')):
                raise EvidenceError(f'{version}/{scenario}: foreground missing or not ready')
        samples = row["samples"]
        if len(samples) != 3 or [s["pass"] for s in samples] != [0, 1, 2]:
            raise EvidenceError(f"{version}/{scenario}: incomplete passes")
        spec = report["scenarios"][scenario]
        for sample in samples:
            if sample["workers"] != spec["workers"] or sample["sentences"] != spec["sentences"]:
                raise EvidenceError(f"{version}/{scenario}: workload changed")
            positive(sample["elapsed_ms"])
            positive(sample["peak_rss_mib"])
            if not sample.get("output_hash") or positive(sample["output_bytes"]) <= 0:
                raise EvidenceError(f"{version}/{scenario}: missing translation output")
        values["cold_ms"].append(samples[0]["elapsed_ms"])
        values["warm_ms"].append(median(s["elapsed_ms"] for s in samples[1:]))
        values["peak_rss_mib"].append(samples[0]["peak_rss_mib"])
        temperatures.append(float(row["before"]["battery_temp_c"]))
        if not math.isfinite(temperatures[-1]):
            raise EvidenceError("invalid temperature")
        cap = required(row["before"], "max_freq_khz")
        if not all(int(v) > 0 for v in cap.values()):
            raise EvidenceError("invalid frequency cap")
        caps.append(cap)
        hashes.extend(s["output_hash"] for s in samples)
    for metric, nums in values.items():
        spread = 100 * (max(nums) - min(nums)) / median(nums)
        if spread > max_spread_pct:
            raise EvidenceError(f"{version}/{scenario}/{metric}: spread {spread:.1f}%, rerun")
    return {k: median(v) for k, v in values.items()}, temperatures, caps, hashes


def compare(base, candidate, base_version, candidate_version, latency_pct=10,
            memory_pct=10, max_spread_pct=10, max_temp_delta_c=3):
    compatible(base, candidate)
    scenarios = set(base["scenarios"])
    for report, version in ((base, base_version), (candidate, candidate_version)):
        observed = {r["scenario"] for r in report["runs"] if r["version"] == version}
        if observed != scenarios:
            raise EvidenceError(f"{version}: missing or unexpected scenarios")
    output, regressions = [], []
    # Keep the declared suite order, not alphabetic or completion order.
    for scenario in base['scenarios']:
        old, old_temp, old_caps, old_hashes = measurements(base, base_version, scenario, max_spread_pct)
        new, new_temp, new_caps, new_hashes = measurements(candidate, candidate_version, scenario, max_spread_pct)
        # Each process must start at the same cap; this is not a claim that
        # actual CPU frequency remains locked throughout the translation.
        if any(cap != old_caps[0] for cap in old_caps + new_caps):
            raise EvidenceError(f"{scenario}: starting frequency caps differ")
        if max(old_temp + new_temp) - min(old_temp + new_temp) > max_temp_delta_c:
            raise EvidenceError(f"{scenario}: starting temperatures differ too much")
        if base["scenarios"][scenario].get("stable_hash", False):
            if len(set(old_hashes)) != 1 or len(set(new_hashes)) != 1:
                raise EvidenceError(f"{scenario}: output unstable within a version")
            if old_hashes[0] != new_hashes[0]:
                regressions.append(f"{scenario}: output hash changed; review correctness first")
        for metric in old:
            delta = 100 * (new[metric] / old[metric] - 1)
            limit = memory_pct if metric == "peak_rss_mib" else latency_pct
            if metric in ('cold_ms', 'warm_ms'):
                speed = speed_metrics(old[metric], new[metric], base['scenarios'][scenario]['sentences'])
                label = 'cold_speed' if metric == 'cold_ms' else 'warm_speed'
                output.append(
                    f"{scenario:18} {label:14} {speed['before_inputs_per_second']:.2f} -> "
                    f"{speed['after_inputs_per_second']:.2f} inputs/s "
                    f"({speed['speedup']:.2f}x, {speed['speed_change_pct']:+.1f}%); "
                    f"raw {metric}: {old[metric]:.3f} -> {new[metric]:.3f} ms")
            else:
                output.append(f"{scenario:18} {metric:14} {old[metric]:9.3f} -> {new[metric]:9.3f} ({delta:+.1f}%)")
            # Keep the established time/RSS gate; changing display units must
            # not turn +10% time into a different -10% speed threshold.
            if delta > limit:
                regressions.append(f"{scenario}/{metric}: {delta:+.1f}% > {limit:g}%")
    return output, regressions


def nonnegative(text):
    value = float(text)
    if not math.isfinite(value) or value < 0:
        raise argparse.ArgumentTypeError("must be a finite nonnegative number")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--baseline-version", required=True)
    parser.add_argument("--candidate-version", required=True)
    parser.add_argument("--latency-pct", type=nonnegative, default=10)
    parser.add_argument("--memory-pct", type=nonnegative, default=10)
    parser.add_argument("--max-spread-pct", type=nonnegative, default=10)
    parser.add_argument("--max-temp-delta-c", type=nonnegative, default=3)
    args = parser.parse_args()
    try:
        base, candidate = [json.loads(p.read_text()) for p in (args.baseline, args.candidate)]
        output, regressions = compare(base, candidate, args.baseline_version, args.candidate_version,
                                      args.latency_pct, args.memory_pct,
                                      args.max_spread_pct, args.max_temp_delta_c)
    except (EvidenceError, KeyError, TypeError, ValueError, OSError, AttributeError) as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 2
    print("\n".join(output))
    for regression in regressions:
        print(f"REGRESSION: {regression}")
    print("FAIL" if regressions else "PASS (within configured thresholds; not a significance test)")
    return 1 if regressions else 0


if __name__ == "__main__":
    sys.exit(main())
