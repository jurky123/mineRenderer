#define RT_FULL_REFERENCE 1
#include "device.cuh"
static __device__ float3 causticIrradiance(float3,float3){return v(0,0,0);}
#include "transport_impl.cuh"
extern "C" __device__ void __continuation_callable__transport(IncomingCall* call){call->result=incomingImplementation(call->origin,call->direction,call->seed,call->bounces,call->indirectOnly,call->firstLobe,&call->debug,call->excludeFirstSun,call->initialBsdfPdf);}
