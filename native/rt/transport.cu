#include "device.cuh"
#include "caustic_lookup.cuh"
#include "transport_impl.cuh"
extern "C" __device__ void __continuation_callable__transport(IncomingCall* call){call->result=incomingImplementation(call->origin,call->direction,call->seed,call->bounces,call->indirectOnly,call->firstLobe,&call->debug,call->excludeFirstSun,call->initialBsdfPdf);}
