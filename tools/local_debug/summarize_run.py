"""Build compact stage summaries from an archived, validated client run."""
import argparse, csv, datetime, hashlib, importlib.util, json, pathlib
from paired_runs import CAMERAS, properties

def digest(data):return hashlib.sha256(data).hexdigest()
def utc(value):return datetime.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('archive',type=pathlib.Path);ap.add_argument('--pair',type=int,required=True);ap.add_argument('--source-commit',required=True);args=ap.parse_args()
    folder=args.archive.resolve();record=json.loads((folder/'process.json').read_text(encoding='utf-8'));renderer=record['renderer']
    spec=importlib.util.spec_from_file_location('profile',pathlib.Path(__file__).parents[1]/'cross_renderer_profile.py');profile=importlib.util.module_from_spec(spec);spec.loader.exec_module(profile)
    validation=json.loads((folder/'observation-validation.json').read_text(encoding='utf-8'))
    if not validation['valid']:raise ValueError('Invalid observation')
    candidates=[p for p in (folder/'raw').glob('rt-suite-*') if p.is_dir()] if renderer=='voxellight' else []
    for camera in CAMERAS:
        begin=properties(folder/f'{renderer}-{camera}-sample.begin.properties');end=properties(folder/f'{renderer}-{camera}-sample.end.properties')
        pose={key:begin[key] for key in ('actualCamera.pos','actualCamera.xRot','actualCamera.yRot','actualCamera.projectionMatrix','actualCamera.viewRotationMatrix')}
        excluded=[];block=None
        if renderer=='caustica':
            start,stop=int(begin['gpuProfileFrame']),int(end['gpuProfileFrame'])
            source=folder/'raw/gpu-graphics.csv'
            render=[int(begin['renderW']),int(begin['renderH'])];display=[int(begin['displayW']),int(begin['displayH'])]
            source_proof=json.loads((folder/'source-identity.json').read_text(encoding='utf-8'))['sha256']
        else:
            matches=[p for p in candidates if utc(begin['utc'])<=int(p.name.removeprefix('rt-suite-'))/1000<=utc(end['utc'])]
            if len(matches)!=1:raise ValueError(f'{camera}: expected one suite, found {len(matches)}')
            suite=matches[0];block_path=suite/'01-production-r1-p1-A.json';block=json.loads(block_path.read_text(encoding='utf-8'))
            if block.get('valid') is not True or block.get('reasons'):raise ValueError(f'{camera}: invalid benchmark block')
            start,stop=block['firstFrame'],block['lastFrame']+1
            source=block_path.with_suffix('.passes.csv');excluded=json.loads(block_path.with_suffix('.counter_frames.json').read_text(encoding='utf-8'))
            render=[block['state']['width'],block['state']['height']];display=[1048,672]
            source_proof=digest((folder/'patch-build.json').read_bytes())
        raw_world_clock_time=int(begin['scene.dayTime'])
        controls={'display_size':display,'render_size':render,'spp':1,'rr_mode':'performance(0)','fg':False,'vsync':False,'frame_cap':int(begin['frameLimit']),'path_budget':{'max_radiance_bounce':5},'world_clock_phase_ticks':raw_world_clock_time % 24000,'warmup_seconds':int(begin['warmupSeconds'])}
        # Fixture content is identical across isolated clients; preserve observed poses,
        # not the commanded target, in pairing identity.
        meta={'run_id':f'{renderer}-{args.pair}-{camera}','pair_id':args.pair,'scene_id':camera,'scene_signature':None,
              'camera':pose,'camera_signature':digest(json.dumps(pose,sort_keys=True).encode()),'artifact_sha256':record['artifactSha256'],
              'source_commit':args.source_commit,'source_proof':source_proof,'sampling_mode':record['samplingMode'],
              'process_identity':{'pid':record['pid'],'creation_filetime':record['creationFiletime']},
              'valid':True,'frame_start':start,'frame_end':stop,'controls':controls,'raw_world_clock_time':raw_world_clock_time,'counter_frames_excluded':renderer=='voxellight',
              'lighting_comparability':'native defaults differ; these comparisons do not establish equal lighting work',
              'actual_sky':{k:v for k,v in begin.items() if k.startswith(('actualSky.','actualLighting.'))},'benchmark_block':block}
        fixture=folder/'fixture-identity.json'
        if not fixture.exists():raise ValueError('Missing archived fixture identity')
        meta['scene_signature']=json.loads(fixture.read_text(encoding='utf-8'))['sha256']
        result=profile.normalize(renderer,source,meta,excluded)
        if renderer=='caustica':
            compute=folder/'raw/gpu-compute.csv'
            result['samples'].extend(profile.normalize(renderer,compute,meta)['samples'])
            result['auxiliary_sources']={str(compute):digest(compute.read_bytes())}
            for filename,frame_column,stage_column,queue in [('async-cpu.csv','render_frame_at_completion','stage','worker'),('client-frame-interval.csv','frame',None,'graphics')]:
                auxiliary=folder/'raw'/filename
                result['auxiliary_sources'][str(auxiliary)]=digest(auxiliary.read_bytes())
                with auxiliary.open(encoding='utf-8-sig',newline='') as stream:
                    for row in csv.DictReader(stream):
                        frame=int(row[frame_column])
                        if not start<=frame<stop:continue
                        result['samples'].append({'frame':frame,'scope_id':int(row.get('event_id',frame)),'parent_scope_id':0,'stage':row[stage_column] if stage_column else 'client_frame_interval','domain':'cpu','queue':queue,'elapsed_ns':int(row['cpu_ns']),'semantics':'CPU interval; completion-frame association, not GPU critical path'})
            result['async_association']='compute uses CPU recording frame; CPU jobs use completion frame; overlapping durations must not be summed'
        result['stage_summary']=profile.summarize(result);result['sample_count']=len(result.pop('samples'));result['source_file']=str(source)
        (folder/f'{camera}-summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(camera,result['sample_count'],flush=True)

if __name__=='__main__':main()
