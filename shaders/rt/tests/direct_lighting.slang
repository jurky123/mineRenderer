#define RT_CPU_FIXTURE
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){SurfacePayload hit=(SurfacePayload)0;hit.distance=-1;return hit;}
[[vk::binding(19,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void direct_lighting(uint3 tid:SV_DispatchThreadID){
    uint seed=directHash(tid.x+camera.frame*1237u),bounce=camera.padding;
    bool surface=camera.height==2;Material m=(Material)0;m.type=DIFFUSE;m.baseColor=1;Frame frame=makeFrame(float3(0,0,1));
    DirectReservoir selected=selectDirect(float3(0),m,frame,float3(0,0,1),float3(0,0,1),1,bounce,seed,!surface,0,float3(0));
    float3 color=selected.value;
    if(selected.target>0){if(selected.light.kind==3&&uint(round(selected.light.distance))%3==0)color=0;
        if(selected.light.kind==4&&selected.light.wi.x<0)color=0;}
    if(surface){
        float3 wi=selected.light.wi;float3 hit=wi*(3/max(1e-20,wi.z));bool rectangle=wi.z>0&&abs(hit.x)<1&&abs(hit.y)<1&&runtimeLights()>16;
        if(selected.light.kind==1&&rectangle)color=0;
        if(bounce<5){BsdfSample bsdf=sampleBsdf(m,frame,float3(0,0,1),seed,1);wi=bsdf.wi;hit=wi*(3/max(1e-20,wi.z));rectangle=wi.z>0&&abs(hit.x)<1&&abs(hit.y)<1&&runtimeLights()>16;
            if(rectangle){if(hit.x>=0){uint tri=hit.x+hit.y<=0?32u:33u;float pdf=runtimeHitPdf(tri,float3(0),hit,runtimeCell(float3(0)),bounce);color+=bsdf.weight*8*powerHeuristic(bsdf.pdf,pdf);}}
            else color+=bsdf.weight*runtimeMiss(wi,bsdf.pdf,false,bounce);
        }
    }
    // Match the sampled hierarchical area PDF to the BSDF-hit competitor, including roulette/K.
    uint bucket;LightSample candidate=runtimeCandidate(float3(0),runtimeCell(float3(0)),seed,bucket,rng(seed));float error=0;
    if(candidate.pdf>0&&candidate.kind==4){float q=runtimeHitPdf(candidate.wi.x+candidate.wi.y<=0?32u:33u,float3(0),candidate.wi*candidate.distance,runtimeCell(float3(0)),bounce);
        // Diagonal x+y=0 identifies the exact sampled emitter triangle.
        error=abs(q-directSurvival(bounce)*directBudget(bounce)*candidate.pdf)/max(1e-20,q);}
    result[tid.x*2]=float4(color,selected.target>0?1:0);result[tid.x*2+1]=float4(error,owenRadical(tid.x,camera.frame),0,0);
}
