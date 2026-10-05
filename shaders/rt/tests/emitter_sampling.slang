#include "../common/bsdf.slang"
[[vk::binding(6,0)]] ByteAddressBuffer assets;
struct LightSample {float3 wi,radiance;float distance,pdf;uint kind;};
struct TerrainSurface {Material material;};
bool visibleTexel(uint triangle,float2 bary){return true;}
TerrainSurface terrainSurface(uint triangle,float2 bary,uint instance){TerrainSurface surface=(TerrainSurface)0;surface.material.emission=float3(triangle+1);return surface;}
#include "../common/emissive.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void emitter_sampling(uint3 tid:SV_DispatchThreadID){
    uint seed=(tid.x+1)*747796405u;LightSample light=sampleEmitter(float3(0),seed);
    result[tid.x]=float4(light.radiance.x,light.pdf,emitterHitPdf((uint)light.radiance.x-1,float3(0),light.wi*light.distance),light.distance);
}
