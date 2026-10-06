#include "../common/bsdf.slang"
[[vk::binding(6,0)]] ByteAddressBuffer assets;
struct LightSample {float3 wi,radiance;float distance,pdf;uint kind;};
struct TerrainSurface {Material material;};
bool visibleTexel(uint triangle,float2 bary){return true;}
TerrainSurface terrainSurface(uint triangle,float2 bary,uint instance){return (TerrainSurface)0;}
#include "../common/emissive.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void flame_sampling(uint3 tid:SV_DispatchThreadID){
    uint seed=(tid.x+1)*747796405u;float3 sum=0;
    for(uint connection=0;connection<min(3u,flameCount());connection++){
        LightSample light=flameConnection(float3(0),connection,seed);
        // A visibility mask acts after selection, just like the production shadow ray.
        bool visible=((uint)round(light.distance)%3)!=0;
        if(light.pdf>0&&visible)sum+=light.radiance;
    }
    result[tid.x]=float4(sum,1);
}
