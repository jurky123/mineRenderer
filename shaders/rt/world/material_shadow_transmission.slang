#include "../common/material_transport.slang"
[shader("anyhit")]
void material_shadow_transmission(inout ShadowPayload payload,BuiltInTriangleIntersectionAttributes attributes){
    if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[513].y,1,ignored);}
    uint triangle=triangleIndex(InstanceIndex(),InstanceID(),GeometryIndex(),PrimitiveIndex());
    if(!visibleTexel(triangle,attributes.barycentrics)){IgnoreHit();return;}
    TerrainSurface surface=terrainSurface(triangle,attributes.barycentrics,InstanceIndex());
    float3 point=WorldRayOrigin()+WorldRayDirection()*RayTCurrent();
    if(payload.flame!=0&&(surface.flags&32)!=0&&all(floor(point-terrainVertex(triangle,0).n*.001)==payload.sourceBlock)){IgnoreHit();return;}
    Material m=surface.material;
    if(!dielectric(m)&&m.type!=DIFFUSE_TRANSMISSION)return;
    if(m.mediumId==payload.mediumId&&dot(surface.geometryNormal,WorldRayDirection())>0)payload.exitDistance=min(payload.exitDistance,RayTCurrent());
    // Realtime straight-through approximation, deliberately separate from the
    // ordered medium/Fresnel reference estimator. Each interface is visited once.
    float cosine=max(.05,abs(dot(surface.geometryNormal,WorldRayDirection())));
    float fresnel=fresnelDielectric(cosine,m.ior);
    float thickness=m.type==THIN_DIELECTRIC?m.thickness:.5;
    payload.transmission*=m.type==DIFFUSE_TRANSMISSION?m.baseColor*m.transmission:expNeg(m.sigmaA,thickness/cosine)*((1-fresnel)*m.transmission);
    if(maxComponent(payload.transmission)<1e-5){payload.transmission=0;AcceptHitAndEndSearch();return;}
    IgnoreHit();
}
