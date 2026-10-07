#define RT_CPU_FIXTURE 1
#define RT_POLICY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void realtime_policy(uint3 tid:SV_DispatchThreadID){
    for(uint row=544;row<1568;row++)pageFeedback[row]=uint4(0xdeadc0deu);
    uint p=rtProbe(0);float3 point=float3(1,1,1),n=float3(0,1,0);
    pageFeedback[1568]=uint4(1,7,0,1024);
    pageFeedback[p]=uint4(1,1,0,0);pageFeedback[p+1]=asuint(float4(3,4,5,1));pageFeedback[p+2]=asuint(float4(.25,.5,.75,0));pageFeedback[p+4]=asuint(float4(point,0));pageFeedback[p+5]=asuint(float4(n,0));
    // Stratified uniform-hemisphere observations of constant incoming radiance.
    for(uint i=0;i<128;i++){float u=(i+.5)/128,phi=2*Pi*i*.61803398875;float3 wi=float3(sqrt(1-u*u)*cos(phi),u,sqrt(1-u*u)*sin(phi));pageFeedback[p+3]=asuint(float4(wi,1/(2*Pi)));output[0]=float4(3.5,5.5,8,1);rtTrain(0);}
    uint address=rtCacheAddress(point,n);float3 value=rtShIrradiance(asfloat(pageFeedback[address+4]),asfloat(pageFeedback[address+5]),asfloat(pageFeedback[address+6]),asfloat(pageFeedback[address+7]),n);
    output[1]=float4(value,rtCacheValid(address,point,n)?1:0);
    output[2]=float4(rtInterval(8,.01),rtInterval(4,.05),rtInterval(32,.5),rtCacheValid(address,point+float3(0,.1,0),n)?1:0);
    pageFeedback[1568].y=8;output[3]=float4(rtCacheValid(address,point,n)?1:0,0,0,0);pageFeedback[1568].y=7;pageFeedback[address].z=51;output[3].y=rtCacheValid(address,point,n)?1:0;rtTrain(0);output[9]=float4(pageFeedback[address].w,0,0,0);
    Material m=(Material)0;m.type=DIFFUSE;m.microfacetAlpha=.5;output[4]=float4(rtDiffuse(m)?1:0,0,0,0);m.type=CONDUCTOR;output[4].y=rtDiffuse(m)?1:0;m.type=WATER;output[4].z=rtDiffuse(m)?1:0;
    pageFeedback[1568].x=0;output[4].w=rtPolicy()?1:0;
    pageFeedback[1568]=uint4(3,7,0,1024);pageFeedback[1570]=asuint(float4(1,0,0,0));pageFeedback[1571]=asuint(float4(0,1,0,0));pageFeedback[1572]=asuint(float4(0,0,1,0));pageFeedback[1573]=asuint(float4(0,0,0,1));pageFeedback[1574]=uint4(asuint(float3(1,1,0)),1);
    uint previous=rtHistory(0,(camera.frame+1)&1);pageFeedback[previous]=asuint(float4(.2,.3,.4,8));pageFeedback[previous+1]=asuint(float4(.2,.3,.4,.01));pageFeedback[previous+2]=asuint(float4(0,0,0,.3));pageFeedback[previous+3]=0;pageFeedback[previous+4]=asuint(float4(point,1));pageFeedback[previous+5]=asuint(float4(n,.5));pageFeedback[previous+6]=uint4(41,7,7,camera.frame-1);pageFeedback[previous+7]=asuint(float4(.6,.6,.6,DIFFUSE));pageFeedback[p]=0;
    PathState path=(PathState)0;path.origin.w=1;TerrainSurface surface=(TerrainSurface)0;surface.material=(Material)0;surface.material.type=DIFFUSE;surface.material.materialId=7;surface.material.microfacetAlpha=.5;surface.material.baseColor=.6;SurfacePayload hit=(SurfacePayload)0;hit.triangle=41;hit.distance=1;MediumStack media=(MediumStack)0;
    bool reused=rtSparse(path,surface,hit,point,n,media);output[5]=float4(output[0].xyz,reused?1:0);
    path.origin.w=1;surface.flags=128;output[6]=float4(rtSparse(path,surface,hit,point,n,media)?1:0,0,0,0);surface.flags=0;pageFeedback[1568].y=8;output[6].y=rtSparse(path,surface,hit,point,n,media)?1:0;pageFeedback[1568].y=7;hit.triangle=42;output[6].z=rtSparse(path,surface,hit,point,n,media)?1:0;

    uint corrupt=0;for(uint row=544;row<1568;row++)if(any(pageFeedback[row]!=uint4(0xdeadc0deu)))corrupt++;output[8]=float4(corrupt,0,0,0);

}
