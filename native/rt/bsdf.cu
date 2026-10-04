#include "device.cuh"
#include "callable_api.cuh"
extern "C" __device__ void __direct_callable__evaluate(BsdfCall* call){call->evaluation=rt::evalBsdf(*call->material,*call->frame,call->wo,call->wi,call->eta,call->forced);}
extern "C" __device__ void __direct_callable__sample(BsdfCall* call){call->sample=rt::sampleBsdf(*call->material,*call->frame,call->wo,call->seed,call->eta,call->forced);}
extern "C" __device__ void __direct_callable__evaluate_glossy(BsdfCall* call){call->evaluation=rt::evalGlossy(*call->material,*call->frame,call->wo,call->wi);}
extern "C" __device__ void __direct_callable__sample_glossy(BsdfCall* call){call->sample=rt::sampleGlossy(*call->material,*call->frame,call->wo,call->seed);}
