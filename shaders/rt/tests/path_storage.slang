#define RT_CPU_FIXTURE 1
#define RT_STATE_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void path_storage(uint3 tid:SV_DispatchThreadID){
    // Second lane, eight nested media, and split radiance/AOV storage across dispatch boundaries.
    uint index=camera.width+1;
    PathState path=(PathState)0;path.reserved0=index;path.reserved1=405;path.origin=float4(1,2,3,1);path.direction=float4(.1,.2,.3,.4);path.throughput=float4(.5,.6,.7,2);path.etaScale=1.2;path.previousDelta=1;path.channel=2;path.radiance=float4(2,3,4,asfloat(0x12345678u));
    path.direction.xyz=normalize(path.direction.xyz);
    MediumStack media;media.count=8;for(int i=0;i<8;i++){Medium m={float3(i,.2,.3),float3(.4,i,.6),.7,1+i*.1,42u+uint(i)};media.entries[i]=m;}storeMedia(path,media);
    pathAovs[index]=(PathAov)0;output[index]=float4(2,3,4,1);storePath(index,path);
    PathState resumed=loadPath(index);addRadiance(resumed,float3(1,2,3));resumed.radiance.w=asfloat(0x87654321u);storePath(index,resumed);output[index]=pathOutput(resumed);
    PathState again=loadPath(index);MediumStack restored=loadMedia(again);
    output[0]=float4(output[index].xyz,asfloat(loadHot(index).seed));
    output[1]=float4(pathAovs[index].refraction.xyz,again.etaScale);
    output[2]=float4(restored.entries[7].sigmaA,restored.entries[7].id);
    output[3]=float4(again.origin.xyz,again.mediumCount);
    output[7]=float4(length(again.direction.xyz-path.direction.xyz),again.direction.w,again.throughput.w,again.origin.w);
    output[5]=float4(again.reserved1,0,0,0);
    output[6]=float4(triangleIndex(0,17,0,2),triangleIndex(0,17,1,2),triangleIndex(0,17,2,2),0);
    again.padding=1;again.previousDelta=1;again.channel=1;storePath(index,again);again=loadPath(index);output[4]=float4(again.padding,again.previousDelta,again.channel,pathOutput(again).w);
}
