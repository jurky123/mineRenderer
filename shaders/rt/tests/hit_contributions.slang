#define RT_CPU_FIXTURE 1
#define RT_STATE_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[[vk::binding(19,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void hit_contributions(uint3 tid:SV_DispatchThreadID){
    uint i=tid.x,index=i*2,kind=i%8;
    float initial=kind==0?16777216.0:kind==4?asfloat(0x7f000000u):3.5;
    output[index]=output[index+1]=float4(initial,initial,initial,1);
    PathAov aov;aov.diffuse=float4(.1,.2,.3,2);aov.reflection=float4(.4,.5,.6,3);aov.refraction=float4(.7,.8,.9,4);
    pathAovs[index]=pathAovs[index+1]=aov;
    PathState reference=(PathState)0;reference.reserved0=index;reference.origin.w=1;reference.throughput.w=i&1;reference.channel=(i/8)%3;reference.radiance.w=asfloat(0xdeadbeefu);
    PathState local=reference;local.reserved0=index+1;beginHitContributions(local);
    for(uint step=0;step<4;step++){
        float3 c=kind==0?float3(1):float3(.2,.3,.4);
        if(step==1){
            if(kind==1)c.x=asfloat(0x7fc00000u);
            if(kind==2)c.y=asfloat(0x7f800000u);
            if(kind==3)c.z=-1;
            if(kind==4)c=float3(asfloat(0x7f000000u));
            if(kind==5)c=0;
            if(kind==6)c=float3(1e-30);
        }
        addRadiance(reference,c,int(step%3));addHitRadiance(local,c,int(step%3));
    }
    commitHitContributions(local);PathAov a=pathAovs[index],b=pathAovs[index+1];
    result[i]=float4(any(asuint(output[index])!=asuint(output[index+1]))?1:0,
        any(asuint(a.diffuse)!=asuint(b.diffuse))||any(asuint(a.reflection)!=asuint(b.reflection))||any(asuint(a.refraction)!=asuint(b.refraction))?1:0,
        reference.padding!=local.padding||reference.origin.w!=local.origin.w?1:0,asuint(local.radiance.w)!=0xdeadbeefu?1:0);
}
