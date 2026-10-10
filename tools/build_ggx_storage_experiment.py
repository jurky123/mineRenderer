"""Compile a fail-closed inline GGX reference bank for controlled storage A/B.
This is an experiment artifact, not the normal production build.
"""
import argparse,concurrent.futures,hashlib,json,pathlib,shutil,subprocess
from build_vulkan_rt import ROOT,STAGES,VARIANTS,tool,validate_byte_address_layout,validate_kernel_storage,validate_layout

def main():
 p=argparse.ArgumentParser();p.add_argument('--output',default='build/vulkan-rt-ggx-ab');p.add_argument('--source',default='build/vulkan-rt-ggx-assets');p.add_argument('--workers',type=int,default=4);a=p.parse_args()
 source=ROOT/a.source;out=ROOT/a.output;out.mkdir(parents=True,exist_ok=True)
 manifest=json.loads((source/'manifest.json').read_text())
 for name,digest in manifest['sources'].items():
  if '/tests/' not in name.replace('\\','/') and hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=digest:raise SystemExit('Production source mismatch: '+name)
 if any('RT_GGX_ASSETS' not in d['defines'] for n,d in manifest['shaders'].items() if n.startswith('material_')):raise SystemExit('Experiment source must be --ggx-storage assets build')
 compiler=tool('slangc','SLANGC');validator=tool('spirv-val','SPIRV_VAL')
 if subprocess.check_output([compiler,'-version'],stderr=subprocess.STDOUT,text=True).strip()!=manifest['compiler']:raise SystemExit('Compiler mismatch')
 for name,entry in manifest['shaders'].items():
  data=(source/(name+'.spv')).read_bytes()
  if hashlib.sha256(data).hexdigest()!=entry['sha256']:raise SystemExit('Binary mismatch: '+name)
  shutil.copy2(source/(name+'.spv'),out/(name+'.spv'))
 reference={}
 def compile_entry(item):
  name,stage=item;base,defines=VARIANTS.get(name,(name,[]));stem=name+'_ggx_inline';spv=out/(stem+'.spv');reflection=out/(stem+'.json')
  subprocess.run([compiler,str(ROOT/f'shaders/rt/world/{base}.slang'),'-target','spirv','-profile','spirv_1_5','-entry',base,*['-D'+d+'=1' for d in defines],*(['-capability','spvShaderInvocationReorderNV'] if 'RT_SER' in defines else []),*(['-capability','spvRayQueryKHR'] if 'RT_RAY_QUERY' in defines else []),'-stage',stage,'-matrix-layout-column-major','-O2','-o',str(spv),'-reflection-json',str(reflection)],check=True)
  subprocess.run([validator,'--target-env','vulkan1.2',str(spv)],check=True)
  validate_byte_address_layout(spv.read_bytes());validate_kernel_storage(spv.read_bytes(),allow_ggx_inline=True)
  if name!='material_visibility_miss':validate_layout(json.loads(reflection.read_text()),True,True,'RT_COMPACT48' in defines)
  reference[stem]={'stage':stage,'defines':defines,'sha256':hashlib.sha256(spv.read_bytes()).hexdigest(),'bytes':spv.stat().st_size};print(stem,flush=True)
 with concurrent.futures.ThreadPoolExecutor(max_workers=a.workers) as pool:list(pool.map(compile_entry,[(n,s) for n,s in STAGES.items() if n.startswith('material_')]))
 manifest['shaders'].update(reference);manifest['experiment']='GGX storage only: CLEAN=assets, GGX_INLINE=canonical inline; both banks fully validated'
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');print('Validated inline bank',len(reference),flush=True)
if __name__=='__main__':main()
