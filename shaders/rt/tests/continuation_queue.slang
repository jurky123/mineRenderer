#define RT_CPU_FIXTURE 1
#define RT_QUEUE_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void continuation_queue(uint3 tid:SV_DispatchThreadID){
    PathState path=(PathState)0;path.origin.w=camera.frame==4||tid.x%3!=0?1:0;path.padding=camera.frame!=4&&tid.x%5==0?1:0;path.throughput.w=camera.frame;
    appendContinuation(tid.x,path);
}
