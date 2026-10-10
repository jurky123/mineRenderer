"""Secondary module/World ABBA analysis; never edits or upgrades raw suite validity."""
import argparse,hashlib,json,pathlib,zipfile

def analyze(path,timing):
    with zipfile.ZipFile(path) as z:summary=json.loads(z.read('summary.json'))
    groups=[]
    for original in summary.get('comparisons',[]):
        name=original['name'];blocks=sorted((b for b in summary['blocks'] if b['plan']['comparison']==name),key=lambda b:(b['plan']['round'],b['plan']['position']))
        result={'name':name,'timing':timing,'originalComparison':original,'verdict':'not_comparable'}
        if not summary.get('completed') or len(blocks)!=8 or any(not b.get('valid') for b in blocks) or original['verdict'] in ['incomplete','not_comparable','correctness_failed']:
            result['reason']='Original suite validity/comparability does not permit secondary acceptance';groups.append(result);continue
        values=[]
        for i,b in enumerate(blocks):
            if b['plan']['round']!=i//4 or b['plan']['position']!=i%4 or b['plan']['candidate']!=(i%4 in [1,2]):break
            key='vulkan_rt_batch_'+b['plan']['config']['queue'].lower() if timing=='rt_batch' else timing
            t=b['timings'].get(key,{})
            if t.get('samples',0)<30 or t.get('medianMs') is None:break
            values.append(t['medianMs'])
        if len(values)!=8:result['reason']='Missing timestamps or ABBA order';groups.append(result);continue
        gains=[];noise=0
        for r in range(2):
            a0,b0,b1,a1=values[r*4:r*4+4];a=(a0+a1)/2;b=(b0+b1)/2
            if a<=0 or b<=0:break
            gains.append((a-b)/a*100);noise=max(noise,abs(a0-a1)/a*100,abs(b0-b1)/b*100)
        if len(gains)!=2:result['reason']='Nonpositive timing';groups.append(result);continue
        gain=sum(gains)/2;threshold=max(3,noise)
        verdict='not_comparable' if noise>10 else 'candidate_faster' if all(g>threshold for g in gains) else 'candidate_slower' if all(g< -threshold for g in gains) else 'within_variation' if abs(gain)<=threshold else 'inconsistent'
        result.update(verdict=verdict,improvementPercent=gain,repeatVariationPercent=noise,roundImprovementsPercent=gains,thresholdPercent=threshold)
        groups.append(result)
    return {'source':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'scope':'secondary analysis of unchanged raw suite; original validity required; original numeric ABBA thresholds; descriptive, not a confidence interval','comparisons':groups}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('zip',type=pathlib.Path);parser.add_argument('--timing',default='vulkan_world_total');parser.add_argument('--output',type=pathlib.Path,required=True);args=parser.parse_args()
    result=analyze(args.zip,args.timing);args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result['comparisons'],indent=2))
