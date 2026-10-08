#pragma once
// Fresh RIS only: no spatial/temporal reservoir reuse. All PMFs are frozen for this frame.
bool directRis(){
#if defined(RT_DIRECT_RIS)
    return true;
#elif defined(RT_DIRECT_LEGACY)
    return false;
#else
    return (uint(camera.origin.w)&128u)!=0;
#endif
}
uint runtimeBase(){return assets.Load(208);}
uint runtimeLoad(uint offset){return assets.Load(runtimeBase()+offset);}
uint runtimeLights(){return runtimeLoad(4);}
uint runtimeNode(uint section){return runtimeBase()+runtimeLoad(44)+section*32;}
uint runtimeRecord(uint light){return runtimeBase()+runtimeLoad(36)+light*64;}
uint runtimeCell(float3 point){
    int3 cell=int3(floor(point/16))-asint(assets.Load3(runtimeBase()+16));uint3 size=uint3(runtimeLoad(12),runtimeLoad(28),runtimeLoad(32));
    if(any(cell<0)||any(uint3(cell)>=size))return 0;
    return uint((cell.y*int(size.z)+cell.z)*int(size.x)+cell.x)+1;
}
uint runtimeAliasPmf(uint base,uint count,float u,out float pmf){
    float value=min(u*float(count),float(count)-.00001);uint slot=uint(value);uint address=base+slot*16;
    uint chosen=value-float(slot)<asfloat(assets.Load(address))?slot:assets.Load(address+4);pmf=asfloat(assets.Load(base+chosen*16+12));return assets.Load(base+chosen*16+8);
}
uint runtimeAlias(uint base,uint count,float u){float pmf;return runtimeAliasPmf(base,count,u,pmf);}
float runtimeSectionPdf(uint section,uint cell){
    float global=asfloat(assets.Load(runtimeBase()+runtimeLoad(48)+section*16+12));if(cell==0)return global;
    uint slots=runtimeLoad(52),base=runtimeBase()+runtimeLoad(56)+(cell-1)*slots*16;float local=0;
    for(uint i=0;i<slots;i++)if(assets.Load(base+i*16+8)==section){local=asfloat(assets.Load(base+i*16+12));break;}
    return .2*global+.8*local;
}
float runtimeEnvironmentProbability(){return runtimeLights()>0?.2:1;}
uint directBudget(uint bounce){return assets.Load(bounce==0?216:bounce<3?220:224);}
float directSurvival(uint bounce){return bounce<3?1:bounce==3?.5:.25;}
LightSample runtimeSun(inout uint seed){
    LightSample light=(LightSample)0;float4 sun=directionalLight();if(luminance(directionalIrradiance())<=0||sun.w<=0)return light;
    light.kind=0;light.wi=sampleCone(sun.xyz,sun.w,seed);light.pdf=1/sun.w;light.radiance=directionalIrradiance()/sun.w;light.distance=4096;return light;
}
LightSample runtimeCandidate(float3 point,uint cell,inout uint seed,out uint bucket,float familyChoice){
    LightSample light=(LightSample)0;bucket=0xffffffffu;float env=runtimeEnvironmentProbability();
    if(familyChoice<env){light.wi=sampleEnvironment(seed);light.pdf=env*environmentDensity(light.wi);light.radiance=environmentRadiance(light.wi);light.kind=1;light.distance=4096;return light;}
    uint g=runtimeLoad(8),section;float sectionPdf;
    if(cell>0&&rng(seed)<.8){uint base=runtimeBase()+runtimeLoad(56)+(cell-1)*runtimeLoad(52)*16;float local;section=runtimeAliasPmf(base,runtimeLoad(52),rng(seed),local);sectionPdf=.2*asfloat(assets.Load(runtimeBase()+runtimeLoad(48)+section*16+12))+.8*local;}
    else {section=runtimeAlias(runtimeBase()+runtimeLoad(48),g,rng(seed));sectionPdf=runtimeSectionPdf(section,cell);}
    uint node=runtimeNode(section),index=runtimeAlias(runtimeBase()+runtimeLoad(40)+assets.Load(node+16)*16,assets.Load(node+20),rng(seed));
    uint address=runtimeRecord(index);bucket=assets.Load(node+24);float pmf=(1-env)*sectionPdf*asfloat(assets.Load(address+12));
    if(assets.Load(address+56)==1){float3 delta=asfloat(readWords3(assets,address))-point;light.distance=length(delta);if(light.distance<.025)return light;
        light.wi=delta/light.distance;light.pdf=pmf;light.radiance=asfloat(readWords3(assets,address+16))/(light.distance*light.distance);light.kind=3;return light;}
    float root=sqrt(rng(seed));float2 bary=float2(root*(1-rng(seed)),0);bary.y=root-bary.x;
    float3 a=asfloat(readWords3(assets,address)),b=asfloat(readWords3(assets,address+16)),c=asfloat(readWords3(assets,address+32));
    float3 target=a*(1-root)+b*bary.x+c*bary.y;uint2 ids=assets.Load2(address+48);if(!visibleTexel(ids.x,bary))return light;
    float3 delta=target-point;light.distance=length(delta);light.wi=safeNormalize(delta);float cosine=abs(dot(safeNormalize(cross(b-a,c-a)),light.wi));
    if(cosine<=1e-7||light.distance<=.001)return light;
    light.pdf=pmf/asfloat(assets.Load(address+28))*dot(delta,delta)/cosine;light.radiance=terrainEmission(ids.x,bary);light.kind=4;return light;
}
float runtimeHitPdf(uint triangle,float3 point,float3 target,uint token,uint sourceBounce){
    uint lo=0,hi=runtimeLights();while(lo<hi){uint mid=(lo+hi)/2;if(assets.Load(runtimeRecord(mid)+48)<triangle)lo=mid+1;else hi=mid;}
    if(lo>=runtimeLights())return 0;uint address=runtimeRecord(lo);if(assets.Load(address+48)!=triangle||assets.Load(address+56)!=0)return 0;
    float3 a=asfloat(readWords3(assets,address)),b=asfloat(readWords3(assets,address+16)),c=asfloat(readWords3(assets,address+32));
    float3 delta=target-point;float cosine=abs(dot(safeNormalize(cross(b-a,c-a)),safeNormalize(delta)));if(cosine<=1e-7)return 0;
    float pmf=(1-runtimeEnvironmentProbability())*runtimeSectionPdf(assets.Load(address+60),token&65535u)*asfloat(assets.Load(address+12));
    return directSurvival(sourceBounce)*directBudget(sourceBounce)*pmf/asfloat(assets.Load(address+28))*dot(delta,delta)/cosine;
}
float3 runtimeMiss(float3 direction,float bsdfPdf,bool delta,uint sourceBounce){
    float envPdf=directSurvival(sourceBounce)*directBudget(sourceBounce)*runtimeEnvironmentProbability()*environmentDensity(direction);
    float3 value=environmentRadiance(direction)*(delta?1:powerHeuristic(bsdfPdf,envPdf));float4 sun=directionalLight();
    if(sun.w>0&&dot(direction,sun.xyz)>1-sun.w/(2*Pi))value+=directionalIrradiance()/sun.w*(delta?1:powerHeuristic(bsdfPdf,1/sun.w));return value;
}
// A true nested binary scramble of the base-2 radical inverse. Shared random prefix flips,
// preserve uniform discrete marginals and stratify power-of-two candidate sets.
uint directHash(uint value){value^=value>>16;value*=0x7feb352du;value^=value>>15;value*=0x846ca68bu;return value^(value>>16);}
float owenRadical(uint index,uint scramble){uint bits=0,prefix=0;for(uint bit=0;bit<24;bit++){uint digit=(index>>bit)&1u;uint flipped=digit^(directHash(scramble^prefix^(bit*0x9e3779b9u))&1u);bits|=flipped<<(23-bit);prefix=(prefix<<1)|digit;}return float(bits)/16777216.;}
// Uniform digital shift preserves stratification; opt-in comparison against nested Owen.
float shiftedRadical(uint index,uint scramble){return float((reversebits(index)>>8)^(directHash(scramble)&0xffffffu))/16777216.;}
float directSequence(uint index,uint scramble){return (uint(camera.origin.w)&1048576u)!=0?shiftedRadical(index,scramble):owenRadical(index,scramble);}
uint directCounterAddress(uint bounce){return bounce<4?536u+bounce:538u+bounce;}
void directCounter(uint bounce,uint component){
#ifndef RT_CPU_FIXTURE
    if((uint(camera.origin.w)&2u)!=0){uint ignored;uint index=directCounterAddress(bounce);if(component==0)InterlockedAdd(pageFeedback[index].x,1,ignored);else if(component==1)InterlockedAdd(pageFeedback[index].y,1,ignored);else if(component==2)InterlockedAdd(pageFeedback[index].z,1,ignored);else InterlockedAdd(pageFeedback[index].w,1,ignored);}
#endif
}
void directVolumeCounter(uint component){
#ifndef RT_CPU_FIXTURE
    if((uint(camera.origin.w)&2u)!=0){uint ignored;if(component==0)InterlockedAdd(pageFeedback[527].x,1,ignored);else InterlockedAdd(pageFeedback[527].y,1,ignored);}
#endif
}
float3 volumeVisibility(float3 point,LightSample light,MediumStack media){directVolumeCounter(1);return visibility(point,light.wi,media,max(.001,light.distance-.002),light.kind==3);}
struct DirectReservoir {LightSample light;float3 value;float target,sum;uint bucket;};
float3 directIntegrand(LightSample light,Material m,Frame frame,float3 wo,float3 geometryNormal,float eta,uint bounce,bool volume,float phaseG,float3 incoming,bool terminal=false){
    if(light.pdf<=0)return 0;if(volume)return light.radiance*hg(dot(incoming,light.wi),phaseG);
    BsdfEval e=evalBsdf(m,frame,wo,light.wi,eta);if(e.pdf<=0||!validShadingHemisphere(geometryNormal,frame.n,wo,light.wi))return 0;
    float mis=light.kind==3||bounce>=5||terminal?1:powerHeuristic(directSurvival(bounce)*directBudget(bounce)*light.pdf,e.pdf);
    return light.radiance*e.f*(abs(dot(frame.n,light.wi))*mis);
}
DirectReservoir selectDirect(float3 point,Material m,Frame frame,float3 wo,float3 geometryNormal,float eta,uint bounce,inout uint seed,bool volume=false,float phaseG=0,float3 incoming=float3(0),bool terminal=false){
    DirectReservoir selected=(DirectReservoir)0;selected.bucket=0xffffffffu;float survival=directSurvival(bounce);if(rng(seed)>=survival)return selected;
    uint proposals=directBudget(bounce),cell=runtimeCell(point),scramble=directHash(seed);uint baseIndex=camera.frame*max(1u,proposals),reservoirSeed=directHash(seed^0xac564b05u);
    for(uint i=0;i<proposals;i++){
        directCounter(bounce,2);uint bucket;LightSample light=runtimeCandidate(point,cell,seed,bucket,directSequence(baseIndex+i,scramble));
        float3 value=directIntegrand(light,m,frame,wo,geometryNormal,eta,bounce,volume,phaseG,incoming,terminal);float target=luminance(value);float weight=light.pdf>0?target/light.pdf:0;
        if(!isfinite(weight)||weight<=0)continue;float next=selected.sum+weight;if(!isfinite(next))continue;selected.sum=next;
        if(rng(reservoirSeed)<weight/selected.sum){selected.light=light;selected.value=value;selected.target=target;selected.bucket=bucket;}
    }
    if(selected.target>0){selected.value*=selected.sum/(float(proposals)*selected.target*survival);directCounter(bounce,3);}return selected;
}
void directAdapt(DirectReservoir sample,float3 transmission){
#ifndef RT_CPU_FIXTURE
    if(sample.target>0&&sample.bucket<8&&(uint(camera.origin.w)&64u)!=0){uint ignored;InterlockedAdd(pageFeedback[528u+sample.bucket].x,1,ignored);InterlockedAdd(pageFeedback[528u+sample.bucket].y,uint(saturate(luminance(sample.value*transmission)/max(1e-20,luminance(sample.value)))*float(assets.Load(232))),ignored);}
#endif
}
