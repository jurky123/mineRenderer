#!/usr/bin/env python3
"""Offline fixture generation and raw capture comparisons. Never contacts a running world."""
import argparse, array, json, math, pathlib, re, sys, zipfile

def fixture(directory):
    directory.mkdir(parents=True,exist_ok=True)
    commands=['time set noon','weather clear','difficulty peaceful','fill -8 98 -8 72 110 24 air','fill -8 98 -8 72 98 24 stone']
    scenes=[('terrain',0,'stone'),('foliage',16,'oak_leaves[persistent=true]'),('mirror',32,'gold_block'),('glass_water',48,'glass'),('entity',64,'iron_block')]
    for name,x,block in scenes:
        commands+= [f'fill {x} 99 6 {x+6} 103 6 {block}',f'setblock {x+3} 100 4 sea_lantern']
        if name=='glass_water':commands += [f'fill {x} 99 10 {x+6} 99 15 water',f'fill {x} 99 12 {x+6} 102 12 red_concrete']
        if name in ('mirror','glass_water','entity'):
            commands += [f'summon armor_stand {x+3} 99 {0 if name=="mirror" else 3 if name=="entity" else 9} {{Tags:["vl49_{name}"],NoGravity:1b,Invulnerable:1b}}']
    # Local scratch world only; duplicate entities are cleared by explicit fixture tag.
    commands.insert(0,'kill @e[tag=vl49_mirror]');commands.insert(0,'kill @e[tag=vl49_glass_water]');commands.insert(0,'kill @e[tag=vl49_entity]')
    (directory/'setup.mcfunction').write_text('\n'.join(commands)+'\n')
    for step in (0,1):
        (directory/f'pose-{step}.mcfunction').write_text('\n'.join(f'tp @e[tag=vl49_{name}] {x+2+step*2} 99 {0 if name=="mirror" else 3 if name=="entity" else 9}' for name,x,_ in scenes if name in ('mirror','glass_water','entity'))+'\n')
    (directory/'scenes.json').write_text(json.dumps({'world':'dedicated local creative scratch world','seed':49,'camera':[{'scene':name,'teleport':f'tp @s {x+3} 100 -3 0 0','materials':block} for name,x,block in scenes], 'note':'Vanilla gold is not guaranteed mirror-like; use the same verified LabPBR pack and record actual roughness in both runs. Functions are manual local-world inputs, not automatic server commands.'},indent=2)+'\n')

def compare(left,right):
    result={'reference':str(left),'candidate':str(right),'limitations':'Single noisy frame differences are descriptive, not image equivalence. Match camera, dimensions, jitter, resources, exposure and history; average repeated HDR captures.'}
    with zipfile.ZipFile(left) as a,zipfile.ZipFile(right) as b:
        def metadata(z):
            text=z.read('capture.txt').decode(); shapes={n:(int(w),int(h),int(c)) for n,w,h,c in re.findall(r'([^\s]+) (\d+)x(\d+) channels=(\d+)',text)};return text,shapes
        ta,sa=metadata(a);tb,sb=metadata(b);result['referenceMetadata']=ta;result['candidateMetadata']=tb
        if not sa or sa.keys()!=sb.keys():raise ValueError('capture channel set differs or is empty')
        ca=re.search(r'\bclip=(.*?)(?=\nRaw files:|\Z)',ta,re.S);cb=re.search(r'\bclip=(.*?)(?=\nRaw files:|\Z)',tb,re.S)
        if not ca or not cb or ca.group(1)!=cb.group(1):raise ValueError('incompatible capture contract: clip/view orientation')
        for key in ('camera','workingColor','preExposure','depth','motion'):
            va=re.search(r'\b'+key+r'=([^\s]+)',ta);vb=re.search(r'\b'+key+r'=([^\s]+)',tb)
            if not va or not vb or va.group(1)!=vb.group(1):raise ValueError('incompatible capture contract: '+key)
        ja=re.search(r'\bjitter=([^\s]+)',ta);jb=re.search(r'\bjitter=([^\s]+)',tb)
        result['jitterMatched']=bool(ja and jb and ja.group(1)==jb.group(1))
        rows={}
        for name,shape in sa.items():
            if sb.get(name)!=shape:raise ValueError('capture dimensions/channels differ: '+name)
            x=array.array('f');x.frombytes(a.read(name+'.f32'));y=array.array('f');y.frombytes(b.read(name+'.f32'))
            if sys.byteorder!='little':x.byteswap();y.byteswap()
            if len(x)!=math.prod(shape) or len(y)!=len(x):raise ValueError('invalid float payload '+name)
            errors=[abs(i-j) for i,j in zip(x,y) if math.isfinite(i) and math.isfinite(j)];errors.sort()
            invalid=len(x)-len(errors)
            rows[name]={'shape':shape,'nonfinitePairs':invalid,'mae':sum(errors)/len(errors) if errors else None,'rmse':math.sqrt(sum(e*e for e in errors)/len(errors)) if errors else None,'p95Absolute':errors[(len(errors)-1)*95//100] if errors else None,'maxAbsolute':max(errors) if errors else None}
        result['channels']=rows
    return result
if __name__=='__main__':
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('fixture');p.add_argument('directory',type=pathlib.Path)
    p=sub.add_parser('compare');p.add_argument('reference',type=pathlib.Path);p.add_argument('candidate',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,required=True)
    args=parser.parse_args()
    if args.command=='fixture':fixture(args.directory)
    else:args.output.write_text(json.dumps(compare(args.reference,args.candidate),indent=2)+'\n')
