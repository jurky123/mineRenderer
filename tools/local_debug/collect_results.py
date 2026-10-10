"""Collect local automation evidence and summarize exported benchmark verdicts."""
import argparse, datetime, hashlib, json, pathlib, shutil, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--state-dir',type=pathlib.Path,default=ROOT/'work/automation');parser.add_argument('--output-dir',type=pathlib.Path,default=ROOT/'outputs/local-debug-results');args=parser.parse_args()
HERE=args.state_dir.resolve()
OUT=args.output_dir.resolve()
OUT.mkdir(parents=True,exist_ok=True)
GAME=HERE/'game'
report={'collectedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Isolated local debugging; inspect artifact provenance and per-run validity before making comparisons.','suites':[],'incompleteRuns':[]}
terminations=list(HERE.glob('watchdog-*.json'))
if terminations:
    report['sessionTermination']=json.loads(max(terminations,key=lambda p:p.stat().st_mtime).read_text())
    # A suite may have exported successfully before restoration/capture later hangs.
    # Preserve both facts; session completion is not inferred from suite completion.
if (HERE/'patch-build.json').exists():report['artifact']=json.loads((HERE/'patch-build.json').read_text())
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
for directory in (GAME/'benchmark-results/voxellight').glob('rt-suite-*'):
    if not directory.is_dir() or directory.with_suffix('.zip').exists():continue
    markers=list(HERE.glob('watchdog-*.json'))
    if not markers:continue
    existing=directory/'automation-interruption.json'
    evidence=json.loads((existing if existing.exists() else max(markers,key=lambda p:p.stat().st_mtime)).read_text())
    evidence['directory']=directory.name
    if not any(r.get('directory')==directory.name for r in report['incompleteRuns']):report['incompleteRuns'].append(evidence)
    with zipfile.ZipFile(OUT/(directory.name+'-incomplete.zip'),'w',zipfile.ZIP_DEFLATED) as output:
        for path in directory.rglob('*'):
            if path.is_file():output.write(path,path.relative_to(directory))
        if not existing.exists():output.writestr('automation-interruption.json',json.dumps(evidence,indent=2))
for name in ['agent-events.log','launch-output.log','production-agent-events.log','production-launch-output.log','guides-transport-agent-events.log','guides-transport-launch-output.log','watchdog-output.log','gpu-telemetry.csv','telemetry-output.log','patch-build.json','process.json','fixture-verified.properties','pipeline-compiler.csv','pipeline-compiler-status.json','capture-states.log']:
    if (HERE/name).exists():shutil.copy2(HERE/name,OUT/name)
for path in (GAME/'benchmark-results/voxellight').glob('rr-guides-*.zip'):shutil.copy2(path,OUT/path.name)
for path in (GAME/'screenshots').glob('*.png'):
    (OUT/'screenshots').mkdir(exist_ok=True);shutil.copy2(path,OUT/'screenshots'/path.name)
for path in HERE.glob('caustica-*.properties'):shutil.copy2(path,OUT/path.name)
if (HERE/'renderer.json').exists():shutil.copy2(HERE/'renderer.json',OUT/'renderer.json')
if (GAME/'rt-frame-stats').exists():shutil.copytree(GAME/'rt-frame-stats',OUT/'rt-frame-stats',dirs_exist_ok=True)
for pattern in ('threads-*.txt','watchdog-*.json','watchdog-*-threads.txt','diagnostic-*-threads.txt'):
    for path in HERE.glob(pattern):shutil.copy2(path,OUT/path.name)
if (HERE/'run-history').exists():shutil.copytree(HERE/'run-history',OUT/'run-history',dirs_exist_ok=True)
(OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps([{k:s[k] for k in ['file','suite','completed','validBlocks','totalBlocks','comparisons']} for s in report['suites']],ensure_ascii=False,indent=2))
