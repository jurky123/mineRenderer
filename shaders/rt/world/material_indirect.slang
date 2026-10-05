#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_indirect() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;
    PathState path=paths[index];advance(path);paths[index]=path;
    output[index]=pathOutput(path);
}
