"""Experiment: route coherent Primary SER aliases to validated ordinary Primary binaries."""
import argparse,hashlib,json,shutil,subprocess
from build_vulkan_rt import ROOT,tool,validate_byte_address_layout,validate_kernel_storage,validate_layout

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--bank',required=True);ap.add_argument('--output',required=True);args=ap.parse_args();bank=ROOT/args.bank;out=ROOT/args.output;out.mkdir(parents=True,exist_ok=True)
 m=json.loads((bank/'manifest.json').read_text());assert not m['profileWorkload'];slang,val=tool('slangc','SLANGC'),tool('spirv-val','SPIRV_VAL');changed=[]
 for name,record in list(m['shaders'].items()):
  target=name
  if name.startswith('material_primary') and 'RT_SER' in record['defines']:
   assert name.endswith('_ser'),name;target=name[:-4];ref=m['shaders'][target];assert ref['defines']==[d for d in record['defines'] if d!='RT_SER']
   base='material_primary_shade' if name.startswith('material_primary_shade') else 'material_primary'
   source=ROOT/f'shaders/rt/world/{base}.slang'
   def pp(defines):return subprocess.check_output([slang,str(source),*['-D'+d+'=1' for d in defines],'-E'],text=True)
   withSer=pp(record['defines']);ordinary=pp(ref['defines']);call='ReorderThread ( hit . distance >= 0 ? surface . material . type : 15 , 4 ) ;'
   assert withSer.count(call)==1,name;assert withSer.replace(call,'').split()==ordinary.split(),name+' unexpected semantic difference'
   m['shaders'][name]=dict(ref);m.get('reuseProof',{}).pop(name,None);changed.append({'alias':name,'ordinarySource':target,'sha256':ref['sha256']})
  binary=(bank/(target+'.spv')).read_bytes();assert hashlib.sha256(binary).hexdigest()==m['shaders'][name]['sha256'];shutil.copy2(bank/(target+'.spv'),out/(name+'.spv'));shutil.copy2(bank/(target+'.json'),out/(name+'.json'))
  subprocess.run([val,'--target-env','vulkan1.2',str(out/(name+'.spv'))],check=True);validate_byte_address_layout(binary);validate_kernel_storage(binary,allow_ggx_inline=True)
  if name!='material_visibility_miss':validate_layout(json.loads((out/(name+'.json')).read_text()),name.startswith(('transport_','material_')),name.startswith('material_'),'RT_COMPACT48' in m['shaders'][name]['defines'])
 m['primarySerBypass']={'sourceBank':args.bank,'aliases':changed};(out/'manifest.json').write_text(json.dumps(m,indent=2));print(f"Validated {len(m['shaders'])} shaders; {len(changed)} Primary SER aliases use ordinary binaries; Indirect unchanged",flush=True)
if __name__=='__main__':main()
