#!/usr/bin/env python3
"""Build the same harness from refs and/or a named local working-tree snapshot."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--jobs', type=int, default=4)
p.add_argument('--refs', nargs='*', default=['v0.1.0', 'v0.2.0'])
p.add_argument('--working-tree-version', help='Local snapshot label, e.g. v0.3.0; does not create a tag')
a = p.parse_args()
repo = Path(__file__).resolve().parents[2]
names = a.refs + ([a.working_tree_version] if a.working_tree_version else [])
if not names or len(names) != len(set(names)) or any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', n) for n in names):
    p.error('use unique, simple version labels (no slashes)')
if a.jobs < 1 or a.output.resolve().is_relative_to(repo):
    p.error('use positive --jobs and an output directory outside the repository')
a.output.mkdir(parents=True, exist_ok=True)
for tag in names:
    dest = a.output.resolve() / tag
    dest.mkdir()  # Refuse to overwrite an existing source/build tree.
    local = tag == a.working_tree_version
    commit = subprocess.check_output(['git', 'rev-parse', ('HEAD' if local else tag) + '^{commit}'], cwd=repo, text=True).strip()
    if local:
        # A commit ID alone cannot identify uncommitted native code. Include
        # tracked and new non-ignored source files, then hash the native inputs.
        files = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=repo).decode().split('\0')
        for relative in set(files) - {''}:
            source = repo / relative
            if source.is_file():
                target = dest / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    else:
        archive = a.output / (tag + '.tar')
        with archive.open('xb') as f:
            subprocess.run(['git', 'archive', commit], cwd=repo, stdout=f, check=True)
        subprocess.run(['tar', '-xf', str(archive.resolve()), '-C', str(dest)], check=True)
    digest = hashlib.sha256()
    native = [dest / 'CMakeLists.txt']
    for folder in ['engine', 'jni', 'tools/smoke']:
        native.extend(path for path in (dest / folder).rglob('*') if path.is_file())
    for path in sorted(native):
        digest.update(path.relative_to(dest).as_posix().encode() + b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    shutil.copyfile(repo / 'tools/version-bench/main.cpp', dest / 'tools/smoke/smoke.cpp')
    if tag == 'v0.1.0':
        cmake = dest / 'CMakeLists.txt'
        cmake.write_text(cmake.read_text().replace('  add_subdirectory(jni)', '  add_subdirectory(jni)\n  add_subdirectory(tools/smoke)'))
    # Vendored CMake generates git_revision.h from a HEAD log. This empty commit
    # is build metadata only; all engine inputs above are exported from the tag.
    subprocess.run(['git', 'init', str(dest)], check=True)
    subprocess.run(['git', '-C', str(dest), '-c', 'user.name=Benchmark', '-c', 'user.email=benchmark@localhost', 'commit', '--allow-empty', '-m', f'Build metadata for {tag} source export'], check=True)
    flags = ['-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28', '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_STL=c++_static', '-DSSPLIT_USE_INTERNAL_PCRE2=ON', '-DCOMPILE_TESTS=OFF', '-DBUILD_ARCH=armv8-a', '-DBUILD_SMOKE=ON']
    subprocess.run(['cmake', '-S', str(dest), '-B', str(dest / 'build'), '-DCMAKE_TOOLCHAIN_FILE=' + str(a.ndk.resolve() / 'build/cmake/android.toolchain.cmake'), *flags], check=True)
    subprocess.run(['cmake', '--build', str(dest / 'build'), '--target', 'smoke', '-j', str(a.jobs)], check=True)
    binary = dest / 'build/tools/smoke/smoke'
    manifest = {
        'label': tag, 'commit': commit, 'source_kind': 'working-tree' if local else 'git-ref',
        'source_native_sha256': digest.hexdigest(),
        'dirty_status': subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True) if local else '',
        'harness_sha256': hashlib.sha256((repo / 'tools/version-bench/main.cpp').read_bytes()).hexdigest(),
        'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
        'build_settings': {'cmake_flags': flags, 'ndk': (a.ndk / 'source.properties').read_text(),
                           'cmake': subprocess.check_output(['cmake', '--version'], text=True).splitlines()[0]},
    }
    (dest / 'build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copyfile(binary, a.output / f'smoke-{tag}')
