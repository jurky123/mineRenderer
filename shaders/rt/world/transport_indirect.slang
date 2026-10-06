#include "../common/transport_test.slang"
[shader("raygeneration")]
void transport_indirect() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    PathState path=paths[index];advance(path);paths[index]=path;
    output[index]=float4(path.radiance.xyz,1);
}
