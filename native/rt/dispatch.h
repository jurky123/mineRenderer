#pragma once
#ifdef __CUDACC__
#define RT_DISPATCH inline __host__ __device__
#else
#define RT_DISPATCH inline
#endif
namespace rt {
// Keep individual reference kernels bounded without changing the target sample count or bounce model.
constexpr unsigned ReferenceLaunchPixels=8192;
constexpr unsigned ReferenceInitialPixels=64;
RT_DISPATCH unsigned referenceBudget(unsigned pixels,unsigned long long nanoseconds){if(nanoseconds>16000000ull)return pixels>8?pixels/2:8;if(nanoseconds<4000000ull)return pixels<1024?pixels*2:1024;return pixels;}
struct ReferenceSweep {unsigned offset=0,samples=0;
 RT_DISPATCH void reset(){offset=samples=0;}
 RT_DISPATCH unsigned count(unsigned pixels,unsigned target,unsigned budget)const{return samples>=target||offset>=pixels?0:(pixels-offset<budget?pixels-offset:budget);}
 RT_DISPATCH void advance(unsigned pixels,unsigned count){if(count==0)return;offset+=count;if(offset==pixels){offset=0;samples++;}}
};
RT_DISPATCH unsigned referenceBatch(unsigned target,unsigned completed){return completed<target?1u:0u;}
RT_DISPATCH unsigned referenceChunk(unsigned pixels,unsigned offset){return offset>=pixels?0u:(pixels-offset<ReferenceLaunchPixels?pixels-offset:ReferenceLaunchPixels);}
}
#undef RT_DISPATCH
