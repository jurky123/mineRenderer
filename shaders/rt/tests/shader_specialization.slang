#define RT_CPU_FIXTURE 1
#define RT_POLICY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void shader_specialization(uint3 tid:SV_DispatchThreadID){
    pageFeedback[1568].x=0;
    output[0]=float4(directRis()?1:0,rtPolicy()?1:0,0,0);
    pageFeedback[1568].x=3;
    output[1]=float4(directRis()?1:0,rtPolicy()?1:0,0,0);
    // FULL rejects reuse without accessing a history/probe record or mutating the path.
    pageFeedback[1568].x=0;PathState path=(PathState)0;path.origin=float4(1,2,3,1);
    output[2]=float4(rtSparse(path,(TerrainSurface)0,(SurfacePayload)0,float3(0),float3(0,1,0),(MediumStack)0)?1:0,path.origin.xyz);
}
