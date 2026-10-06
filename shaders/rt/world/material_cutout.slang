#include "../common/material_transport.slang"
[shader("anyhit")]
void material_cutout(inout SurfacePayload payload,BuiltInTriangleIntersectionAttributes attributes) {
    if((uint(camera.origin.w)&2u)!=0){uint ignored;InterlockedAdd(pageFeedback[513].y,1,ignored);}
    if(!visibleTexel(triangleIndex(InstanceIndex(),InstanceID(),GeometryIndex(),PrimitiveIndex()),attributes.barycentrics))IgnoreHit();
}
