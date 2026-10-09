"""Collect local automation evidence and summarize exported benchmark verdicts."""
import argparse, datetime, hashlib, json, pathlib, shutil, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--state-dir',type=pathlib.Path,default=ROOT/'work/automation');parser.add_argument('--output-dir',type=pathlib.Path,default=ROOT/'outputs/local-debug-results');args=parser.parse_args()
HERE=args.state_dir.resolve()
OUT=args.output_dir.resolve()
OUT.mkdir(parents=True,exist_ok=True)
GAME=HERE/'game'
report={'collectedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Automatic isolated singleplayer debugging of installed alpha.49. No core bytecode transformations or benchmark-gate changes. Initial production used an incomplete cleanup fixture; subsequent suites use corrected cleanup.','suites':[],'incompleteRuns':[]}
for path in sorted((GAME/'benchmark-results/voxellight').glob('rt-suite-*.zip')):
    with zipfile.ZipFile(path) as z:
        summary=json.loads(z.read('summary.json'))
    result={'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'suite':summary['suite'],'testGroup':summary['blocks'][0]['plan']['comparison'] if summary['blocks'] else 'no sampled blocks','completed':summary['completed'],'interruption':summary.get('interruption'),'validBlocks':sum(b.get('valid',False) for b in summary['blocks']),'totalBlocks':len(summary['blocks']),'comparisons':summary.get('comparisons',[]),'blocks':[]}
    for b in summary['blocks']:
        metrics=b.get('timings',{})
        result['blocks'].append({'plan':b['plan'],'valid':b.get('valid'),'reasons':b.get('reasons',[]),'state':b.get('state'),'timings':{k:v for k,v in metrics.items() if k in ['vulkan_world_total','vulkan_rt_scene_commit','vulkan_rt_deterministic_guides','vulkan_rt_dlss_rr','cpu:client_frame_interval','cpu:vulkan_rt_scene_commit'] or k.startswith('vulkan_rt_batch')}})
    report['suites'].append(result);shutil.copy2(path,OUT/path.name)
for marker in (GAME/'benchmark-results/voxellight').glob('rt-suite-*/automation-interruption.json'):
    evidence=json.loads(marker.read_text());evidence['directory']=marker.parent.name;report['incompleteRuns'].append(evidence)
for name in ['agent-events.log','launch-output.log','production-agent-events.log','production-launch-output.log','guides-transport-agent-events.log','guides-transport-launch-output.log','watchdog-output.log']:
    if (HERE/name).exists():shutil.copy2(HERE/name,OUT/name)
for path in (GAME/'benchmark-results/voxellight').glob('rr-guides-*.zip'):shutil.copy2(path,OUT/path.name)
for path in (GAME/'screenshots').glob('*.png'):
    (OUT/'screenshots').mkdir(exist_ok=True);shutil.copy2(path,OUT/'screenshots'/path.name)
for pattern in ('threads-*.txt','watchdog-*.json','watchdog-*-threads.txt'):
    for path in HERE.glob(pattern):shutil.copy2(path,OUT/path.name)
(OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps([{k:s[k] for k in ['file','suite','completed','validBlocks','totalBlocks','comparisons']} for s in report['suites']],ensure_ascii=False,indent=2))
