"""Normalize independently archived diagnostic windows, never formal timings."""
import argparse,json,pathlib,sys,hashlib
from paired_runs import CAMERAS,properties
from summarize_run import utc
sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
from cross_renderer_workload import normalize,normalize_as

def main(root):
 results=[]
 for renderer in ('caustica','voxellight'):
  folder=root/renderer
  if not json.loads((folder/'observation-validation.json').read_text())['valid']:raise ValueError('Invalid observations')
  record=json.loads((folder/'process.json').read_text())
  if record['samplingMode']!='counters' or record['formalTimingEligible']:raise ValueError('Not counter diagnostics')
  for camera in CAMERAS:
   begin=properties(folder/f'{renderer}-{camera}-sample.begin.properties');end=properties(folder/f'{renderer}-{camera}-sample.end.properties')
   if renderer=='caustica':
    start,stop=int(begin['gpuProfileFrame']),int(end['gpuProfileFrame']);source=folder/'raw/work-counters.csv';extra=None
    status=properties(folder/'raw/work-counter-status.properties')
    if status.get('skippedFrames')!='0':raise ValueError('Counter frames skipped')
   else:
    matches=[p for p in (folder/'raw').glob('rt-suite-*') if p.is_dir() and utc(begin['utc'])<=int(p.name[9:])/1000<=utc(end['utc'])]
    if len(matches)!=1:raise ValueError('Expected one current suite')
    block_path=matches[0]/'01-production-r1-p1-A.json';block=json.loads(block_path.read_text())
    if not block['valid'] or block['reasons']:raise ValueError('Invalid diagnostic block')
    start,stop=block['firstFrame'],block['lastFrame']+1
    source=block_path.with_suffix('.work.csv');extra=block_path.with_suffix('.rays.csv')
   result=normalize(renderer,source,start,stop,extra)
   result['source_files']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (source,extra) if p is not None}
   if not result['frames']:raise ValueError('Empty counter window')
   result.update(scene_id=camera,frame_start=start,frame_end=stop,process_identity={'pid':record['pid'],'creation_filetime':record['creationFiletime']},artifact_sha256=record['artifactSha256'])
   result['as_counts']=normalize_as(folder/'as-records.csv',start,stop)
   (folder/f'{camera}-work-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8');results.append(result)
 (root/'workload-comparison.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
 for camera in CAMERAS:
  pair={r['renderer']:r for r in results if r['scene_id']==camera}
  print(camera,json.dumps({renderer:{k:v['median_per_frame'] for k,v in r['counters'].items() if v['available']} for renderer,r in pair.items()}))

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('root',type=pathlib.Path);main(ap.parse_args().root.resolve())
