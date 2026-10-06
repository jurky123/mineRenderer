#include "../common/material_transport.slang"
// Replay a bounded set of real opaque visibility rays; disabled outside profiling.
[shader("raygeneration")]
void material_visibility_benchmark(){
    uint index=DispatchRaysIndex().x;if(index>=min(pageFeedback[541].x,256u))return;
    float4 source=asfloat(pageFeedback[544u+index]);float3 direction=asfloat(pageFeedback[800u+index]).xyz;
    uint blocked=opaqueBlocked(source.xyz,direction,source.w)?1u:0u;
#ifdef RT_RAY_QUERY
    pageFeedback[1312u+index]=uint4(blocked,0,0,0);
    if((uint(camera.origin.w)&1024u)==0&&blocked!=pageFeedback[1056u+index].x){uint ignored;InterlockedAdd(pageFeedback[541].y,1,ignored);}
#else
    pageFeedback[1056u+index]=uint4(blocked,0,0,0);
    if((uint(camera.origin.w)&1024u)!=0&&blocked!=pageFeedback[1312u+index].x){uint ignored;InterlockedAdd(pageFeedback[541].y,1,ignored);}
#endif
}
