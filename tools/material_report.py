#!/usr/bin/env python3
"""Audit block texture assignments. Active pack assets and model aliases are resolved before selectors."""
import argparse,csv,json,pathlib,zipfile,fnmatch
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--minecraft-jar',required=True);p.add_argument('--common-jar',help='Optional common jar for Vanilla block tags');p.add_argument('--pack',help='Optional resource-pack zip');p.add_argument('--overrides');p.add_argument('--csv');a=p.parse_args()
curated=json.loads((ROOT/'src/main/resources/assets/voxellight/materials/vanilla/blocks.json').read_text());old=json.loads((ROOT/'src/main/resources/assets/voxellight/pbr_materials.json').read_text())
archives=[zipfile.ZipFile(a.minecraft_jar)]
if a.common_jar:archives.append(zipfile.ZipFile(a.common_jar))
if a.pack:archives.append(zipfile.ZipFile(a.pack))
files=set().union(*(z.namelist() for z in archives))
def asset(path):
 for z in reversed(archives):
  if path in z.namelist():return json.loads(z.read(path))
 return {}
def qualified(key):return key if ':' in key else 'minecraft:'+key
def model(key,seen=None):
 key=qualified(key);seen=set() if seen is None else seen
 if key in seen:return {}
 seen.add(key);ns,name=key.split(':',1);o=asset(f'assets/{ns}/models/{name}.json');values=model(o['parent'],seen) if 'parent' in o else {};return {**values,**o.get('textures',{})}
def model_names(value):
 if isinstance(value,list):
  for v in value:yield from model_names(v)
 elif isinstance(value,dict):
  for k,v in value.items():
   if k=='model' and isinstance(v,str):yield v
   else:yield from model_names(v)
def block_textures(key):
 ns,name=qualified(key).split(':',1);names=list(model_names(asset(f'assets/{ns}/blockstates/{name}.json')));result=set()
 for name in names:
  values=model(name)
  for value in values.values():
   seen=set()
   while value.startswith('#') and value not in seen:seen.add(value);value=values.get(value[1:],'#missing')
   if not value.startswith('#'):result.add(qualified(value))
 return result or {f'{ns}:block/{key.split(":")[-1]}'}
def tag_blocks(key,seen=None):
 key=qualified(key);seen=set() if seen is None else seen
 if key in seen:return set()
 seen.add(key);ns,name=key.split(':',1);data=asset(f'data/{ns}/tags/block/{name}.json');result=set()
 for value in data.get('values',[]):
  value=value.get('id','') if isinstance(value,dict) else value
  if value.startswith('#'):result|=tag_blocks(value[1:],seen)
  elif value:result.add(qualified(value))
 return result
rules=[]
if a.pack:
 for n in sorted(archives[-1].namelist()):
  if '/material_overrides/' in n and n.endswith('.json'):
   data=asset(n);rules.extend(data if isinstance(data,list) else [data])
if a.overrides:
 data=json.loads(pathlib.Path(a.overrides).read_text());rules.extend(data if isinstance(data,list) else [data])
selector_sets={}
for rule in rules:
 selector=rule['material']
 if selector.startswith('block='):selector_sets[selector]=block_textures(selector[6:])
 elif selector.startswith('#'):selector_sets[selector]=set().union(*(block_textures(b) for b in tag_blocks(selector[1:])))
names=sorted(n for n in files if n.startswith('assets/minecraft/textures/block/') and n.endswith('.png') and not n.endswith(('_s.png','_n.png')))
rows=[]
for n in names:
 key='minecraft:'+n[len('assets/minecraft/textures/'):-4];data=curated.get(key,old.get(key));source='vanilla preset' if data else 'generic fallback';data=data or {'type':'rough_diffuse','roughness':.85};selected=None;rank=0
 for rule in rules:
  selector=rule['material'];r=3 if selector.startswith('block=') else 2 if selector.startswith('#') else 1 if selector.endswith(':*') else 4
  match=key in selector_sets[selector] if r in (2,3) else fnmatch.fnmatchcase(key,selector)
  if match and r>=rank:selected=rule;rank=r
 if selected:data={**data,**selected};source='override'
 lab=a.pack and (n[:-4]+'_s.png' in archives[-1].namelist() or n[:-4]+'_n.png' in archives[-1].namelist())
 if lab:source='LabPBR'
 rows.append({'texture':key,'material_class':data.get('type','conductor' if data.get('metal',0)>=230 else 'rough_diffuse'),'perceptual_roughness':data.get('roughness',.85),'source':source,'labpbr_per_texel':bool(lab)})
fallback=sum(r['source']=='generic fallback' for r in rows)
print(json.dumps({'textures':len(rows),'coverage_percent':round(100*(len(rows)-fallback)/max(1,len(rows)),2),'fallback_count':fallback,'unknown':[r['texture'] for r in rows if r['source']=='generic fallback'],'unresolved_selectors':[s for s,t in selector_sets.items() if not t]},indent=2))
if a.csv:
 with open(a.csv,'w') as f:w=csv.DictWriter(f,fieldnames=rows[0].keys());w.writeheader();w.writerows(rows)
for z in archives:z.close()
