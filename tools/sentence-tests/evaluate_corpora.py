#!/usr/bin/env python3
"""Reproducible sentence-boundary evaluation; downloaded text stays in build/.

Original gold sentences measure false splitting. Separate synthetic 8-sentence
blocks measure both missed and extra boundaries, without giving the splitter
gold newlines. These blocks are NOT claimed to be original source paragraphs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name('corpora.json')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def sentences(data):
    result = []
    for block in data.decode('utf-8').strip().split('\n\n'):
        comments = dict(line[2:].split(' = ', 1) for line in block.splitlines()
                        if line.startswith('# ') and ' = ' in line)
        if not comments and not block.strip():
            continue
        text = comments.get('text', '').strip()
        if not text or 'sent_id' not in comments:
            raise ValueError('Expected nonempty # text and # sent_id; no token-based text reconstruction')
        item = {'id': comments['sent_id'], 'text': text}
        if any(line.startswith(('# newdoc', '# newpar')) for line in block.splitlines()):
            item['starts_group'] = True
        result.append(item)
    return result


def blocks(items, language, window=8):
    separator = '' if language in ('zh', 'ja') else ' '
    result = []
    for at in range(0, len(items), window):
        selected = items[at:at + window]
        text = separator.join(item['text'] for item in selected)
        spans, offset = [], 0
        for item in selected:
            end = offset + len(item['text'].encode())
            spans.append((offset, end))
            offset = end + len(separator)
        result.append({'text': text, 'spans': spans, 'ids': [item['id'] for item in selected]})
    return result


def annotated_blocks(items, language):
    """Respect explicit newdoc/newpar groups; never guess documents from IDs."""
    if not any(item.get('starts_group') for item in items):
        return None
    groups, pending = [], []
    for item in items:
        if item.get('starts_group') and pending:
            groups.extend(blocks(pending, language, window=len(pending)))
            pending = []
        pending.append(item)
    if pending:
        groups.extend(blocks(pending, language, window=len(pending)))
    return groups


def predict(binary, language, texts, unicode=False):
    command = [str(binary)] + (['--unicode'] if unicode else [])
    request = ''.join(language + '\t' + text.encode().hex() + '\n' for text in texts)
    result = subprocess.run(command, input=request, text=True, capture_output=True, check=True)
    lines = result.stdout.splitlines()
    if len(lines) != len(texts):
        raise ValueError('Native CLI response count mismatch')
    normalized = []
    for text, line in zip(texts, lines):
        data = text.encode()
        spans, previous = [], 0
        for pair in line.split():
            start, end = map(int, pair.split(':'))
            if not previous <= start < end <= len(data):
                raise ValueError('Invalid native byte span')
            if data[previous:start].decode().strip():
                raise ValueError('Native splitter dropped non-whitespace text')
            body = data[start:end].decode()
            left = len(body) - len(body.lstrip())
            stripped = body.strip()
            if stripped:
                begin = start + len(body[:left].encode())
                spans.append((begin, begin + len(stripped.encode())))
            previous = end
        if data[previous:].decode().strip():
            raise ValueError('Native splitter dropped the end of input')
        normalized.append(spans)
    return normalized


def scores(tp, fp, fn):
    return {'tp': tp, 'fp': fp, 'fn': fn,
            'precision': tp / (tp + fp) if tp + fp else None,
            'recall': tp / (tp + fn) if tp + fn else None,
            'f1': 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None}


def evaluate(binary, language, items, unicode=False, errors=None, source='', annotated=False):
    joined = annotated_blocks(items, language) if annotated else blocks(items, language)
    if joined is None:
        return None
    predictions = predict(binary, language, [b['text'] for b in joined] +
                          [item['text'] for item in items], unicode)
    tp = fp = fn = exact = 0
    for block, spans in zip(joined, predictions[:len(joined)]):
        # Exclude the forced final boundary of every block from P/R/F1.
        size = len(block['text'].encode())
        gold = {end for _, end in block['spans'] if end != size}
        predicted = {end for _, end in spans if end != size}
        tp += len(gold & predicted)
        fp += len(predicted - gold)
        fn += len(gold - predicted)
        exact += len(set(block['spans']) & set(spans))
        if errors is not None and gold != predicted:
            errors.write(json.dumps({'source': source, 'language': language, 'ids': block['ids'],
                'text': block['text'], 'gold': sorted(gold), 'predicted': sorted(predicted),
                'extra': sorted(predicted - gold), 'missed': sorted(gold - predicted)}, ensure_ascii=False) + '\n')
    isolated = predictions[len(joined):]
    return {**scores(tp, fp, fn), 'sentences': len(items), 'blocks': len(joined),
            'context': 'explicit_newdoc_newpar' if annotated else 'synthetic_consecutive_8',
            'exact_sentence_spans': exact, 'exact_sentence_rate': exact / len(items),
            'isolated_false_split_sentences': sum(len(s) != 1 for s in isolated),
            'isolated_extra_boundaries': sum(max(0, len(s) - 1) for s in isolated)}


def load(source, filename, directory, fetch=False):
    path = directory / source['id'] / filename
    metadata = source['files'][filename]
    if not path.exists() and fetch:
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(metadata['url'], timeout=90) as response:
            data = response.read()
        if sha(data) != metadata['sha256']:
            raise ValueError('Download fingerprint mismatch: ' + filename)
        path.write_bytes(data)
    data = path.read_bytes()
    if sha(data) != metadata['sha256']:
        raise ValueError('Cached fingerprint mismatch: ' + filename)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--split', choices=['dev', 'test'], required=True)
    parser.add_argument('--data', type=Path, default=ROOT / 'build/core-languages/corpora')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--errors', type=Path, help='Local-only error text; must be under build/')
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--unicode', action='store_true')
    args = parser.parse_args()
    if args.errors and not args.errors.resolve().is_relative_to((ROOT / 'build').resolve()):
        parser.error('Error excerpts must stay under build/; do not redistribute corpus text')
    manifest = json.loads(MANIFEST.read_text())
    result = {'collection': manifest['collection'], 'split': args.split,
              'profile': 'Unicode' if args.unicode else 'Translation',
              'binary_sha256': sha(args.binary.read_bytes()), 'manifest_sha256': sha(MANIFEST.read_bytes()),
              'method': 'Original sentences for false splits; synthetic consecutive blocks of 8, no separator for zh/ja and one space for en/ko/ru. No gold newlines. Forced final block boundaries excluded from P/R/F1. All sentences included; missing punctuation is not filtered out.',
              'sources': {}}
    errors = args.errors.open('w') if args.errors else None
    try:
        for source in manifest['sources']:
            filename = source['id'] + '-ud-' + args.split + '.conllu'
            if filename not in source['files']:
                continue
            if args.fetch:
                for name in ('README.md', 'LICENSE.txt'):
                    load(source, name, args.data, True)
            items = sentences(load(source, filename, args.data, args.fetch))
            stats = evaluate(args.binary.resolve(), source['language'], items, args.unicode, errors, source['id'])
            stats['annotated_context'] = evaluate(args.binary.resolve(), source['language'], items, args.unicode, annotated=True)
            result['sources'][source['id']] = {'language': source['language'], **stats}
            print(source['id'], 'sentences=' + str(stats['sentences']),
                  'F1=' + format(stats['f1'], '.4f'), 'FP=' + str(stats['fp']),
                  'FN=' + str(stats['fn']), 'isolated_split=' + str(stats['isolated_false_split_sentences']), flush=True)
    finally:
        if errors:
            errors.close()
    result['total'] = scores(*(sum(s[k] for s in result['sources'].values()) for k in ('tp', 'fp', 'fn')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
