#!/usr/bin/env python3
"""Summarize android-app-v1; reject incomplete, changed-input or noisy evidence."""
import argparse
import json
import math
from pathlib import Path
from statistics import median
from run import SCENARIOS, sha256, validate_result
from bench_device import FOREGROUND_CHECKS, foreground_ready


def summarize(directory):
    report = json.loads((directory / "results.json").read_text())
    errors = []
    if report.get("status") != "complete" or report.get("mode") != "measure":
        errors.append("requires a completed measure run, not prepare/quality/partial data")
    if report.get("suite_id") != "android-app-v1":
        errors.append("unsupported suite")
    protocol = report.get("protocol", {})
    for key, expected in (("rounds", 3), ("passes", 3), ("gate_khz", 2630400),
                          ("order", "forward/reverse/forward"), ("cooldown_seconds", 15)):
        if protocol.get(key) != expected:
            errors.append(f"changed protocol.{key}")
    if not report.get("model_files_sha256_after"):
        errors.append("post-run model fingerprints missing; model stability not verified")
    elif report.get("model_files_sha256_before") != report.get("model_files_sha256_after"):
        errors.append("model files changed during measurement")
    if not report.get("model_files_sha256_before"):
        errors.append("model fingerprints missing")
    for key in ("apk_sha256", "source_files_sha256", "corpus_sha256", "device"):
        if not report.get(key):
            errors.append(f"missing {key}")
    source_file = directory / "source-files.json"
    if not source_file.is_file() or sha256(source_file.read_bytes()) != report.get("source_files_sha256"):
        errors.append("source file manifest missing or changed")
    expected_cells = {(r, d, e, None if e == "mlkit" else t)
                      for r in range(1, 4) for d, e, t in SCENARIOS}
    rows = report.get("runs", [])
    keys = [(r.get("round"), r.get("direction"), r.get("engine"), r.get("threads")) for r in rows]
    if len(keys) != len(expected_cells) or set(keys) != expected_cells:
        errors.append("expected 24 unique runs: all eight scenarios x three rounds")
    grouped = {}
    temperatures = []
    for row in rows:
        label = f"{row.get('direction')}/{row.get('engine')}/{row.get('threads')}t"
        try:
            if row.get("status") != "complete":
                raise ValueError("incomplete process")
            path = directory / row["result_file"]
            data = json.loads(path.read_text())
            validate_result(data, row["run_id"], row["direction"], row["engine"], row["threads"] or 1,
                            report["corpus_sha256"][row["direction"]])
            if data["device_fingerprint"] != report["device"]["fingerprint"]:
                raise ValueError("device fingerprint changed")
            if "foreground_checks" in protocol:
                if protocol["foreground_checks"] != FOREGROUND_CHECKS:
                    raise ValueError("unsupported foreground check protocol")
                if not all(foreground_ready(row.get(side, {}).get("foreground")) for side in ("before", "after")):
                    raise ValueError("device not awake/unlocked with benchmark app foreground")
            caps = row["before"]["max_freq_khz"]
            if set(caps) != {"2", "7"} or any(v < 2630400 for v in caps.values()):
                raise ValueError("frequency gate not satisfied")
            temperatures.append(row["before"]["battery_temp_c"])
            passes = data["passes"]
            grouped.setdefault(label, []).append({
                "cold_ms": passes[0]["elapsed_ms"],
                "warm_ms": median(p["elapsed_ms"] for p in passes[1:]),
                "cold_pss_mib": passes[0]["metrics"]["peakPssMb"],
                "warm_pss_mib": max(p["metrics"]["peakPssMb"] for p in passes[1:]),
                "hashes": [p["output_sha256"] for p in passes],
            })
        except (KeyError, ValueError, OSError, TypeError) as error:
            errors.append(f"{label}: {error}")
    if temperatures and (any(not math.isfinite(t) for t in temperatures) or max(temperatures) - min(temperatures) > 3):
        errors.append("starting battery temperatures span more than 3 C")
    summaries = []
    for direction, engine, threads in SCENARIOS:
        label = f"{direction}/{engine}/{None if engine == 'mlkit' else threads}t"
        cells = grouped.get(label, [])
        result = {"scenario": label, "successful_processes": len(cells)}
        if len(cells) == 3:
            for metric in ("cold_ms", "warm_ms", "cold_pss_mib", "warm_pss_mib"):
                values = [c[metric] for c in cells]
                value = median(values)
                result[metric] = value
                if (max(values) - min(values)) / value > .10:
                    errors.append(f"{label}: {metric} spread exceeds 10%")
            result["cold_inputs_per_second"] = 200000 / result["cold_ms"]
            result["warm_inputs_per_second"] = 200000 / result["warm_ms"]
            result["unique_output_hashes"] = sorted({h for c in cells for h in c["hashes"]})
            if len(result["unique_output_hashes"]) != 1:
                errors.append(f"{label}: output changes across identical calls")
        summaries.append(result)
    return {"suite_id": "android-app-v1", "accepted": not errors, "errors": errors, "scenarios": summaries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output and args.output.exists():
        parser.error("summary exists; do not overwrite historical reports")
    result = summarize(args.directory)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text)
    raise SystemExit(0 if result["accepted"] else 2)


if __name__ == "__main__":
    main()
