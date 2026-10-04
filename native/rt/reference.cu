#define RT_FULL_REFERENCE 1
#include "device.cuh"
static __device__ float3 causticIrradiance(float3,float3){return v(0,0,0);}
#include "transport.cuh"
extern "C" __global__ void __raygen__reference(){unsigned i=params.launchOffset+optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;if(i>=(unsigned)(params.width*params.height))return;

  if(params.mode!=0)return;
  float x=2*((i%params.width+random(seed))/params.width)-1,y=2*((i/params.width+random(seed))/params.height)-1;
  auto m=params.inverseCamera;float z=.00001f,w=m[3]*x+m[7]*y+m[11]*z+m[15];
  float3 ray=norm(v((m[0]*x+m[4]*y+m[8]*z+m[12])/w,(m[1]*x+m[5]*y+m[9]*z+m[13])/w,(m[2]*x+m[6]*y+m[10]*z+m[14])/w));
  float3 result=incoming(params.camera,ray,seed,12,false);int slot=i;
  auto old=params.referenceSamples?params.referenceSum[slot]:make_float4(0,0,0,0);
  float weight=params.referenceSamples+1;float3 sum=add(v(old.x,old.y,old.z),result);
  params.referenceSum[slot]=make_float4(sum.x,sum.y,sum.z,weight);result=mul(sum,1/weight);
  params.diffuse[i]=make_float4(result.x,result.y,result.z,1);params.specular[i]=params.transmission[i]=make_float4(0,0,0,0);return;
 }
