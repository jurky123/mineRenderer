#include "../common/bringup.slang"
[shader("closesthit")]
void closest_hit(inout SurfacePayload payload, BuiltInTriangleIntersectionAttributes attributes) {
    // InstanceCustomIndex indexes a triangle-normal base; one BLAS per section.
    payload.normal = normalize(normals[InstanceID() + PrimitiveIndex()].xyz);
    payload.distance = RayTCurrent();
}
