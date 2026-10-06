#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_primary() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 ndc=(float2(pixel)+float2(rng(seed),rng(seed)))/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(normalize(farPoint.xyz/farPoint.w),0);path.throughput=float4(1,1,1,0);path.radiance.w=asfloat(seed);path.etaScale=1;path.previousDelta=1;
    path.reserved0=index;output[index]=0;pathAovs[index]=(PathAov)0;
    storeMedia(path,cameraMedia());
    for(uint slot=0;slot<4;slot++)heldProbe(path,slot,float4(0));
    if(DispatchRaysIndex().z==0){uint base=camera.width*camera.height*max(1u,camera.padding)+4;for(uint plane=0;plane<7;plane++)output[base+plane*camera.width*camera.height+pixel.y*camera.width+pixel.x]=float4(0);}
    advance(path);storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
}
