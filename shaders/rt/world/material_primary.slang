#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_primary() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    uint seed=(index+1)*747796405u+(camera.frame+1)*2891336453u;
    float2 ndc=(float2(pixel)+float2(rng(seed),rng(seed)))/float2(camera.width,camera.height)*2-1;
    float4 farPoint=camera.inverseClip0*ndc.x+camera.inverseClip1*ndc.y+camera.inverseClip2*.00001+camera.inverseClip3;
    PathState path=(PathState)0;path.origin=float4(camera.origin.xyz,1);path.direction=float4(normalize(farPoint.xyz/farPoint.w),0);path.throughput=float4(1,1,1,0);path.radiance.w=asfloat(seed);path.etaScale=1;path.previousDelta=1;
    storeMedia(path,cameraMedia());
    for(uint slot=0;slot<4;slot++)heldProbe(path,slot,float4(0));
    if(DispatchRaysIndex().z==0){uint base=camera.width*camera.height*max(1u,camera.padding)+4;for(uint plane=0;plane<6;plane++)output[base+plane*camera.width*camera.height+pixel.y*camera.width+pixel.x]=float4(0);}
    advance(path);storePath(index,path);output[index]=pathOutput(path);
    // Stable pixel-center visibility for reconstruction; noisy paths retain independent jitter.
    if(DispatchRaysIndex().z==0&&camera.origin.w>0){
        float2 center=(float2(pixel)+.5)/float2(camera.width,camera.height)*2-1;
        float4 farCenter=camera.inverseClip0*center.x+camera.inverseClip1*center.y+camera.inverseClip2*.00001+camera.inverseClip3;
        float3 direction=normalize(farCenter.xyz/farCenter.w);SurfacePayload guide=traceSurface(camera.origin.xyz,direction);
        uint base=camera.width*camera.height*max(1u,camera.padding)+4,local=pixel.y*camera.width+pixel.x;
        if(guide.distance>=0){
            TerrainSurface surface=terrainSurface(guide.triangle,guide.bary,guide.instance);float3 point=camera.origin.xyz+direction*guide.distance;
            float3 normal=dot(surface.geometryNormal,-direction)>=0?surface.geometryNormal:-surface.geometryNormal;
            output[base+local]=float4(surface.material.baseColor,(surface.flags&128)!=0?-float(surface.material.type+1):float(surface.material.type+1));
            output[base+camera.width*camera.height+local]=float4(normal,surface.material.microfacetAlpha);
            output[base+camera.width*camera.height*2+local]=float4(point,guide.distance);
        }else output[base+camera.width*camera.height*2+local]=float4(direction,0);
    }
}
