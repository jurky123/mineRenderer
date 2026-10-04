#include "device.cuh"
#include "callable_api.cuh"
// RGB visibility with medium identity. This is straight-line NEE, not focused caustics.
static __noinline__ __device__ float3 visibilityImplementation(float3 o,float3 d,float maximum,rt::MediumStack stack={}){countOperation(1);float3 T=v(1,1,1);float travel=0;
// Keep interface traversal iterative in the driver optimizer.
#pragma unroll 1
for(int k=0;k<24;k++){auto h=trace(o,d,fmaxf(0,maximum-travel));if(!h.hit){if(stack.count)T=prod(T,cv(rt::expNeg(stack.entries[stack.count-1].sigmaA+stack.entries[stack.count-1].sigmaS,fmaxf(0,maximum-travel))));return T;}if(!rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::DIFFUSE_TRANSMISSION)return v(0,0,0);bool entering=dot3(h.geometryNormal,d)<0;rt::Medium medium={h.bsdf.sigmaA,h.bsdf.sigmaS,h.bsdf.phaseG,h.bsdf.ior,h.bsdf.mediumId};if(!entering&&stack.count==0&&rt::dielectric(h.bsdf)&&h.bsdf.type!=rt::THIN_DIELECTRIC)stack.enter(medium);if(stack.count)T=prod(T,cv(rt::expNeg(stack.entries[stack.count-1].sigmaA+stack.entries[stack.count-1].sigmaS,h.distance)));else if(!entering&&h.bsdf.type!=rt::THIN_DIELECTRIC)T=prod(T,cv(rt::expNeg(medium.sigmaA+medium.sigmaS,h.distance)));
 if(h.bsdf.type==rt::DIFFUSE_TRANSMISSION){T=prod(T,mul(h.color,h.bsdf.transmission));}
 else{float from=stack.ior(),to=entering?h.ior:stack.outside(medium.id),F=rt::fresnelDielectric(fabsf(dot3(h.n,d)),h.bsdf.type==rt::THIN_DIELECTRIC?h.ior/from:to/from);if(h.bsdf.type==rt::THIN_DIELECTRIC)F=2*F/(1+F);T=mul(T,(1-F)*h.bsdf.transmission);if(h.bsdf.type==rt::THIN_DIELECTRIC)T=prod(T,cv(rt::expNeg(medium.sigmaA,h.bsdf.thickness/fmaxf(.01f,fabsf(dot3(h.n,d))))));else if(entering){if(!stack.enter(medium))return v(0,0,0);}else if(!stack.exit(medium.id)&&stack.count&&stack.entries[stack.count-1].id==0xfffffffeu)stack.count--;}
 travel+=h.distance;if(travel>=maximum)return T;o=spawn(h,d);}return v(0,0,0);}

extern "C" __device__ void __continuation_callable__visibility(VisibilityCall* call){call->result=visibilityImplementation(call->origin,call->direction,call->maximum,call->media);}
