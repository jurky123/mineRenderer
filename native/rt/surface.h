#pragma once
#ifdef __CUDACC__
#define RT_SURFACE_FN static __forceinline__ __device__
#else
#define RT_SURFACE_FN static inline
#endif
namespace rt {
// Pinned 26.2 CUTOUT_TERRAIN=0.5; supported entity cutouts=0.1. Compare before rounding tint alpha.
RT_SURFACE_FN bool cutoutVisible(unsigned textureAlpha,unsigned tintAlpha,unsigned flags){unsigned product=textureAlpha*tintAlpha;return (flags&16)?product*10>=65025u:product*2>=65025u;}
}

namespace rt {
// Round away from the interface using representable floats, rather than stepping .02 blocks.
// Scene coordinates must remain representable; this does not recover precision already lost in AS transforms.
RT_SURFACE_FN float offsetComponent(float p,float n){if(n==0)return p;float step=fmaxf(1e-6f,fabsf(p)*2.384185791015625e-7f);float q=p+n*step;return nextafterf(q,n>0?3.402823466e38f:-3.402823466e38f);}
RT_SURFACE_FN Vec offsetRayOrigin(Vec p,Vec geometricNormal,Vec direction){Vec n=dot(geometricNormal,direction)>=0?geometricNormal:geometricNormal*-1;return V(offsetComponent(p.x,n.x),offsetComponent(p.y,n.y),offsetComponent(p.z,n.z));}
RT_SURFACE_FN bool validShadingHemisphere(Vec ng,Vec ns,Vec wo,Vec wi){return dot(ng,wo)*dot(ng,wi)*dot(ns,wo)*dot(ns,wi)>0;}
// Veach/PBRT adjoint correction: camera (radiance) transport is unity; light paths use the Jacobian.
RT_SURFACE_FN float shadingNormalCorrection(Vec ng,Vec ns,Vec wo,Vec wi,bool importance){if(!validShadingHemisphere(ng,ns,wo,wi))return 0;if(!importance)return 1;float denominator=fabsf(dot(ng,wo)*dot(ns,wi));return denominator>1e-8f?fabsf(dot(ns,wo)*dot(ng,wi))/denominator:0;}
}
