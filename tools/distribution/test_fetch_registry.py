"""Offline regression coverage for the catalog refresh used before release."""
import copy
import json
import unittest

import fetch_registry as registry


class RegistryTest(unittest.TestCase):
    def setUp(self):
        fixture = registry.ROOT / 'foxlet/src/test/resources/io/github/yinvoker/foxlet/changeset-sample.json'
        self.records = json.loads(fixture.read_text())['changes']

    def test_fixture_matches_the_bundled_catalog(self):
        models, _ = registry.select(self.records)
        bundled = json.loads(registry.DEFAULT_OUTPUT.read_text())['models']
        pairs = {(m['from'], m['to']) for m in models}
        self.assertEqual(models, [m for m in bundled if (m['from'], m['to']) in pairs])
        self.assertEqual(4, len(models))

    def test_bad_record_types_are_skipped(self):
        good = next(r for r in self.records if registry.is_eligible(r))
        for field, value in [('fileType', []), ('fromLang', ' '), ('id', '')]:
            bad = dict(good, **{field: value})
            self.assertFalse(registry.is_eligible(bad))
        for size in [True, 1.5, 0, 1024**3 + 1]:
            bad = copy.deepcopy(good)
            bad['attachment']['size'] = size
            self.assertFalse(registry.is_eligible(bad))
        for bad in [None, 42, [], 'record']:
            self.assertFalse(registry.is_eligible(bad))

    def test_bad_layout_falls_back_to_a_complete_version(self):
        records = copy.deepcopy(self.records)
        for record in records:
            if (record.get('fromLang'), record.get('toLang'), record.get('version'), record.get('fileType')) == ('en', 'zh-Hans', '2.2', 'model'):
                record['name'] = 'weights.bin'
        models, notes = registry.select(records)
        model = next(m for m in models if (m['from'], m['to']) == ('en', 'zh-Hans'))
        self.assertEqual('2.1', model['version'])
        self.assertTrue(any('unresolvable' in note for note in notes))

    def test_duplicate_names_do_not_publish_an_ambiguous_bundle(self):
        records = [copy.deepcopy(r) for r in self.records
                   if r.get('fromLang') == 'en' and r.get('toLang') == 'zh-Hans' and r.get('version') == '2.2']
        records[0]['name'] = records[1]['name']
        self.assertEqual('duplicate asset name', registry.completeness(records))
        self.assertEqual([], registry.select(records)[0])


if __name__ == '__main__':
    unittest.main()
