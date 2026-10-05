#include "../common/transport_test.slang"
[shader("raygeneration")]
void transport_primary() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 ndc=(float2(pixel)+float2(rng(seed),rng(seed)))/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path={float4(camera.origin.xyz,1),float4(normalize(farPoint.xyz/farPoint.w),0),float4(1,1,1,0),float4(0,0,0,asfloat(seed))};
    advance(path);paths[index]=path;output[index]=float4(path.radiance.xyz,1);
}
