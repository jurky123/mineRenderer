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
