#include "device.cuh"
#include "caustics.cuh"
extern "C" __global__ void __raygen__caustics(){unsigned i=optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;causticPhoton(i,seed);}
