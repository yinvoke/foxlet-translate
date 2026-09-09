#!/usr/bin/env python3
"""Run android-app-v1 in fresh app processes; retain raw outputs and failures.

Install the supplied APK first (adb install -r). prepare downloads ML Kit
models without measurement. quality exports one run/cell without a frequency
gate: its timings must NOT be promoted to performance results. measure runs
three rounds with the fixed Mi 14 frequency gate and eight app scenarios.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bench_device import FOREGROUND_CHECKS, capture_foreground, foreground_ready
PACKAGE = "io.github.yinvoker.bergamot.bench"
REPO = Path(__file__).resolve().parents[2]
SCENARIOS = [(direction, engine, threads) for direction in ("enzh", "jazh")
             for engine, threads in (("mlkit", 1), ("bergamot", 1), ("bergamot", 2), ("bergamot", 4))]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def decode_snapshot(raw):
    """An in-progress app write can split either JSON or a UTF-8 character.

    Retry the entire snapshot, never replace malformed bytes in translations.
    The caller's deadline bounds retries if the file stays invalid.
    """
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def validate_result(data, run_id, direction, engine, threads, corpus_hash, prepare=False):
    expected = {"suite_id": "android-app-v1", "run_id": run_id,
                "mode": "prepare" if prepare else "measure", "direction": direction,
                "engine": engine, "threads": None if engine == "mlkit" else threads,
                "input_count": 200, "corpus_sha256": corpus_hash, "status": "complete"}
    for key, value in expected.items():
        if data.get(key) != value:
            raise ValueError(f"{key}: expected {value!r}, got {data.get(key)!r}")
    if prepare:
        return
    if [p.get("pass") for p in data.get("passes", [])] != [0, 1, 2]:
        raise ValueError("missing/duplicate passes")
    for sample in data["passes"]:
        if sample.get("error"):
            raise ValueError(sample["error"])
        ms = sample["elapsed_ms"]
        if not isinstance(ms, (int, float)) or not math.isfinite(ms) or ms <= 0:
            raise ValueError("invalid elapsed_ms")
        outputs = sample["outputs"]
        if len(outputs) != 200 or any(not isinstance(s, str) or not s.strip() for s in outputs):
            raise ValueError("incomplete translations")
        if sha256(("\n".join(outputs) + "\n").encode()) != sample["output_sha256"]:
            raise ValueError("output hash mismatch")
        if not sample["metrics"].get("curve") or sample["metrics"].get("peakPssMb", 0) <= 0:
            raise ValueError("PSS samples missing")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "quality", "measure"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb_cmd = [args.adb, "-s", args.serial]

    def adb(*words, timeout=30):
        return subprocess.check_output([*adb_cmd, *words], text=True, timeout=timeout).strip()

    def shell(command):
        return adb("shell", command)

    def sensors():
        battery = shell("dumpsys battery")
        fields = dict(re.findall(r"^\s*(level|temperature|status|AC powered|USB powered):\s*(\S+)", battery, re.M))
        return {"battery_temp_c": int(fields["temperature"]) / 10,
                "battery_level": int(fields["level"]), "battery_status": fields.get("status"),
                "ac_powered": fields.get("AC powered"), "usb_powered": fields.get("USB powered"),
                "foreground": capture_foreground(shell),
                "max_freq_khz": {str(cpu): int(shell(f"cat /sys/devices/system/cpu/cpu{cpu}/cpufreq/scaling_max_freq"))
                                 for cpu in (2, 7)}}

    def gate():
        started = time.monotonic()
        while True:
            state = sensors()
            if not foreground_ready(state["foreground"]):
                return state  # Save the failed preflight before reporting it.
            if all(cap >= 2630400 for cap in state["max_freq_khz"].values()):
                return state
            if time.monotonic() - started >= 180:
                raise RuntimeError(f"frequency gate timeout: {state}")
            time.sleep(5)

    model = shell("getprop ro.product.model")
    if model != "23127PN0CC":
        parser.error(f"only the verified Mi 14 layout is supported, got {model}")
    package_paths = shell(f"pm path {PACKAGE}").splitlines()
    if len(package_paths) != 1 or not package_paths[0].startswith("package:"):
        parser.error("expected an installed, single-APK benchmark app")
    installed_hash = shell("sha256sum " + shlex.quote(package_paths[0][8:])).split()[0]
    if installed_hash != sha256(args.apk.read_bytes()):
        parser.error("installed APK differs from --apk; install the intended build first")
    corpus = {d: sha256((REPO / f"sample/src/main/assets/bench/{lang}.txt").read_bytes())
              for d, lang in (("enzh", "eng"), ("jazh", "jpn"))}
    report = {"suite_id": "android-app-v1", "mode": args.mode, "measured_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
              "status": "running", "apk_sha256": installed_hash, "corpus_sha256": corpus,
              "device": {"model": model, "fingerprint": shell("getprop ro.build.fingerprint"),
                         "id_sha256": sha256(args.serial.encode())},
              "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "source_dirty_status": subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True),
              "mlkit_dependency": "com.google.mlkit:translate:17.0.3",
              "protocol": {"rounds": 3 if args.mode == "measure" else 1, "passes": 3,
                           "order": "forward/reverse/forward", "cooldown_seconds": 15,
                           "gate_khz": 2630400 if args.mode == "measure" else None,
                           "affinity": "Android foreground scheduling, no taskset",
                           "foreground": "fresh process opens idle benchmark UI before frequency gate; engine not created",
                           "foreground_checks": FOREGROUND_CHECKS,
                           "memory": "absolute app PSS; 250 ms samples, not native RSS",
                           "performance_eligible": args.mode == "measure"}, "runs": []}
    # Hash the actual source files, not just the moving HEAD label. The APK
    # hash above remains the identity of the measured artifact.
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=REPO).decode().split("\0")
    source_hashes = {}
    for name in sorted(set(paths) - {""}):
        file = REPO / name
        if file.is_file() and (name.startswith(("engine/", "jni/", "bergamot/src/", "sample/src/", "tools/app-bench/"))
                               or name.endswith(".gradle.kts") or name in ("CMakeLists.txt", "gradle.properties", "tools/bench_device.py")):
            source_hashes[name] = sha256(file.read_bytes())
    (args.output / "source-files.json").write_text(json.dumps(source_hashes, indent=2) + "\n")
    report["source_files_sha256"] = sha256((args.output / "source-files.json").read_bytes())

    def save():
        (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    save()
    try:
        # Deliberately fingerprint only model files, never InstallationId or
        # unrelated app data. The two namespaces are not combined for scoring.
        model_files = shell(f"run-as {PACKAGE} sh -c " + shlex.quote(
            "find files/models no_backup/com.google.mlkit.translate.models -type f -exec sha256sum {} \\;"))
        report["model_files_sha256_before"] = model_files.splitlines()
        save()
        scenarios = [(d, "mlkit", 1) for d in ("enzh", "jazh")] if args.mode == "prepare" else SCENARIOS
        for round_index in range(report["protocol"]["rounds"]):
            order = scenarios if round_index % 2 == 0 else list(reversed(scenarios))
            for direction, engine, threads in order:
                run_id = f"{args.mode}_{round_index+1}_{direction}_{engine}_{threads}_{uuid.uuid4().hex[:10]}"
                run = {"round": round_index + 1, "run_id": run_id, "direction": direction,
                       "engine": engine, "threads": None if engine == "mlkit" else threads}
                report["runs"].append(run)
                save()
                shell(f"am force-stop {PACKAGE}")
                # Keep the benchmark app foreground while waiting. Waiting
                # on the launcher can trigger a different vendor power policy.
                # No engine is created on this idle screen. CLEAR_TOP below
                # recreates the activity without stacking a second idle UI.
                run["idle_launch_output"] = shell(f"am start -W -n {PACKAGE}/.MainActivity")
                run["before"] = gate() if args.mode == "measure" else sensors()
                if not foreground_ready(run["before"]["foreground"]):
                    raise RuntimeError(f"unlock the device and keep the benchmark app foreground: {run['before']['foreground']}")
                command = (f"am start -W --activity-clear-top -n {PACKAGE}/.MainActivity --es isolated_phase {direction} "
                           f"--es engine {engine} --ei threads {threads} --es run_id {run_id}")
                if args.mode == "prepare":
                    command += " --ez prepare_models true"
                run["launch_output"] = shell(command)
                save()
                deadline = time.monotonic() + 720
                data = None
                while time.monotonic() < deadline:
                    time.sleep(2)
                    result = subprocess.run([*adb_cmd, "exec-out", "run-as", PACKAGE, "cat", f"files/isolated_{run_id}.json"],
                                            capture_output=True, timeout=30)
                    if result.returncode:
                        continue
                    data = decode_snapshot(result.stdout)
                    if data is None:
                        continue  # app may be between truncate/write while saving a pass
                    (args.output / f"{run_id}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
                    if data.get("status") in ("complete", "failed"):
                        break
                else:
                    raise TimeoutError(f"app run timed out: {run_id}; partial results retained")
                run["result_file"] = f"{run_id}.json"
                run["after"] = sensors()
                if not foreground_ready(run["after"]["foreground"]):
                    raise RuntimeError(f"foreground lost; retain this run but do not accept it: {run['after']['foreground']}")
                validate_result(data, run_id, direction, engine, threads, corpus[direction], args.mode == "prepare")
                if args.mode != "prepare":
                    run["output_hashes"] = [p["output_sha256"] for p in data["passes"]]
                    run["outputs_stable"] = len(set(run["output_hashes"])) == 1
                run["status"] = "complete"
                save()
                print(f"round {round_index+1}: {direction} {engine} {threads}t complete", flush=True)
                shell(f"am force-stop {PACKAGE}")
                time.sleep(15)
        report["model_files_sha256_after"] = shell(f"run-as {PACKAGE} sh -c " + shlex.quote(
            "find files/models no_backup/com.google.mlkit.translate.models -type f -exec sha256sum {} \\;")).splitlines()
        report["status"] = "complete"
    except (Exception, KeyboardInterrupt) as error:
        report["status"] = "failed"
        report["error"] = str(error).replace(args.serial, "<device-serial>")
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
