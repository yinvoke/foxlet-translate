#!/usr/bin/env python3
"""Refresh registry.json from Mozilla Remote Settings.

Fetches the ``translations-models`` collection (the uncompressed 1.x/2.x
student models Firefox ESR reads and this SDK's engine loads) and rebuilds
the bundled snapshot ``registry.json`` with the rule Firefox applies in
``TranslationsParent.getMaxSupportedVersionRecords`` for major versions 1..2
on an Android *release* build:

* only ``fileType`` model / lex / vocab / srcvocab / trgvocab;
* only records whose ``filter_expression`` is empty/None or exactly
  ``env.appinfo.OS == 'Android'`` after stripping — that is what the JEXL
  filter admits on a release Android client (nightly/beta-only and
  desktop-only records are dropped);
* only release version strings matching ``^\\d+\\.\\d+$`` (alpha builds such
  as ``1.0a1`` never reach a release client) with major version 1 or 2;
* a version of a pair counts only when it ships a complete file set: exactly
  one model, one lex, and either one vocab or one srcvocab plus one trgvocab;
* per (fromLang, toLang) the highest complete version, compared numerically
  as (major, minor).

Tombstones (``deleted: true``) and records without an attachment are skipped.
Attachment URLs are the CDN base URL plus the record's ``attachment.location``;
``sha256``/``size`` come from ``attachment.hash``/``attachment.size``.

Usage::

    python3 tools/distribution/fetch_registry.py --check   # report drift, exit 1 if any
    python3 tools/distribution/fetch_registry.py           # rewrite registry.json
    python3 tools/distribution/sync_catalog.py             # then regenerate models.tsv

Stdlib only; Python 3.9 compatible. One HTTPS GET (about 80 KB gzipped).
"""
import argparse
import datetime
import gzip
import json
import re
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / 'registry.json'
DEFAULT_URL = ('https://firefox.settings.services.mozilla.com/v1/buckets/main/'
               'collections/translations-models/changeset?_expected=0')
ATTACHMENT_BASE_URL = 'https://firefox-settings-attachments.cdn.mozilla.net/'
USER_AGENT = 'FoxletTranslate-fetch-registry (+https://github.com/yinvoke/foxlet-translate)'
COMMENT = ('Mozilla Firefox translation models (MPL-2.0), latest release version per direction, '
           'as published via Firefox Remote Settings. Snapshot date: {date}.')

ELIGIBLE_TYPES = {'model', 'lex', 'vocab', 'srcvocab', 'trgvocab'}
ANDROID_FILTER = "env.appinfo.OS == 'Android'"
RELEASE_VERSION = re.compile(r'^\d+\.\d+$')
SUPPORTED_MAJORS = range(1, 3)  # 1..2


def fetch(url):
    """GET the changeset (or records) endpoint and return the record list plus the collection timestamp."""
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Accept-Encoding': 'gzip'})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
        if response.headers.get('Content-Encoding', '').lower() == 'gzip':
            body = gzip.decompress(body)
        backoff = response.headers.get('Backoff')
    if backoff:
        print('warning: server asked clients to back off for {} s'.format(backoff), file=sys.stderr)
    payload = json.loads(body.decode('utf-8'))
    records = payload.get('changes', payload.get('data'))
    if not isinstance(records, list):
        raise SystemExit('unexpected response from {}: no "changes"/"data" list'.format(url))
    return records, payload.get('timestamp')


SHA256 = re.compile(r'^[0-9a-fA-F]{64}$')


def is_eligible(record):
    """The Android-release view of one record, before completeness and max-version selection.

    Mirrors RemoteIndex.kt: a record with a malformed attachment (no location, non-positive
    size, hash that is not 64 hex digits) or a non-string filter expression is dropped here
    rather than crash the script or emit an entry the SDK would refuse to download.
    """
    if not isinstance(record, dict) or record.get('deleted'):
        return False
    attachment = record.get('attachment')
    if not isinstance(attachment, dict):
        return False
    if not isinstance(attachment.get('location'), str) or not attachment['location']:
        return False
    if type(attachment.get('size')) is not int or not 0 < attachment['size'] <= 1024**3:
        return False
    if not isinstance(attachment.get('hash'), str) or not SHA256.match(attachment['hash']):
        return False
    if not all(isinstance(record.get(k), str) and record[k].strip() for k in ('id', 'name', 'fromLang', 'toLang')):
        return False
    if not isinstance(record.get('fileType'), str) or record['fileType'] not in ELIGIBLE_TYPES:
        return False
    expression = record.get('filter_expression')
    if expression is None:
        expression = ''
    if not isinstance(expression, str) or expression.strip() not in ('', ANDROID_FILTER):
        return False
    version = record.get('version')
    if not isinstance(version, str) or not RELEASE_VERSION.match(version):
        return False
    return int(version.split('.')[0]) in SUPPORTED_MAJORS


def version_key(version):
    major, minor = version.split('.')
    return int(major), int(minor)


def completeness(records):
    """None when the records form exactly one usable file set, else a short reason."""
    names = [r['name'] for r in records]
    if len(set(names)) != len(names):
        return 'duplicate asset name'
    if any('/' in n or any(c.isspace() or ord(c) < 32 for c in n) for n in names):
        return 'invalid asset name'
    patterns = (r'model\..+\.bin', r'lex\..+\.bin', r'(?!trg).*vocab.*\.spm', r'trgvocab.*\.spm')
    matches = [[n for n in names if re.fullmatch(p, n)] for p in patterns]
    if any(len(m) != 1 for m in matches[:3]) or len(matches[3]) > 1 or set(sum(matches, [])) != set(names):
        return 'unresolvable asset layout'
    counts = Counter(r['fileType'] for r in records)
    if counts['model'] != 1 or counts['lex'] != 1:
        return 'model x{} lex x{}'.format(counts['model'], counts['lex'])
    if counts['vocab'] == 1 and counts['srcvocab'] == 0 and counts['trgvocab'] == 0:
        return None
    if counts['vocab'] == 0 and counts['srcvocab'] == 1 and counts['trgvocab'] == 1:
        return None
    return 'vocab x{} srcvocab x{} trgvocab x{}'.format(counts['vocab'], counts['srcvocab'], counts['trgvocab'])


def select(records):
    """Return (models, notes): the registry model list and human-readable notes on skipped versions."""
    by_pair = defaultdict(lambda: defaultdict(list))
    for record in records:
        if is_eligible(record):
            by_pair[(record['fromLang'], record['toLang'])][record['version']].append(record)
    models, notes = [], []
    for (source, target), versions in sorted(by_pair.items()):
        chosen = None
        for version in sorted(versions, key=version_key, reverse=True):
            reason = completeness(versions[version])
            if reason is None:
                chosen = version
                break
            notes.append('{}->{}: skipped incomplete version {} ({})'.format(source, target, version, reason))
        if chosen is None:
            notes.append('{}->{}: no complete release version, pair omitted'.format(source, target))
            continue
        files = [{
            'type': r['fileType'],
            'name': r['name'],
            'size': r['attachment']['size'],
            'sha256': r['attachment']['hash'].lower(),
            'url': ATTACHMENT_BASE_URL + r['attachment']['location'],
        } for r in versions[chosen]]
        files.sort(key=lambda f: f['name'])
        models.append({'from': source, 'to': target, 'version': chosen, 'files': files})
    return models, notes


def load_existing(path):
    if not path.exists():
        return {'models': []}
    return json.loads(path.read_text(encoding='utf-8'))


def diff(old_models, new_models):
    """Return (added, removed, version_changes, hash_changes) keyed by (from, to)."""
    old = {(m['from'], m['to']): m for m in old_models}
    new = {(m['from'], m['to']): m for m in new_models}
    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    version_changes, hash_changes = [], []
    for pair in sorted(set(old) & set(new)):
        if old[pair]['version'] != new[pair]['version']:
            version_changes.append((pair, old[pair]['version'], new[pair]['version']))
            continue
        old_files = {f['name']: (f['sha256'], f['size'], f['url']) for f in old[pair]['files']}
        new_files = {f['name']: (f['sha256'], f['size'], f['url']) for f in new[pair]['files']}
        if old_files != new_files:
            changed = sorted(set(old_files) ^ set(new_files) | {n for n in old_files if n in new_files and old_files[n] != new_files[n]})
            hash_changes.append((pair, changed))
    return added, removed, version_changes, hash_changes


def summarize(old_models, new_models, timestamp):
    added, removed, version_changes, hash_changes = diff(old_models, new_models)
    new = {(m['from'], m['to']): m for m in new_models}
    print('Snapshot: {} pairs / {} files -> {} pairs / {} files (collection timestamp {})'.format(
        len(old_models), sum(len(m['files']) for m in old_models),
        len(new_models), sum(len(m['files']) for m in new_models), timestamp))
    print('Added pairs ({}):'.format(len(added)), ', '.join('{}->{} {}'.format(f, t, new[(f, t)]['version']) for f, t in added) or '-')
    print('Removed pairs ({}):'.format(len(removed)), ', '.join('{}->{}'.format(f, t) for f, t in removed) or '-')
    print('Version changes ({}):'.format(len(version_changes)),
          ', '.join('{}->{} {}->{}'.format(f, t, o, n) for (f, t), o, n in version_changes) or '-')
    print('Hash-only changes ({}):'.format(len(hash_changes)),
          ', '.join('{}->{} [{}]'.format(f, t, ' '.join(names)) for (f, t), names in hash_changes) or '-')
    return bool(added or removed or version_changes or hash_changes)


def main():
    parser = argparse.ArgumentParser(description='Rebuild registry.json from Mozilla Remote Settings.')
    parser.add_argument('--check', action='store_true', help='report drift against the existing file; exit 1 if any')
    parser.add_argument('--url', default=DEFAULT_URL, help='changeset endpoint (default: Mozilla translations-models)')
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT, help='registry file (default: registry.json at repo root)')
    args = parser.parse_args()

    records, timestamp = fetch(args.url)
    models, notes = select(records)
    for note in notes:
        print('note: ' + note, file=sys.stderr)
    existing = load_existing(args.output)
    drift = summarize(existing.get('models', []), models, timestamp)

    if args.check:
        if drift:
            print('registry.json is stale; run tools/distribution/fetch_registry.py', file=sys.stderr)
            return 1
        print('registry.json is up to date')
        return 0

    data = {'_comment': COMMENT.format(date=datetime.date.today().isoformat()), 'models': models}
    with open(args.output, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write('\n')
    print('wrote {}'.format(args.output))
    return 0


if __name__ == '__main__':
    sys.exit(main())
