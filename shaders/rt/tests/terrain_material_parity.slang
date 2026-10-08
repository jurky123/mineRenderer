#include "../common/terrain_material.slang"
#include "../common/water_surface.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void terrain_material_parity(uint3 tid:SV_DispatchThreadID) {
    uint i=tid.x;float2 bary=float2(.25,.5);
    TerrainSurface surface=terrainSurface(i,bary,i);
    ShadowSurface shadow=terrainShadow(i,bary,i);bool transparent=dielectric(surface.material)||surface.material.type==DIFFUSE_TRANSMISSION;
    float shadowError=transparent?max(maxComponent(abs(shadow.sigmaA-surface.material.sigmaA)),max(maxComponent(abs(shadow.baseColor-surface.material.baseColor)),max(abs(shadow.ior-surface.material.ior),abs(shadow.transmission-surface.material.transmission)))):0;
    result[i*14+12]=float4(shadowError,transparent&&shadow.type!=surface.material.type?1:0,transparent&&shadow.mediumId!=surface.material.mediumId?1:0,transparent?maxComponent(abs(shadow.geometryNormal-surface.geometryNormal)):0);
    TerrainSurface primary;bool eligible=terrainPrimaryGuide(i,bary,i,primary);float guideError=eligible?max(maxComponent(abs(primary.material.baseColor-surface.material.baseColor)),maxComponent(abs(primary.frame.n-surface.frame.n))):0;
    if(eligible)guideError=max(guideError,max(abs(primary.material.alphaV-surface.material.alphaV),abs(primary.material.f0-surface.material.f0)));
    result[i*14+13]=float4(guideError,eligible&&primary.material.type!=surface.material.type?1:0,eligible&&primary.material.mediumId!=surface.material.mediumId?1:0,eligible?1:0);
    Material m=surface.material;TerrainVertex v=terrainVertex(i,0);
    result[i*14]=float4(m.baseColor,(float)m.type);
    result[i*14+1]=float4(m.microfacetAlpha,m.alphaV,m.f0,m.ior);
    result[i*14+2]=float4(m.coatWeight,m.coatAlpha,m.coatIOR,m.transmission);
    result[i*14+3]=float4(m.sigmaA,m.phaseG);
    result[i*14+4]=float4(m.sigmaS,(float)m.materialId);
    result[i*14+5]=float4(surface.frame.n,visibleTexel(i,bary)?1:0);
    result[i*14+6]=float4(m.emission,(float)m.mediumId);
    result[i*14+7]=float4(v.p,(float)v.flags);
    result[i*14+8]=float4(terrainUv(v,terrainVertex(i,1),terrainVertex(i,2),bary),0,0);
    result[i*14+9]=float4(m.eta,m.conductorId);
    bool deferred;TerrainSurface guide=terrainSurface(i,bary,i,true,deferred);Frame completed=deferred?terrainCompleteFrame(i,bary):guide.frame;
    result[i*14+11]=float4(deferred?1:0,max(maxComponent(abs(completed.n-surface.frame.n)),maxComponent(abs(terrainEmission(i,bary)-m.emission))),maxComponent(abs(completed.t-surface.frame.t)),maxComponent(abs(completed.b-surface.frame.b)));
    result[i*14+10]=float4(terrainWaterNormal(float3(0,1,0),float3(i*.03125,64,-.125*i),float3(0,65,0),12.5,.09,3.4,.7,1),0);
}
