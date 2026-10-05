#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_primary() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 ndc=(float2(pixel)+float2(rng(seed),rng(seed)))/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(normalize(farPoint.xyz/farPoint.w),0);path.throughput=float4(1,1,1,0);path.radiance.w=asfloat(seed);path.etaScale=1;path.previousDelta=1;
    storeMedia(path,cameraMedia());
    for(uint slot=0;slot<4;slot++)heldProbe(path,slot,float4(0));
    advance(path);paths[index]=path;output[index]=pathOutput(path);
}
