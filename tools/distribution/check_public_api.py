#!/usr/bin/env python3
"""Check unified entry points and compare a reproducible JVM API signature snapshot."""
import argparse
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = "io/github/yinvoker/foxlet/"
RETIRED = ("BergamotEngine", "FoxletEngine", "EngineConfig", "ModelCatalog", "ThreadTuning", "NonbreakingPrefixes")
REQUIRED = ("Foxlet", "FoxletConfig", "ModelManager", "Translator", "ModelDescriptor", "InstalledModel", "LanguagePair")


def check(aar, snapshot, update=False):
    with zipfile.ZipFile(aar) as archive:
        jar = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar)) as classes:
        names = set(classes.namelist())
    for name in RETIRED:
        assert not any(path == BASE + name + ".class" or path.startswith(BASE + name + "$") for path in names), "Retired entry point remains: " + name
    for name in REQUIRED:
        assert BASE + name + ".class" in names, "Missing unified API: " + name
    top = set()
    declaration = re.compile(r"^(?:(?:data|sealed|enum|annotation|value|open|abstract) )*(?:class|interface|object) (\w+)", re.M)
    for source in (ROOT / "foxlet/src/main/kotlin/io/github/yinvoker/foxlet").glob("*.kt"):
        top.update(declaration.findall(source.read_text()))
    selected = set(top)
    for path in names:
        if not path.startswith(BASE) or not path.endswith(".class"):
            continue
        name = path[len(BASE):-6]
        parts = name.split("$")
        if len(parts) == 2 and parts[0] in top and parts[1] in ("Companion", "Fixed", "Auto", "Idle", "AfterRequest", "UntilShutdown", "DefaultImpls"):
            selected.add(name)
    javap = str(Path(os.environ["JAVA_HOME"]) / "bin/javap") if os.environ.get("JAVA_HOME") else shutil.which("javap")
    if not javap:
        raise RuntimeError("JDK 17 javap is required (set JAVA_HOME)")
    with tempfile.TemporaryDirectory(prefix="foxlet-api-") as directory:
        jar_path = Path(directory) / "classes.jar"
        jar_path.write_bytes(jar)
        output = subprocess.check_output([javap, "-classpath", str(jar_path), "-public", "-s",
            *["io.github.yinvoker.foxlet." + name for name in sorted(selected)]], text=True)
    # Source filenames are irrelevant to binary signatures.
    output = "\n".join(line.rstrip() for line in output.splitlines() if not line.startswith('Compiled from "')) + "\n"
    if update:
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        snapshot.write_text(output)
    elif not snapshot.is_file() or snapshot.read_text() != output:
        raise AssertionError("Public JVM signature snapshot differs; review and run check_public_api.py --update")
    print("PASS unified API: " + str(len(top)) + " public types, retired entry points absent, JVM signatures match")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, default=ROOT / "foxlet/build/outputs/aar/foxlet-release.aar")
    parser.add_argument("--snapshot", type=Path, default=ROOT / "api/public-jvm.txt")
    parser.add_argument("--update", action="store_true", help="Record an intentionally changed API after review")
    args = parser.parse_args()
    check(args.aar, args.snapshot, args.update)


if __name__ == "__main__":
    main()
