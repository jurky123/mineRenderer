#include "../common/bringup.slang"
[shader("miss")]
void sky(inout SurfacePayload payload) { payload.normal = float3(0.0); payload.distance = -1.0; }
