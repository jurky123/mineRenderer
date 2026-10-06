#include "../common/material_transport.slang"
// Scalar visibility payload; transparent interfaces never block this solid-only probe.
[shader("anyhit")]
void material_shadow_cutout(inout uint blocked,BuiltInTriangleIntersectionAttributes attributes) {
    if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[513].y,1,ignored);}
    if(GeometryIndex()==2){IgnoreHit();return;}
    if(!visibleTexel(triangleIndex(InstanceIndex(),InstanceID(),GeometryIndex(),PrimitiveIndex()),attributes.barycentrics))IgnoreHit();
}
