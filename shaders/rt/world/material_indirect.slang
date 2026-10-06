#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_indirect() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    PathState path=loadPath(index);advance(path);storePath(index,path);
    output[index]=pathOutput(path);
}
