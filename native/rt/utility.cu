#include "contract.h"
#include "environment.h"
static __device__ float3 v(float x,float y,float z){return make_float3(x,y,z);}
extern "C" __global__ void rtUtility(RtParams params){unsigned i=params.launchOffset+blockIdx.x*blockDim.x+threadIdx.x;
if(blockIdx.x*blockDim.x+threadIdx.x>=params.utilityCount)return;
 if(params.mode==13){int row=i;if(row>=rt::EnvironmentHeight)return;float total=0;for(int x=0;x<rt::EnvironmentWidth;x++){auto c=params.environmentMap[row*rt::EnvironmentWidth+x];total+=fmaxf(0,c.x*.2126f+c.y*.7152f+c.z*.0722f)*rt::environmentSolidAngle(row);params.environmentCdf[row*rt::EnvironmentWidth+x]=total;}return;}
 if(params.mode==14){if(i)return;float total=0;for(int y=0;y<rt::EnvironmentHeight;y++){total+=params.environmentCdf[y*rt::EnvironmentWidth+rt::EnvironmentWidth-1];params.environmentCdf[rt::EnvironmentWidth*rt::EnvironmentHeight+y]=total;}return;}
 if(params.mode==12){if(i<(unsigned)(params.width*params.height))for(int kind=0;kind<9;kind++)atomicAdd(params.counterTotals+kind,params.counters[i*9+kind]);return;}
 if(params.mode==10){float cell=.25f;int cx=(int)floorf(params.camera.x/cell)-32,cz=(int)floorf(params.camera.z/cell)-32,x=cx+(i&63),z=cz+((i>>6)&63),slot=(x&63)|((z&63)<<6);auto record=params.caustics+slot*2;record[0]=make_float4(x*cell,0,z*cell,0);record[1]=make_float4(0,0,0,0);return;}
 if(params.mode==11){auto a=params.caustics+i*2,b=params.causticHistory+i*2;bool same=a[0].w>0&&b[0].w>0&&fabsf(a[0].x-b[0].x)+fabsf(a[0].z-b[0].z)<.01f&&fabsf(a[0].y-b[0].y)<.1f;if(same){a[1].x=.2f*a[1].x+.8f*b[1].x;a[1].y=.2f*a[1].y+.8f*b[1].y;a[1].z=.2f*a[1].z+.8f*b[1].z;}b[0]=a[0];b[1]=a[1];return;}
 if(params.mode==2){auto a=params.probes+i*8;float margin=8.f*(1<<(i/512));float3 p=v(a[0].x,a[0].y,a[0].z);
  if(p.x>=params.invalidateMin.x-margin&&p.x<=params.invalidateMax.x+margin&&p.y>=params.invalidateMin.y-margin&&p.y<=params.invalidateMax.y+margin&&p.z>=params.invalidateMin.z-margin&&p.z<=params.invalidateMax.z+margin){a[0].w=0;a[7].w+=1;}return;
 }
 if(params.mode==4){params.previousKey[i]=params.surfaceKey[i];params.previousSignal[i]=make_float4(params.specular[i].w,params.transmission[i].w,params.material[i].x,params.material[i].z);float4 p=params.position[i];params.previousPosition[i]=make_float4(p.x+params.camera.x,p.y+params.camera.y,p.z+params.camera.z,p.w);params.previousNormal[i]=params.normal[i];return;}}
