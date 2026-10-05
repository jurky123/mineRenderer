#include "../common/terrain_material.slang"
#include "../common/water_surface.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void terrain_material_parity(uint3 tid:SV_DispatchThreadID) {
    uint i=tid.x;float2 bary=float2(.25,.5);
    TerrainSurface surface=terrainSurface(i,bary,i);
    Material m=surface.material;TerrainVertex v=terrainVertex(i,0);
    result[i*11]=float4(m.baseColor,(float)m.type);
    result[i*11+1]=float4(m.microfacetAlpha,m.alphaV,m.f0,m.ior);
    result[i*11+2]=float4(m.coatWeight,m.coatAlpha,m.coatIOR,m.transmission);
    result[i*11+3]=float4(m.sigmaA,m.phaseG);
    result[i*11+4]=float4(m.sigmaS,(float)m.materialId);
    result[i*11+5]=float4(surface.frame.n,visibleTexel(i,bary)?1:0);
    result[i*11+6]=float4(m.emission,(float)m.mediumId);
    result[i*11+7]=float4(v.p,(float)v.flags);
    result[i*11+8]=float4(terrainUv(v,terrainVertex(i,1),terrainVertex(i,2),bary),0,0);
    result[i*11+9]=float4(m.eta,m.conductorId);
    result[i*11+10]=float4(terrainWaterNormal(float3(0,1,0),float3(i*.03125,64,-.125*i),float3(0,65,0),12.5,.09,3.4,.7,1),0);
}
