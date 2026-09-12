#!/usr/bin/env python3
"""Generate the SDK's plain-text trusted model index from registry.json."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'foxlet/src/main/resources/io/github/yinvoker/foxlet/models.tsv'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = ['# from\tto\tversion\tname\tsize\tsha256\turl']
    for m in json.loads((ROOT / 'registry.json').read_text())['models']:
        for f in m['files']:
            rows.append('\t'.join(map(str, [m['from'], m['to'], m['version'], f['name'], f['size'], f['sha256'], f['url']])))
    content = '\n'.join(rows) + '\n'
    if args.check:
        if not OUT.exists() or OUT.read_text() != content:
            raise SystemExit('models.tsv is stale; run tools/distribution/sync_catalog.py')
    else:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(content)

if __name__ == '__main__':
    main()
