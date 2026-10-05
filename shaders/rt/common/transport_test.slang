#pragma once
// Geometry-only wavefront acceptance scene. No claim of production material parity.
#include "bsdf.slang"
#include "surface.slang"
struct SurfacePayload { float3 normal; float distance; };
[[vk::binding(0, 0)]] RaytracingAccelerationStructure world;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(2, 0)]] StructuredBuffer<float4> normals;
struct Camera { float4 inverseClip0; float4 inverseClip1; float4 inverseClip2; float4 inverseClip3; float4 origin; uint width; uint height; uint frame; uint padding; };
[[vk::binding(3, 0)]] ConstantBuffer<Camera> camera;
// Four vec4s per pixel: origin/active, direction/pdf, throughput/depth, radiance/seed bits.
struct PathState { float4 origin; float4 direction; float4 throughput; float4 radiance; };
[[vk::binding(4, 0)]] RWStructuredBuffer<PathState> paths;
float3 testSky(float3 direction) { return lerp(float3(.08,.10,.14),float3(.55,.72,1),saturate(direction.y*.5+.5)); }
SurfacePayload traceSurface(float3 origin,float3 direction,float maximum=4096.0) {
    RayDesc ray; ray.Origin=origin;ray.Direction=direction;ray.TMin=.001;ray.TMax=maximum;
    SurfacePayload hit={float3(0),-1};
    TraceRay(world,RAY_FLAG_FORCE_OPAQUE,255,0,0,0,ray,hit);
    return hit;
}
void advance(inout PathState path) {
    if(path.origin.w==0)return;
    uint seed=asuint(path.radiance.w);
    SurfacePayload hit=traceSurface(path.origin.xyz,path.direction.xyz);
    if(hit.distance<0) {
        // Environment sampled solely through the BSDF: no double counting, no environment NEE.
        path.radiance.xyz+=path.throughput.xyz*testSky(path.direction.xyz);path.origin.w=0;
    } else {
        float3 point=path.origin.xyz+path.direction.xyz*hit.distance;
        float3 n=dot(hit.normal,path.direction.xyz)<0?hit.normal:-hit.normal;
        Frame frame=makeFrame(n);Material material=defaultMaterial();material.type=DIFFUSE;material.baseColor=float3(.65);
        float3 sun=normalize(float3(.4,.8,.25));
        BsdfEval direct=evalBsdf(material,frame,-path.direction.xyz,sun);
        if(direct.pdf>0) {
            SurfacePayload shadow=traceSurface(offsetRayOrigin(point,n,sun),sun);
            if(shadow.distance<0)path.radiance.xyz+=path.throughput.xyz*direct.f*max(0,dot(n,sun))*float3(2.5,2.2,1.8);
        }
        BsdfSample sample=sampleBsdf(material,frame,-path.direction.xyz,seed);
        path.throughput.xyz*=sample.weight;path.throughput.w+=1;
        path.origin=float4(offsetRayOrigin(point,n,sample.wi),sample.pdf>0?1:0);
        path.direction=float4(sample.wi,sample.pdf);
        if(path.throughput.w>=3) {
            float survival=clamp(maxComponent(path.throughput.xyz),.05,.95);
            if(rng(seed)>=survival)path.origin.w=0;else path.throughput.xyz/=survival;
        }
    }
    path.radiance.w=asfloat(seed);
}
