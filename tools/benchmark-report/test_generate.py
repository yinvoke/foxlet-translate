import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from generate import app_data


class ExistingDataTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.path=Path(self.temp.name)
        outputs=['translation']*200
        sha=hashlib.sha256(('\n'.join(outputs)+'\n').encode()).hexdigest()
        self.raw={'status':'complete','input_count':200,'corpus_sha256':'corpus','passes':[{'pass':i,'elapsed_ms':ms,'outputs':outputs,'output_sha256':sha,'metrics':{'peakPssMb':100+i}} for i,ms in enumerate([1200,800,1000])]}
        self.report={'suite_id':'android-app-v2','status':'failed','corpus_sha256':{'enzh':'corpus','jazh':'other'},'runs':[{'round':1,'direction':'enzh','engine':'bergamot','threads':1,'status':'complete','result_file':'raw.json'}]}
        (self.path/'raw.json').write_text(json.dumps(self.raw));(self.path/'results.json').write_text(json.dumps(self.report))
        (self.path/'scores.json').write_text(json.dumps({'scored_outputs':[{'direction':'enzh','output_sha256':sha,'comet_x100':80}]}))

    def test_partial_data_stays_partial_and_is_read_only(self):
        before={f.name:f.read_bytes() for f in self.path.iterdir()}
        data=app_data(self.path,self.path/'scores.json')
        self.assertFalse(data['complete']);self.assertEqual(data['source_status'],'failed')
        row=next(r for r in data['rows'] if r['engine']=='bergamot' and r['threads']==1 and r['direction']=='enzh')
        self.assertEqual(row['completed_processes'],1);self.assertEqual(row['warm_ms'],900)
        self.assertEqual(row['warm_pss_mib'],102);self.assertEqual(row['comet_x100'],[80])
        self.assertEqual(sum(r['completed_processes'] for r in data['rows']),1)
        self.assertEqual(before,{f.name:f.read_bytes() for f in self.path.iterdir()})

    def test_missing_quality_is_not_silently_filled(self):
        data=app_data(self.path,self.path/'missing.json')
        row=next(r for r in data['rows'] if r['completed_processes'])
        self.assertEqual(row['comet_x100'],[]);self.assertTrue(row['unscored_output_hashes'])

    def test_changed_output_is_rejected(self):
        self.raw['passes'][0]['outputs'][0]='changed'
        (self.path/'raw.json').write_text(json.dumps(self.raw))
        with self.assertRaises(AssertionError):app_data(self.path,self.path/'scores.json')

    def test_duplicate_round_is_rejected(self):
        self.report['runs']*=2
        (self.path/'results.json').write_text(json.dumps(self.report))
        with self.assertRaises(AssertionError):app_data(self.path,self.path/'scores.json')


if __name__=='__main__':unittest.main()
