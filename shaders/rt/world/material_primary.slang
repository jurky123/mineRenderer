#include "../common/material_transport.slang"
#include "../common/primary_state.slang"
[shader("raygeneration")]
void material_primary(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    PathState path=primaryPath(index);initializePrimary(path);
    advance(path);if(!rtPolicy())rtCount(0,1);else if(pageFeedback[rtProbe(index)+1].z==0)rtCount(0,1);
    storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
}
