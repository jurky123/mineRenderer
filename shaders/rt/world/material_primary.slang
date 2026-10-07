#include "../common/material_transport.slang"
float rrHalton(uint frame,uint base){uint n=frame%1024+1;float value=0,fraction=1;while(n>0){fraction/=float(base);value+=fraction*float(n%base);n/=base;}return value-.5;}
[shader("raygeneration")]
void material_primary() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 samplePosition=(uint(camera.origin.w)&524288u)!=0?float2(.5+rrHalton(camera.frame,2),.5+rrHalton(camera.frame,3)):float2(rng(seed),rng(seed));
    float2 ndc=(float2(pixel)+samplePosition)/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(normalize(farPoint.xyz/farPoint.w),0);path.throughput=float4(1,1,1,0);path.radiance.w=asfloat(seed);path.etaScale=1;path.previousDelta=1;
    path.reserved0=index;output[index]=0;pathAovs[index]=(PathAov)0;
    rtCount(0,0);if(rtPolicy()){uint p=rtProbe(index);pageFeedback[p]=0;pageFeedback[p+6]=0;pageFeedback[p+7]=0;}
    storeMedia(path,cameraMedia());
    for(uint slot=0;slot<4;slot++)heldProbe(path,slot,float4(0));
    if(DispatchRaysIndex().z==0){uint base=camera.width*camera.height*max(1u,camera.padding)+4;for(uint plane=0;plane<8;plane++)output[base+plane*camera.width*camera.height+pixel.y*camera.width+pixel.x]=float4(0);}
    if(DispatchRaysIndex().z==0){uint base=camera.width*camera.height*max(1u,camera.padding)+4;output[base+camera.width*camera.height*2+pixel.y*camera.width+pixel.x]=float4(path.direction.xyz,0);}
    advance(path);if(!rtPolicy())rtCount(0,1);else if(pageFeedback[rtProbe(index)+6].z==0)rtCount(0,1);storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
}
