#!/usr/bin/env python3
"""Build notices from retained upstream license files; optionally strip prefix data."""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RESOURCE = 'io/github/yinvoker/foxlet/licenses/'
PREFIX = 'io/github/yinvoker/foxlet/nonbreaking_prefixes/'

def notices(include_prefixes=True):
    sections = ['Foxlet Translate third-party licenses\n\nThis index covers vendored build/runtime components and separately resolved Kotlin dependencies. Each section retains its upstream terms and describes its scope.\n']
    sections.append('Foxlet Translate original code\n\n' + (ROOT / 'LICENSE').read_text())
    for item in json.loads((ROOT / 'licenses/components.json').read_text()):
        if item.get('optional_prefixes') and not include_prefixes:
            continue
        text = (ROOT / item['license_file']).read_text()
        if item.get('first_comment'):
            text = text[text.index('/*') + 2:text.index('*/')].strip()
        sections.append(item['name'] + '\nSource/license: ' + item['license_file'] + '\n' + item.get('copyright', '') + '\n\n' + text)
    return ('\n\n' + '=' * 72 + '\n\n').join(sections)

def write_resources(output):
    dest = output / RESOURCE
    dest.mkdir(parents=True, exist_ok=True)
    (dest / 'THIRD_PARTY_NOTICES.txt').write_text(notices())
    (dest / 'NOTICE.txt').write_text((ROOT / 'NOTICE').read_text())
    rev = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    ref = os.environ.get('GITHUB_REF_NAME') if os.environ.get('GITHUB_REF_TYPE') == 'tag' else None
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    identity = ref or rev
    text = ('Source repository: https://github.com/yinvoke/foxlet-translate\n'
            f'Build commit: {rev}\n'
            f'Source: https://github.com/yinvoke/foxlet-translate/tree/{identity}\n')
    if ref:
        text += f'Source archive: https://github.com/yinvoke/foxlet-translate/archive/refs/tags/{ref}.zip\n'
    if dirty:
        text += 'UNRELEASED LOCAL BUILD: includes uncommitted changes; the builder must supply this working tree with redistribution.\n'
    text += 'MPL-covered source, including modifications, remains under MPL-2.0. Preserve this source offer when redistributing.\n'
    (dest / 'SOURCE.txt').write_text(text)

def no_prefixes(source, output):
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as out:
        for name in archive.namelist():
            content = archive.read(name)
            if name == 'classes.jar':
                buffer = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(content)) as classes, zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as jar:
                    for entry in classes.namelist():
                        if entry.startswith(PREFIX):
                            continue
                        data = classes.read(entry)
                        if entry == RESOURCE + 'THIRD_PARTY_NOTICES.txt':
                            data = notices(False).encode()
                        jar.writestr(entry, data)
                content = buffer.getvalue()
            out.writestr(name, content)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--resources', type=Path)
    parser.add_argument('--without-prefixes', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.resources:
        write_resources(args.resources)
    elif args.without_prefixes and args.output:
        no_prefixes(args.without_prefixes, args.output)
    else:
        parser.error('choose --resources or --without-prefixes with --output')
