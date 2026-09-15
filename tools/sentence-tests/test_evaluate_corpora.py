"""Check scoring against known boundaries, not against splitter implementation."""
import unittest
from unittest.mock import patch
import evaluate_corpora as ev


class EvaluationTests(unittest.TestCase):
    def test_uses_untokenized_gold_text(self):
        data = '# sent_id = a\n# text = can’t go.\n1-2\tcan’t\t_\n1\tca\t_\n2\tn’t\t_\n\n'
        self.assertEqual(ev.sentences(data.encode()), [{'id': 'a', 'text': 'can’t go.'}])

    def test_missing_gold_is_not_silently_reconstructed(self):
        with self.assertRaises(ValueError):
            ev.sentences(b'# sent_id = a\n1\tWord\t_\n\n')

    def test_annotated_groups_never_join_different_paragraphs(self):
        items = [{'id': 'a', 'text': 'One.', 'starts_group': True},
                 {'id': 'b', 'text': 'Two.'},
                 {'id': 'c', 'text': 'Three.', 'starts_group': True}]
        self.assertEqual([x['text'] for x in ev.annotated_blocks(items, 'en')], ['One. Two.', 'Three.'])
        self.assertIsNone(ev.annotated_blocks([{'id': 'd', 'text': 'Alone.'}], 'en'))

    def test_cjk_blocks_preserve_utf8_offsets_and_hide_gold_newlines(self):
        items = [{'id': 'a', 'text': '甲。'}, {'id': 'b', 'text': '乙。'}]
        block = ev.blocks(items, 'zh')[0]
        self.assertEqual(block['text'], '甲。乙。')
        self.assertEqual(block['spans'], [(0, 6), (6, 12)])
        self.assertEqual(ev.blocks(items, 'ko')[0]['spans'], [(0, 6), (7, 13)])

    def test_ends_of_blocks_do_not_inflate_boundary_f1(self):
        items = [{'id': 'a', 'text': 'One.'}, {'id': 'b', 'text': 'Two.'}]
        with patch.object(ev, 'predict', return_value=[[(0, 9)], [(0, 4)], [(0, 4)]]):
            score = ev.evaluate(None, 'en', items)
        self.assertEqual((score['tp'], score['fp'], score['fn']), (0, 0, 1))
        self.assertEqual(score['f1'], 0)
        self.assertEqual(score['isolated_false_split_sentences'], 0)

    def test_extra_boundaries_and_exact_spans_are_counted(self):
        items = [{'id': 'a', 'text': 'Dr. A.'}, {'id': 'b', 'text': 'End.'}]
        with patch.object(ev, 'predict', return_value=[[(0, 3), (4, 6), (7, 11)], [(0, 3), (4, 6)], [(0, 4)]]):
            score = ev.evaluate(None, 'en', items)
        self.assertEqual((score['tp'], score['fp'], score['fn']), (1, 1, 0))
        self.assertEqual(score['exact_sentence_spans'], 1)
        self.assertEqual(score['isolated_false_split_sentences'], 1)
        self.assertEqual(score['isolated_extra_boundaries'], 1)

    def test_whitespace_is_normalized_without_moving_cjk_offsets(self):
        response = type('Response', (), {'stdout': '0:9 9:15\n'})()
        with patch.object(ev.subprocess, 'run', return_value=response):
            self.assertEqual(ev.predict('cli', 'zh', ['甲。　乙。']), [[(0, 6), (9, 15)]])

    def test_dropped_text_is_rejected(self):
        response = type('Response', (), {'stdout': '0:1\n'})()
        with patch.object(ev.subprocess, 'run', return_value=response):
            with self.assertRaises(ValueError):
                ev.predict('cli', 'en', ['Full text.'])


if __name__ == '__main__':
    unittest.main()
