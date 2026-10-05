#include "../common/material_transport.slang"
[shader("closesthit")]
void material_closest_hit(inout SurfacePayload payload,BuiltInTriangleIntersectionAttributes attributes) {
    payload.distance=RayTCurrent();payload.bary=attributes.barycentrics;
    payload.triangle=InstanceID()+PrimitiveIndex();payload.instance=InstanceIndex();
}
