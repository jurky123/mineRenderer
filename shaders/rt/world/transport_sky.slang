#include "../common/transport_test.slang"
[shader("miss")]
void transport_sky(inout SurfacePayload payload) { payload.normal = float3(0.0); payload.distance = -1.0; }
