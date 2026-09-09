#!/usr/bin/env python3
"""Score exported translations with a pinned local wmt22-comet-da checkpoint.

Inputs are an app run directory plus the original FLORES-200 devtest folder.
Optional --reference-output files contain 200 JSON strings from
quality_reference. Their labels/provenance belong in the accompanying report.
Scores are quality-only: no inference about device speed is made here.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
from run import SCENARIOS, validate_result


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app_results", type=Path)
    parser.add_argument("flores_devtest", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--completed-quality-only", action="store_true",
                        help="score complete cells of an interrupted run; requires all eight scenarios and never validates performance")
    parser.add_argument("--reference-output", nargs=3, action="append", default=[], metavar=("LABEL", "DIRECTION", "JSON"))
    args = parser.parse_args()
    if args.output.exists():
        parser.error("preserve existing scores; choose a new filename")
    root = Path(__file__).resolve().parents[2]
    sources = {}
    for direction, code, asset in (("enzh", "eng_Latn", "eng"), ("jazh", "jpn_Jpan", "jpn")):
        sources[direction] = (args.flores_devtest / f"{code}.devtest").read_text().splitlines()[:200]
        if sources[direction] != (root / f"sample/src/main/assets/bench/{asset}.txt").read_text().splitlines():
            parser.error(f"{direction}: input corpus differs from original FLORES rows")
    ref_path = args.flores_devtest / "zho_Hans.devtest"
    references = ref_path.read_text().splitlines()[:200]
    if len(references) != 200 or any(not text.strip() for text in references):
        parser.error("missing Chinese references")
    report = json.loads((args.app_results / "results.json").read_text())
    if report["status"] != "complete" and not args.completed_quality_only:
        parser.error("app export is incomplete; do not silently omit failed cells")
    complete_runs = [r for r in report["runs"] if r.get("status") == "complete"]
    expected = {(d, e, None if e == "mlkit" else t) for d, e, t in SCENARIOS}
    if {(r["direction"], r["engine"], r["threads"]) for r in complete_runs} != expected:
        parser.error("quality scoring requires complete exports for all eight app scenarios")
    jobs = []
    for run in complete_runs:
        raw_path = args.app_results / run["result_file"]
        raw = json.loads(raw_path.read_text())
        validate_result(raw, run["run_id"], run["direction"], run["engine"], run["threads"] or 1,
                        report["corpus_sha256"][run["direction"]])
        for sample in raw["passes"]:
            jobs.append({"label": f"{raw['engine']}-{raw['direction']}-{raw['threads']}t-r{run['round']}-p{sample['pass']}",
                         "direction": raw["direction"], "outputs": sample["outputs"],
                         "source_file": str(raw_path), "source_sha256": sha256(raw_path)})
    for label, direction, file in args.reference_output:
        if direction not in sources:
            parser.error("reference direction must be enzh or jazh")
        path = Path(file)
        jobs.append({"label": label, "direction": direction, "outputs": json.loads(path.read_text()),
                     "source_file": str(path), "source_sha256": sha256(path)})
    for job in jobs:
        if len(job["outputs"]) != 200 or any(not isinstance(t, str) or not t.strip() for t in job["outputs"]):
            parser.error(f"{job['label']}: expected 200 complete translations")

    import torch
    from comet import load_from_checkpoint
    from pytorch_lightning import seed_everything
    seed_everything(0, workers=True)
    torch.set_num_threads(6)
    # The historical recipe used this API rather than the CLI, whose worker
    # defaults were incompatible with the macOS environment.
    model = load_from_checkpoint(str(args.checkpoint), local_files_only=True)
    expected_runs = {(r, d, e, None if e == "mlkit" else t)
                     for r in range(1, report["protocol"]["rounds"] + 1) for d, e, t in SCENARIOS}
    completed_keys = {(r["round"], r["direction"], r["engine"], r["threads"]) for r in complete_runs}
    results = {"status": "running", "metric": "wmt22-comet-da x 100", "checkpoint_sha256": sha256(args.checkpoint),
               "source_report_sha256": sha256(args.app_results / "results.json"),
               "source_report_status": report["status"], "completed_processes": len(complete_runs),
               "planned_processes": len(expected_runs),
               "uncompleted_cells": sorted(expected_runs - completed_keys, key=str),
               "performance_validated": False,
               "reference_file_sha256": sha256(ref_path), "input_count": 200,
               "reference_slice_sha256": hashlib.sha256(("\n".join(references) + "\n").encode()).hexdigest(),
               "packages": {name: importlib.metadata.version(name) for name in ("unbabel-comet", "torch", "transformers")},
               "seed": 0, "batch_size": 8, "accelerator": "cpu", "num_workers": 2,
               "scored_outputs": [], "runs": []}
    cached = {}
    for job in jobs:
        output_hash = hashlib.sha256(("\n".join(job["outputs"]) + "\n").encode()).hexdigest()
        key = (job["direction"], output_hash)
        if key not in cached:
            data = [{"src": src, "mt": mt, "ref": ref}
                    for src, mt, ref in zip(sources[job["direction"]], job["outputs"], references)]
            prediction = model.predict(data, batch_size=8, gpus=0, num_workers=2)
            cached[key] = {"direction": job["direction"], "output_sha256": output_hash,
                           "comet_x100": prediction.system_score * 100,
                           "sentence_scores_x100": [float(s) * 100 for s in prediction.scores]}
            results["scored_outputs"].append(cached[key])
        results["runs"].append({k: v for k, v in job.items() if k != "outputs"} | {
            "output_sha256": output_hash, "comet_x100": cached[key]["comet_x100"]})
        args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        print(f"{job['label']}: {cached[key]['comet_x100']:.3f}", flush=True)
    results["status"] = "complete"
    args.output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
