#include "../common/material_transport.slang"
#include "../common/primary_state.slang"
[shader("raygeneration")]
void material_primary_shade(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    if((uint(camera.origin.w)&2097152u)!=0){if(DispatchRaysIndex().x>=pageFeedback[520].x)return;index=pageFeedback[1648u+DispatchRaysIndex().x].x;}
    uint record=primaryHit(index);if(pageFeedback[record+1].y==0)return;
    uint4 words=pageFeedback[record];SurfacePayload hit;hit.distance=asfloat(words.x);hit.bary=asfloat(words.yz);hit.triangle=words.w;hit.instance=pageFeedback[record+1].x;
    PathState path=primaryPath(index);advanceHit(path,hit,true,pageFeedback[record+1].z!=0);
    if(!rtPolicy())rtCount(0,1);else if(pageFeedback[rtProbe(index)+1].z==0)rtCount(0,1);
    storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
}
