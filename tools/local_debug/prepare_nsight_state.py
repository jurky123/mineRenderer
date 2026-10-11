"""Clone the formal fixture into an isolated, counter-free Nsight diagnostic."""
import argparse,json,pathlib,shutil,subprocess,sys

def main(root,renderer):
 formal=root/f'work/alpha50-{renderer}-formal';state=root/f'work/alpha50-{renderer}-nsight'
 if state.exists():raise ValueError('Preserve existing Nsight state')
 state.mkdir()
 for name in ('game','natives','fixture'):
  shutil.copytree(formal/name,state/name,ignore=shutil.ignore_patterns('benchmark-results','rt-frame-stats','logs','screenshots','crash-reports'))
 for name in ('debug-agent.jar','agent-identity.json','fixture-identity.json','options-observed.json','source-identity.json'):
  if (formal/name).exists():shutil.copy2(formal/name,state/name)
 args=[value.replace(str(formal),str(state)).replace(formal.as_posix(),state.as_posix()) for value in json.loads((formal/'base-args.json').read_text(encoding='utf-8'))]
 args.insert(0,'-Dvoxellight.debug.diagnosticMode=nsight')
 if renderer=='voxellight':args.insert(0,'-Dvoxellight.rt.nsightLabels=true')
 (state/'base-args.json').write_text(json.dumps(args,indent=2),encoding='utf-8')
 info=json.loads((formal/'renderer.json').read_text(encoding='utf-8'));base=info['artifact'];info['artifact']=str(state/'game/mods'/pathlib.Path(base).name)
 (state/'renderer.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
 if renderer=='voxellight':
  subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('build_profile_overlay.py')),'--base',base,'--state-dir',str(state),'--shader-bank',str(root/'work/mineRenderer/build/vulkan-rt-alpha50-baseline-final')],check=True)
  provenance=json.loads((state/'patch-build.json').read_text(encoding='utf-8'));provenance['formalTimingEligible']=False;provenance['diagnosticMode']='nsight'
  (state/'patch-build.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8')
 (state/'diagnostic-identity.json').write_text(json.dumps({'sampling_mode':'nsight','formal_timing_eligible':False,'fixture_clone':str(formal)},indent=2),encoding='utf-8')
 print(state,flush=True)

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--workspace',type=pathlib.Path,required=True);ap.add_argument('--renderer',choices=('caustica','voxellight'),required=True);a=ap.parse_args();main(a.workspace.resolve(),a.renderer)
