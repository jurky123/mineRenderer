"""Clone isolated fixtures and package counter diagnostics without changing timing jars."""
import argparse,hashlib,json,pathlib,shutil,subprocess,sys

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main(root,resume=False):
    products=root/'outputs/alpha.50/构建产物'
    caustica=root/'work/Caustica/build/libs'
    counter=products/'caustica-material-as-counters.jar'
    sources=products/'caustica-material-as-counters-sources.jar'
    shutil.copy2(caustica/'caustica-0.1.0.jar',counter)
    shutil.copy2(caustica/'caustica-0.1.0-sources.jar',sources)
    for renderer in ('caustica','voxellight'):
        formal=root/f'work/alpha50-{renderer}-formal';state=root/f'work/alpha50-{renderer}-counters'
        if state.exists() and not resume:raise ValueError('Preserve existing diagnostic state: '+str(state))
        if resume and (state/'diagnostic-identity.json').exists():continue
        state.mkdir(exist_ok=resume)
        for folder in ('game','natives','fixture'):
            shutil.copytree(formal/folder,state/folder,dirs_exist_ok=resume,ignore=shutil.ignore_patterns('benchmark-results','rt-frame-stats','logs','screenshots','crash-reports'))
        for filename in ('debug-agent.jar','agent-identity.json','fixture-identity.json','options-observed.json'):
            shutil.copy2(formal/filename,state/filename)
        original=json.loads((formal/'renderer.json').read_text(encoding='utf-8'))
        original['artifact']=str(state/'game/mods'/pathlib.Path(original['artifact']).name)
        args=[value.replace(str(formal),str(state)).replace(formal.as_posix(),state.as_posix()) for value in json.loads((formal/'base-args.json').read_text(encoding='utf-8'))]
        args.insert(0,f'-D{renderer}.rt.workProfile=true')
        (state/'base-args.json').write_text(json.dumps(args,indent=2),encoding='utf-8')
        if renderer=='caustica':
            shutil.copy2(counter,original['artifact']);original['sha256']=sha(counter)
            (state/'source-identity.json').write_text(json.dumps({'sourceJar':str(sources),'sha256':sha(sources)},indent=2),encoding='utf-8')
        (state/'renderer.json').write_text(json.dumps(original,indent=2),encoding='utf-8')
        if renderer=='voxellight':
            base=pathlib.Path(json.loads((formal/'renderer.json').read_text(encoding='utf-8'))['artifact'])
            subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('build_profile_overlay.py')),'--base',str(base),'--state-dir',str(state),'--shader-bank',str(root/'work/mineRenderer/build/vulkan-rt-profile-counters-final')],check=True)
        (state/'diagnostic-identity.json').write_text(json.dumps({'sampling_mode':'counters','formal_timing_eligible':False,'fixture_clone':str(formal),'artifact_sha256':sha(pathlib.Path(original['artifact']))},indent=2),encoding='utf-8')
        print(renderer,state,flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--workspace',type=pathlib.Path,required=True);parser.add_argument('--resume',action='store_true');args=parser.parse_args();main(args.workspace.resolve(),args.resume)
