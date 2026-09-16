#!/usr/bin/env python3
"""Reuse a tested GitHub release AAR for Maven; never rebuild its native bytes."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--verify-repository', action='store_true')
    args = parser.parse_args()
    source, assets = args.source.resolve(), args.assets.resolve()
    require(re.fullmatch(r'v\d+\.\d+\.\d+', args.tag), 'Expected a stable vMAJOR.MINOR.PATCH tag')
    version = args.tag[1:]
    properties = (source / 'gradle.properties').read_text()
    require(re.search(r'^version=' + re.escape(version) + r'$', properties, re.M), 'Version/tag mismatch')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    tag_commit = subprocess.check_output(['git', 'rev-parse', args.tag + '^{commit}'], cwd=source, text=True).strip()
    require(commit == tag_commit, 'Source checkout must match the release tag')
    dirty = subprocess.check_output(['git', 'status', '--porcelain'], cwd=source, text=True).strip()
    require(not dirty, 'Use a clean checkout of the release tag')
    checks = {}
    for line in (assets / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(maxsplit=1)
        name = name.lstrip('*')
        require(Path(name).name == name, 'Invalid checksum filename')
        require(digest(assets / name) == expected, 'Release checksum mismatch: ' + name)
        checks[name] = expected
    aar = assets / f'foxlet-{args.tag}.aar'
    apk = assets / f'foxlet-sdk-example-{args.tag}.apk'
    for name in (aar.name, apk.name, 'SOURCE.txt', 'NOTICE.txt', 'THIRD_PARTY_NOTICES.txt'):
        require(name in checks, 'Missing release checksum: ' + name)
    device = json.loads((assets / 'device-result.json').read_text())
    require(device.get('passed') is True and 'arm64-v8a' in device.get('abi', ''), 'Android device gate did not pass')
    require(device.get('apk_sha256') == digest(apk), 'Device result belongs to a different APK')
    identity = (assets / 'SOURCE.txt').read_text()
    require(f'Build commit: {commit}\n' in identity and f'/tree/{args.tag}\n' in identity,
            'Release source identity does not match the checkout')
    require('UNRELEASED LOCAL BUILD' not in identity, 'Cannot publish a dirty build')
    resource_path = 'io/github/yinvoker/foxlet/licenses/'
    with zipfile.ZipFile(aar) as archive, zipfile.ZipFile(apk) as example:
        with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as classes:
            for name in ('SOURCE.txt', 'NOTICE.txt', 'THIRD_PARTY_NOTICES.txt'):
                require(classes.read(resource_path + name) == (assets / name).read_bytes(),
                        'Embedded release resource mismatch: ' + name)
        for name in archive.namelist():
            if name.startswith('jni/') and name.endswith('.so'):
                require(archive.read(name) == example.read(name.replace('jni/', 'lib/', 1)),
                        'AAR native library differs from tested APK: ' + name)
    if args.verify_repository:
        repository = source / 'build/maven-repository/io/github/yinvoke/foxlet-translate' / version
        stem = f'foxlet-translate-{version}'
        require(digest(repository / (stem + '.aar')) == digest(aar), 'Maven AAR differs from the tested release')
        pom = ET.parse(repository / (stem + '.pom')).getroot()
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        for key, value in [('groupId', 'io.github.yinvoke'), ('artifactId', 'foxlet-translate'),
                           ('version', version), ('packaging', 'aar')]:
            require(pom.findtext('m:' + key, namespaces=ns) == value, 'Incorrect POM ' + key)
        module = json.loads((repository / (stem + '.module')).read_text())
        for variant in module['variants']:
            for entry in variant.get('files', []):
                require(Path(entry['name']).name == entry['name'], 'Invalid module filename')
                artifact = repository / entry['name']
                require(artifact.stat().st_size == entry['size'], 'Module size mismatch')
                require(digest(artifact) == entry['sha256'], 'Module checksum mismatch')
        for suffix in ('-sources.jar', '-javadoc.jar'):
            with zipfile.ZipFile(repository / (stem + suffix)) as archive:
                require(len(archive.namelist()) > 1, 'Empty Maven companion: ' + suffix)
        print('PASS Maven repository: tested AAR, coordinates, metadata, sources and documentation')
        return
    destination = source / 'foxlet/build/outputs/aar/foxlet-release.aar'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(aar, destination)
    resources = source / 'foxlet/build/generated/distributionResources' / resource_path
    resources.mkdir(parents=True, exist_ok=True)
    for name in ('SOURCE.txt', 'NOTICE.txt', 'THIRD_PARTY_NOTICES.txt'):
        shutil.copyfile(assets / name, resources / name)
    print(f'Prepared {args.tag} from {commit}; AAR SHA-256 {digest(aar)}')


if __name__ == '__main__':
    main()
