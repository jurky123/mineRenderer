#pragma once
#ifdef __CUDACC__
#define RT_DISPATCH inline __host__ __device__
#else
#define RT_DISPATCH inline
#endif
namespace rt {
// Keep individual reference kernels bounded without changing the target sample count or bounce model.
constexpr unsigned ReferenceLaunchPixels=8192;
RT_DISPATCH unsigned referenceBatch(unsigned target,unsigned completed){return completed<target?1u:0u;}
RT_DISPATCH unsigned referenceChunk(unsigned pixels,unsigned offset){return offset>=pixels?0u:(pixels-offset<ReferenceLaunchPixels?pixels-offset:ReferenceLaunchPixels);}
}
#undef RT_DISPATCH
