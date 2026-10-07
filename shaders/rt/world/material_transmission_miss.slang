#include "../common/material_transport.slang"
[shader("miss")]
void material_transmission_miss(inout ShadowPayload payload){payload.visible=1;}
