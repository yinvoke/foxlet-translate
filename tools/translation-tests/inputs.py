"""Validate frozen local inputs before scoring; raw corpus text stays in build/."""

import hashlib
import json
from pathlib import Path

DIRECTIONS = ("en-zh", "zh-en", "ja-en", "ko-en", "ru-en", "ja-zh", "ko-zh", "ru-zh")


def verify(work: Path) -> None:
    manifest = json.loads((work / "inputs.json").read_text())
    for name, expected in manifest["files"].items():
        actual = hashlib.sha256((work / "data" / name).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"Corpus fingerprint mismatch: {name}")
    runs = json.loads((work / "runs.json").read_text())
    expected_keys = {
        f"{direction}.{shape}.{label}"
        for direction in DIRECTIONS
        for shape in ("single", "paragraph")
        for label in ("legacy", "current")
    }
    if len(runs) != 32 or {r["key"] for r in runs} != expected_keys:
        raise ValueError("All 32 translation runs must complete before scoring")
    for run in runs:
        path = work / "outputs" / (run["key"] + ".txt")
        if hashlib.sha256(path.read_bytes()).hexdigest() != run["output_sha256"]:
            raise ValueError(f"Translation fingerprint mismatch: {path.name}")
        expected_count = 1012 if ".single." in run["key"] else 253
        lines = path.read_text().splitlines()
        if len(lines) != expected_count or not all(s.strip() for s in lines):
            raise ValueError(f"Missing or empty translation rows: {path.name}")
