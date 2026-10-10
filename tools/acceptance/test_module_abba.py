import json,pathlib,tempfile,unittest,zipfile
from module_abba import analyze

class ModuleAbbaEvidenceTest(unittest.TestCase):
    def suite(self):
        name='module';blocks=[]
        for i in range(8):
            candidate=i%4 in [1,2]
            blocks.append({'plan':{'comparison':name,'round':i//4,'position':i%4,'candidate':candidate,'config':{'queue':'FIXED'}},'valid':True,'timings':{'vulkan_world_total':{'samples':100,'medianMs':8 if candidate else 10}}})
        return {'completed':True,'comparisons':[{'name':name,'verdict':'candidate_faster'}],'blocks':blocks}
    def run_analysis(self,s):
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d)/'raw.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('summary.json',json.dumps(s))
            return analyze(p,'vulkan_world_total')['comparisons'][0]
    def test_invalid_original_evidence_cannot_be_upgraded(self):
        s=self.suite();s['blocks'][3]['valid']=False
        self.assertEqual('not_comparable',self.run_analysis(s)['verdict'])
        s=self.suite();s['comparisons'][0]['verdict']='correctness_failed'
        self.assertEqual('not_comparable',self.run_analysis(s)['verdict'])
    def test_missing_world_timestamp_is_not_invented_as_zero(self):
        s=self.suite();s['blocks'][2]['timings']={}
        self.assertEqual('not_comparable',self.run_analysis(s)['verdict'])
    def test_malformed_abba_order_cannot_be_accepted(self):
        s=self.suite();s['blocks'][1]['plan']['position']=3
        self.assertEqual('not_comparable',self.run_analysis(s)['verdict'])
