#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_cache_train(){uint ticket=DispatchRaysIndex().x;if(ticket>=min(pageFeedback[1604].x,pageFeedback[1568].w))return;uint index=pageFeedback[rtRequestBase()+ticket*6].y;rtTrain(index);}
