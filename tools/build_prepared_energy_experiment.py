"""Build matched baseline + FULL/TRACE prepared-energy shader experiment."""
import hashlib,json,pathlib,shutil,subprocess
from build_vulkan_rt import ROOT,tool,validate_layout,validate_kernel_storage,validate_byte_address_layout

def main():
 source=ROOT/'build/vulkan-rt';out=ROOT/'build/vulkan-rt-prepared';out.mkdir(parents=True,exist_ok=True)
 m=json.loads((source/'manifest.json').read_text());assert m['ggxStorage']=='inline'
 for name,digest in m['sources'].items():
  assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
 for name,entry in m['shaders'].items():
  p=source/(name+'.spv');assert hashlib.sha256(p.read_bytes()).hexdigest()==entry['sha256'];shutil.copy2(p,out/p.name)
 compiler=tool('slangc','SLANGC');validator=tool('spirv-val','SPIRV_VAL')
 assert subprocess.check_output([compiler,'-version'],stderr=subprocess.STDOUT,text=True).strip()==m['compiler']
 for base in ['material_primary','material_indirect']:
  name=base+'_full_ggx_prepared';spv=out/(name+'.spv');reflection=out/(name+'.json')
  defines=['RT_CLEAN_FULL','RT_DIRECT_RIS','RT_GGX_PREPARED']
  subprocess.run([compiler,str(ROOT/f'shaders/rt/world/{base}.slang'),'-target','spirv','-profile','spirv_1_5','-entry',base,*['-D'+d+'=1' for d in defines],'-stage','raygeneration','-matrix-layout-column-major','-O2','-o',str(spv),'-reflection-json',str(reflection)],check=True)
  subprocess.run([validator,'--target-env','vulkan1.2',str(spv)],check=True);validate_byte_address_layout(spv.read_bytes());validate_kernel_storage(spv.read_bytes(),allow_ggx_inline=True);validate_layout(json.loads(reflection.read_text()),True,True)
  m['shaders'][name]={'stage':'raygeneration','defines':defines,'sha256':hashlib.sha256(spv.read_bytes()).hexdigest(),'bytes':spv.stat().st_size}
 m['experiment']='Same-point GGX average/Fresnel factor reuse; inline storage held identical; FULL/TRACE/Monolithic only'
 (out/'manifest.json').write_text(json.dumps(m,indent=2)+'\n',encoding='utf-8');print('Validated baseline110 + prepared2')
if __name__=='__main__':main()
