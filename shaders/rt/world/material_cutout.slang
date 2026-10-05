#include "../common/material_transport.slang"
[shader("anyhit")]
void material_cutout(inout SurfacePayload payload,BuiltInTriangleIntersectionAttributes attributes) {
    if(!visibleTexel(InstanceID()+PrimitiveIndex(),attributes.barycentrics))IgnoreHit();
}
