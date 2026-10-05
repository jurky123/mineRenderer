#include "../common/material_transport.slang"
[shader("miss")]
void material_sky(inout SurfacePayload payload){payload.distance=-1;}
