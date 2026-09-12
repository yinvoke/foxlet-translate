"""Versioned measurement dimensions shared by the runner and regression gate."""
import hashlib
import json
from pathlib import Path
import re


DEFAULT_SUITE = 'android-native-v1'


def load_suite(suite_id=DEFAULT_SUITE):
    if not re.fullmatch(r'[a-z0-9-]+', suite_id):
        raise ValueError('invalid benchmark suite id')
    path = Path(__file__).with_name('suites') / f'{suite_id}.json'
    suite = json.loads(path.read_text())
    if suite['id'] != suite_id:
        raise ValueError('benchmark suite id does not match its filename')
    scenarios = {s['id']: {k: v for k, v in s.items() if k != 'id'}
                 for s in suite['scenarios']}
    if not scenarios or len(scenarios) != len(suite['scenarios']):
        raise ValueError('empty or duplicate benchmark scenarios')
    digest = hashlib.sha256(json.dumps(suite, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return suite, scenarios, digest
