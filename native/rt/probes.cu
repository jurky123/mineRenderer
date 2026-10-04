#include "device.cuh"
#include "caustic_lookup.cuh"
#include "transport.cuh"
extern "C" __global__ void __raygen__probes(){
 unsigned i=optixGetLaunchIndex().x,seed=i*9781u+params.frame*6271u+1;
int cascade=i/512;float spacing=4.f*(1<<cascade);int cx=(int)floorf(params.camera.x/spacing)-4,cy=(int)floorf(params.camera.y/spacing)-4,cz=(int)floorf(params.camera.z/spacing)-4;
  int gx=cx+(i&7),gy=cy+((i>>3)&7),gz=cz+((i>>6)&7);int slot=cascade*512+((gx&7)|((gy&7)<<3)|((gz&7)<<6));float3 p=v(gx*spacing,gy*spacing,gz*spacing);auto a=params.probes+slot*8;
  bool same=a[0].w>0&&fabsf(a[0].x-p.x)+fabsf(a[0].y-p.y)+fabsf(a[0].z-p.z)<.1f;
  unsigned quality=(params.options>>16)&3;int period=quality==0?32:quality==1?24:16,rays=quality==0?8:quality==1?12:16;if(((i+params.frame)%period)!=0)return;
  float3 origin=p;if(same)origin=add(origin,v(a[6].x,a[6].y,a[6].z));
  else{float nearest=2.f;float3 escape=v(0,0,0);const float3 axes[6]={v(1,0,0),v(-1,0,0),v(0,1,0),v(0,-1,0),v(0,0,1),v(0,0,-1)};for(int axis=0;axis<6;axis++){auto h=trace(origin,axes[axis],2);if(h.hit&&h.transmission<=0&&dot3(h.geometryNormal,axes[axis])>.5f&&h.distance<nearest){nearest=h.distance;escape=mul(axes[axis],h.distance+.05f);}}origin=add(origin,escape);a[6]=make_float4(escape.x,escape.y,escape.z,0);}
  float3 sh[4]={v(0,0,0),v(0,0,0),v(0,0,0),v(0,0,0)};float mean=0,second=0,luminance=0,luminanceSecond=0;
  for(int r=0;r<rays;r++){float z=1-2*random(seed),t=6.2831853f*random(seed),rad=sqrtf(fmaxf(0,1-z*z));float3 dir=v(rad*cosf(t),z,rad*sinf(t));RtPayload h=trace(origin,dir);float distance=h.hit?h.distance:128.f;mean+=distance/rays;second+=distance*distance/rays;
   float3 light=incoming(origin,dir,seed,3,true);float l=dot3(light,v(.2126f,.7152f,.0722f));luminance+=l/rays;luminanceSecond+=l*l/rays;sh[0]=add(sh[0],mul(light,1.f/rays));sh[1]=add(sh[1],mul(light,2*dir.x/rays));sh[2]=add(sh[2],mul(light,2*dir.y/rays));sh[3]=add(sh[3],mul(light,2*dir.z/rays));
  }float alpha=same?.15f:1;a[0]=make_float4(p.x,p.y,p.z,1);for(int k=0;k<4;k++){float3 old=v(a[k+1].x,a[k+1].y,a[k+1].z),value=add(mul(old,1-alpha),mul(sh[k],alpha));a[k+1]=make_float4(value.x,value.y,value.z,0);}a[5]=make_float4(mean,second,params.frame,same?a[5].w+1:1);a[7]=make_float4(luminance,luminanceSecond,fmaxf(0,luminanceSecond-luminance*luminance),same?a[7].w:a[7].w+1);return;
 }
