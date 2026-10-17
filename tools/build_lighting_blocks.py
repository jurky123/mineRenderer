"""Decompose real production candidates. Isolated compute fixtures only."""
import pathlib,json,re,subprocess,shutil,argparse,hashlib
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('--rt-source',type=pathlib.Path,required=True,help='Production shaders/rt directory')
ap.add_argument('--schema',type=pathlib.Path,required=True,help='Schema from the captured alpha.79 inputs')
ap.add_argument('--caustica',type=pathlib.Path,required=True,help='Local Caustica checkout, used only to extract its diagnostic target')
ap.add_argument('--slang',type=pathlib.Path,required=True)
ap.add_argument('--spirv-val',type=pathlib.Path,required=True)
ap.add_argument('--output',type=pathlib.Path,required=True)
args=ap.parse_args()
d=args.output.resolve()
if d.exists():raise SystemExit('Choose a new output directory to preserve evidence')
d.mkdir(parents=True)
source=d/'source';shutil.copytree(args.rt_source,source)
schema=json.loads(args.schema.read_text());types=schema['types']
types['RuntimeCandidate']=[('LightSample','light'),('uint','record'),('float2','bary')]
types['BsdfEval']=[('float3','f'),('float','pdf')]
def leaves(t,n):
 if t in types:return [x for a,b in types[t] for x in leaves(a,n+'.'+b)]
 if re.fullmatch('float[234]',t):return [('float',n+'.'+c) for c in 'xyzw'[:int(t[-1])]]
 return [(t,n)]
def load(t,n,start,fn='replayGet(slot,'):
 return f'{t} {n}=({t})0;'+''.join(f'{v}='+('asfloat('+f'{fn}{start+i}u)' +')' if a=='float' else '('+f'{fn}{start+i}u)'+'!=0)' if a=='bool' else f'{fn}{start+i}u)')+';' for i,(a,v) in enumerate(leaves(t,n)))
def store(t,n,start):return ''.join(f'put(id,{start+i}u,'+('asuint('+v+')' if a=='float' else 'uint('+v+')')+');' for i,(a,v) in enumerate(leaves(t,n)))
read='';pos=4
for typ,name in schema['functions'][0]['inputs']:
 read+=load(typ,name,pos);pos+=len(leaves(typ,name))
prefix='#define RT_CPU_FIXTURE 1\n#define RT_CLEAN_FULL 1\n#define RT_DIRECT_RIS 1\n#include "'+(source/'common/material_transport.slang').as_posix()+'"\nSurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){SurfacePayload p=(SurfacePayload)0;p.distance=-1;return p;}\n'
candidateLoop='float checksum=0;uint count=0;float survival=directSurvival(bounce);if(rng(seed)<survival){uint proposals=directBudget(bounce),cell=runtimeCell(point),scramble=directHash(seed),baseIndex=camera.frame*max(1u,proposals);for(uint i=0;i<proposals;i++){uint bucket;RuntimeCandidate c=runtimeCandidateGeometry(point,cell,seed,bucket,directSequence(baseIndex+i,scramble));if(c.light.pdf<=0)continue;if(!volume&&!directReceiverAccept(prepared,geometryNormal,c.light.wi))continue;LightSample l=runtimeCandidateEmission(c);checksum+=l.pdf+dot(l.radiance,float3(.1,.2,.3))+dot(l.wi,float3(.4,.5,.6));count++;}}'
helper='''
uint replayBase(){return 1648u+4u*camera.width*camera.height*max(1u,camera.padding);}
uint replayGet(uint slot,uint word){return pageFeedback[replayBase()+slot*128u+word/4u][word%4u];}
uint tableBase(){return replayBase()+2048u*128u+2048u;}
uint get(uint id,uint word){return pageFeedback[tableBase()+id*16u+word/4u][word%4u];}
void put(uint id,uint word,uint v){pageFeedback[tableBase()+id*16u+word/4u][word%4u]=v;}
'''
def extract(text,start):
 a=text.index(start);b=text.index('{',a);depth=1;e=b+1
 while depth:depth+=(text[e]=='{')-(text[e]=='}');e+=1
 return text[a:e].replace('public ','')
caustica=args.caustica/'shaders/pipelines/world'
math=(caustica/'math.slang').read_text();lighting=(caustica/'lighting.slang').read_text()
helper+='\nstatic const float PI=Pi,INV_PI=1/Pi,SSS_G=.6,SSS_STRENGTH=1;void fixtureProfileCount(uint n){}\n'
helper+='\n'.join(extract(math,s) for s in ['public float ggxD(', 'public float ggxG1(', 'public float3 fresnelSchlick('])
helper+=extract(lighting,'public float3 evalSampleContrib(').replace('profileCount(', 'fixtureProfileCount(')
helper+='''
float4 causticaTarget(float3 point,PreparedBsdf p,RuntimeCandidate c,LightSample light){
 float3 a=asfloat(readWords3(assets,c.record)),b=asfloat(readWords3(assets,c.record+16)),v=asfloat(readWords3(assets,c.record+32));
 float phat;float3 value=evalSampleContrib(point+light.wi*light.distance,safeNormalize(cross(b-a,v-a)),light.radiance,asfloat(assets.Load(c.record+28)),point,p.frame.n,p.woWorld,-p.woWorld,p.material.baseColor,float3(p.material.f0),p.ax,false,0,phat);
 return float4(value,phat);
}
'''
runtime=(source/'common/light_runtime.slang').read_text()
captured=extract(runtime,'RuntimeCandidate runtimeCandidateGeometry(')
captured=captured.replace('runtimeCandidateGeometry(', 'captureGeometry(',1).replace('float familyChoice){','float familyChoice,out uint postSeed,out uint chosenRecord,out float chosenPmf){postSeed=0;chosenRecord=0;chosenPmf=0;',1)
anchor='float pmf=(1-env)*sectionPdf*asfloat(assets.Load(address+12));'
assert captured.count(anchor)==1;captured=captured.replace(anchor,anchor+'postSeed=seed;chosenRecord=address;chosenPmf=pmf;')
helper+=captured
gen=read+'''if(rng(seed)>=directSurvival(bounce))return;
uint count=directBudget(bounce),cell=runtimeCell(point),scramble=directHash(seed),baseIndex=camera.frame*max(1u,count);
for(uint i=0;i<count;i++){uint id=slot*8u+i,bucket,before=seed;float family=directSequence(baseIndex+i,scramble);
uint postSeed,chosenRecord;float chosenPmf;RuntimeCandidate c=captureGeometry(point,cell,seed,bucket,family,postSeed,chosenRecord,chosenPmf);
bool accept=c.light.pdf>0&&(volume||directReceiverAccept(prepared,geometryNormal,c.light.wi));
LightSample light=accept?runtimeCandidateEmission(c):(LightSample)0;
BsdfEval e=accept?evalBsdf(prepared,light.wi):(BsdfEval)0;
float3 value=accept?directIntegrand(light,prepared,geometryNormal,bounce,volume,phaseG,incoming,terminal,diffuseCached):float3(0);
put(id,0,1);put(id,1,slot);put(id,2,before);put(id,3,asuint(family));put(id,4,bucket);put(id,5,seed);put(id,6,uint(accept));put(id,39,scramble);put(id,40,baseIndex+i);
put(id,41,postSeed);put(id,42,chosenRecord);put(id,43,asuint(chosenPmf));
float4 ct=accept&&light.kind==4?causticaTarget(point,prepared,c,light):float4(0);
'''+store('RuntimeCandidate','c',8)+store('LightSample','light',20)+store('BsdfEval','e',32)+store('float3','value',36)+store('float4','ct',44)+'}'
# Store all components, not just a checksum: CPU validation catches discrete and float differences.
bodyread='uint slot=get(id,1);'+read+load('RuntimeCandidate','c',8,'get(id,')+load('LightSample','light',20,'get(id,')
stages=[
 ('geometry', 'uint bucket;seed=get(id,2);RuntimeCandidate value=runtimeCandidateGeometry(point,runtimeCell(point),seed,bucket,asfloat(get(id,3)));'+store('RuntimeCandidate','value',48)+'put(id,60,seed);put(id,61,bucket);'),
 ('emission','LightSample value=runtimeCandidateEmission(c);'+store('LightSample','value',48)),
 ('full_bsdf','BsdfEval value=evalBsdf(prepared,light.wi);'+store('BsdfEval','value',48)),
 ('integrand','float3 value=directIntegrand(light,prepared,geometryNormal,bounce,volume,phaseG,incoming,terminal,diffuseCached);'+store('float3','value',48)),
 ('io_control','float4 value=float4(prepared.material.baseColor+prepared.wo,light.pdf);'+store('float4','value',48)),
 ('geometry_environment','uint bucket;seed=get(id,2);RuntimeCandidate value=runtimeCandidateGeometry(point,runtimeCell(point),seed,bucket,asfloat(get(id,3)));'+store('RuntimeCandidate','value',48)+'put(id,60,seed);put(id,61,bucket);'),
 ('geometry_emitter','uint bucket;seed=get(id,2);RuntimeCandidate value=runtimeCandidateGeometry(point,runtimeCell(point),seed,bucket,asfloat(get(id,3)));'+store('RuntimeCandidate','value',48)+'put(id,60,seed);put(id,61,bucket);'),
 ('emission_environment','LightSample value=runtimeCandidateEmission(c);'+store('LightSample','value',48)),
 ('emission_area','LightSample value=runtimeCandidateEmission(c);'+store('LightSample','value',48)),
 ('bsdf_multiscatter','float3 value=preparedMultiscatter(prepared,prepared.frame.local(light.wi));'+store('float3','value',48)),
 ('bsdf_fresnel','float3 wi=prepared.frame.local(light.wi);float3 h=safeNormalize(prepared.wo+wi);float4 value=float4(metal(prepared.material)?conductorFresnel(prepared.material,max(0,dot(prepared.wo,h))):float3(1)*fresnelDielectric(max(0,dot(prepared.wo,h)),prepared.specIOR),fresnelDielectric(wi.z,prepared.baseIOR));'+store('float4','value',48)),
 ('bsdf_distribution_smith','float3 wi=prepared.frame.local(light.wi);float3 h=safeNormalize(prepared.wo+wi);float3 value=float3(D(h,prepared.ax,prepared.ay),preparedG(prepared,wi),preparedNormalPdf(prepared,h));'+store('float3','value',48)),
 ('geometry_with_sequence','uint bucket;seed=get(id,2);RuntimeCandidate value=runtimeCandidateGeometry(point,runtimeCell(point),seed,bucket,directSequence(get(id,40),get(id,39)));'+store('RuntimeCandidate','value',48)+'put(id,60,seed);put(id,61,bucket);'),
 ('sequence','float value=directSequence(get(id,40),get(id,39));'+store('float','value',48)),
 ('complete_candidate','uint bucket;seed=get(id,2);RuntimeCandidate v=runtimeCandidateGeometry(point,runtimeCell(point),seed,bucket,directSequence(get(id,40),get(id,39)));LightSample value=(LightSample)0;if(v.light.pdf>0&&(volume||directReceiverAccept(prepared,geometryNormal,v.light.wi)))value=runtimeCandidateEmission(v);'+store('LightSample','value',48)+'put(id,60,seed);put(id,61,bucket);'),
 ('full_ris','DirectReservoir value=selectDirectPrepared(point,prepared,geometryNormal,bounce,seed,volume,phaseG,incoming,terminal,diffuseCached);'+store('DirectReservoir','value',48)+'put(id,63,seed);'),
 ('complete_candidate_loop', candidateLoop+'put(id,48,count);put(id,49,asuint(checksum));put(id,50,seed);'),
 ('caustica_target_area','float4 value=causticaTarget(point,prepared,c,light);'+store('float4','value',48)),
]
def write(folder,k,body,generation=False):
 folder.mkdir(exist_ok=True)
 guard='uint slot=tid.x;if(slot>=min(2048u,pageFeedback[1634].x)||replayGet(slot,0)!=0u)return;' if generation else 'uint id=tid.x;if(id>=pageFeedback[1635].x)return;'
 text=prefix+helper+'[shader("compute")][numthreads(64,1,1)]void fixture(uint3 tid:SV_DispatchThreadID){'+guard+body+'}'
 p=folder/f'replay{k}.slang';p.write_text(text)
 subprocess.run([str(args.slang.resolve()),str(p),'-target','spirv','-profile','spirv_1_5','-entry','fixture','-stage','compute','-O2','-o',str(folder/f'replay{k}.spv')],check=True)
 subprocess.run([str(args.spirv_val.resolve()),'--target-env','vulkan1.2',str(folder/f'replay{k}.spv')],check=True)
 print(k,stages[k][0] if not generation else 'capture candidates',flush=True)
write(d/'generate',0,gen,True)
for k,(name,body) in enumerate(stages):
 filt=''
 if k not in (0,5,6,12,13,14,15,16):filt='if(get(id,6)==0)return;'
 if k in (5,7):filt+='if(get(id,16)!=1u)return;'
 if k==6:filt+='if(get(id,16)==1u)return;'
 if k in (8,17):filt+='if(get(id,16)!=4u)return;'
 write(d/'bench',k,filt+bodyread+body)
(d/'stages.json').write_text(json.dumps([n for n,b in stages],indent=2))
identity={str(p.relative_to(source)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source.rglob('*.slang')}
(d/'identity.json').write_text(json.dumps({'sourceSha256':identity,'schemaSha256':hashlib.sha256(args.schema.read_bytes()).hexdigest(),'formalTimingEligible':False,'note':'All kernels are isolated diagnostics; values are not additive frame costs.'},indent=2))
