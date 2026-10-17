"""Rebuild changed SER stages; reuse others only after exact preprocessed-source equality."""
import argparse,concurrent.futures,hashlib,json,shutil,subprocess
from build_vulkan_rt import ROOT,STAGES,VARIANTS,tool,validate_byte_address_layout,validate_kernel_storage,validate_layout

def run(args):return subprocess.check_output([str(x) for x in args],text=True,stderr=subprocess.STDOUT)
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--baseline-dir',required=True);ap.add_argument('--bank',required=True);ap.add_argument('--output',required=True);args=ap.parse_args()
 source=ROOT/args.baseline_dir;bank=ROOT/args.bank;out=ROOT/args.output;out.mkdir(parents=True,exist_ok=True)
 slang,val=tool('slangc','SLANGC'),tool('spirv-val','SPIRV_VAL');old=json.loads((bank/'manifest.json').read_text());assert old['ggxStorage']=='inline' and not old['profileWorkload']
 version=run([slang,'-version']).strip();assert old['compiler']==version
 manifest={'compiler':version,'target':'vulkan1.2','ggxStorage':'inline','profileWorkload':False,'shaders':{},'sources':{},'reuseProof':{}}
 for p in sorted((ROOT/'shaders/rt').rglob('*.slang')):manifest['sources'][str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
 def compile_one(item):
  entry,stage=item;base,defines=VARIANTS.get(entry,(entry,[]))
  flags=['-D'+d+'=1' for d in defines]
  src=ROOT/f'shaders/rt/world/{base}.slang';spv=out/(entry+'.spv');reflection=out/(entry+'.json')
  current=run([slang,src,*flags,'-E']);baseline=run([slang,source/f'world/{base}.slang',*flags,'-E'])
  if current==baseline:
   reference=entry
   if base in ('material_primary','material_primary_shade') and entry.endswith('_ser') and 'RT_SER' not in defines:reference=entry[:-4]
   record=old['shaders'][reference];assert record['defines']==defines if 'defines' in record else not defines
   binary=(bank/(reference+'.spv')).read_bytes();assert hashlib.sha256(binary).hexdigest()==record['sha256']
   shutil.copy2(bank/(reference+'.spv'),spv);shutil.copy2(bank/(reference+'.json'),reflection)
   manifest['reuseProof'][entry]={'preprocessedSha256':hashlib.sha256(current.encode()).hexdigest(),'bank':args.bank,'oldShaderSha256':record['sha256'],'baselineEntry':reference}
  else:
   assert 'RT_SER_PRE_DECODE' in defines and base=='material_indirect',entry+' unexpected changed stage'
   run([slang,src,'-target','spirv','-profile','spirv_1_5','-entry',base,*flags,'-capability','spvShaderInvocationReorderNV',*(['-capability','spvRayQueryKHR'] if 'RT_RAY_QUERY' in defines else []),'-stage',stage,'-matrix-layout-column-major','-O2','-o',spv,'-reflection-json',reflection])
  run([val,'--target-env','vulkan1.2',spv]);binary=spv.read_bytes();validate_byte_address_layout(binary);validate_kernel_storage(binary,allow_ggx_inline=True)
  if entry!='material_visibility_miss':validate_layout(json.loads(reflection.read_text()),entry.startswith(('transport_','material_')),entry.startswith('material_'),'RT_COMPACT48' in defines)
  manifest['shaders'][entry]={'stage':stage,'defines':defines,'sha256':hashlib.sha256(binary).hexdigest(),'bytes':len(binary)}
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(compile_one,STAGES.items()))
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2))
 print(f"Validated {len(STAGES)} stages; rebuilt {len(STAGES)-len(manifest['reuseProof'])} Indirect SER stages, reused {len(manifest['reuseProof'])} with exact preprocessor equality",flush=True)
if __name__=='__main__':main()
