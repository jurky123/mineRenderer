#pragma once
float rrHalton(uint frame,uint base){uint n=frame%1024+1;float value=0,fraction=1;while(n>0){fraction/=float(base);value+=fraction*float(n%base);n/=base;}return value-.5;}
PathState primaryPath(uint index){
    uint local=index%(camera.width*camera.height);uint2 pixel=uint2(local%camera.width,local/camera.width);
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 samplePosition=(uint(camera.origin.w)&524288u)!=0?float2(.5+rrHalton(camera.frame,2),.5+rrHalton(camera.frame,3)):float2(rng(seed),rng(seed));
    float2 ndc=(float2(pixel)+samplePosition)/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(normalize(farPoint.xyz/farPoint.w),0);path.throughput=float4(1,1,1,0);path.radiance.w=asfloat(seed);path.etaScale=1;path.previousDelta=1;path.reserved0=index;storeMedia(path,cameraMedia());return path;
}
void initializePrimary(PathState path){
    uint index=path.reserved0;output[index]=0;pathAovs[index]=(PathAov)0;
    rtCount(0,0);if(rtPolicy()){uint p=rtProbe(index);pageFeedback[p]=0;pageFeedback[p+1]=0;pageFeedback[p+2]=0;}
    for(uint slot=0;slot<4;slot++)heldProbe(path,slot,float4(0));
    if(index<camera.width*camera.height){uint base=(camera.width*camera.height*max(1u,camera.padding))+4;for(uint plane=0;plane<8;plane++)output[base+plane*camera.width*camera.height+index]=float4(0);output[base+camera.width*camera.height*2+index]=float4(path.direction.xyz,0);}
}
uint primaryHit(uint index){return 1648u+camera.width*camera.height*max(1u,camera.padding)*2+index*2;}
