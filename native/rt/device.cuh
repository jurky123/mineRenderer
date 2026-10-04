#pragma once
#include <optix.h>
#include <optix_device.h>
#include "contract.h"
#include "surface.h"
extern "C" __constant__ RtParams params;
static __forceinline__ __device__ float3 v(float x,float y,float z){return make_float3(x,y,z);}
static __forceinline__ __device__ float3 add(float3 a,float3 b){return v(a.x+b.x,a.y+b.y,a.z+b.z);}
static __forceinline__ __device__ float3 mul(float3 a,float b){return v(a.x*b,a.y*b,a.z*b);}
static __forceinline__ __device__ float3 prod(float3 a,float3 b){return v(a.x*b.x,a.y*b.y,a.z*b.z);}
static __forceinline__ __device__ float dot3(float3 a,float3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
static __forceinline__ __device__ float3 norm(float3 a){return mul(a,rsqrtf(fmaxf(dot3(a,a),1.e-15f)));}
static __forceinline__ __device__ float3 cross3(float3 a,float3 b){return v(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);}
static __forceinline__ __device__ float linear(float x){return x<=.04045f?x/12.92f:powf((x+.055f)/1.055f,2.4f);}
static __forceinline__ __device__ float3 rgb(unsigned c){return v(linear((c&255)/255.f),linear(((c>>8)&255)/255.f),linear(((c>>16)&255)/255.f));}
static __forceinline__ __device__ float random(unsigned& state){state=state*1664525u+1013904223u;return (state>>8)*0x1p-24f;}
static __forceinline__ __device__ RtPayload* payload(){return reinterpret_cast<RtPayload*>((static_cast<unsigned long long>(optixGetPayload_1())<<32)|optixGetPayload_0());}
static __forceinline__ __device__ void countOperation(int kind){unsigned i=params.launchOffset+optixGetLaunchIndex().x;if(params.counters&&i<(unsigned)(params.width*params.height))atomicAdd(params.counters+i*9+kind,1u);}
static __forceinline__ __device__ RtPayload trace(float3 o,float3 d,float maximum=512.f){
 countOperation(0);RtPayload p={};unsigned long long address=reinterpret_cast<unsigned long long>(&p);unsigned low=(unsigned)address,high=(unsigned)(address>>32);
 optixTrace(params.scene,o,d,0.f,maximum,0,255,OPTIX_RAY_FLAG_NONE,0,1,0,low,high);return p;
}
static __forceinline__ __device__ float3 spawn(const RtPayload& hit,float3 direction){auto p=rt::offsetRayOrigin(rt::V(hit.p.x,hit.p.y,hit.p.z),rt::V(hit.geometryNormal.x,hit.geometryNormal.y,hit.geometryNormal.z),rt::V(direction.x,direction.y,direction.z));return v(p.x,p.y,p.z);}
static __forceinline__ __device__ unsigned sample(unsigned* map,int w,int h,float2 uv){int x=max(0,min(w-1,(int)(uv.x*w))),y=max(0,min(h-1,(int)(uv.y*h)));return map[y*w+x];}
static __forceinline__ __device__ unsigned hitTexture(const RtHitData* data,float2 uv){if(data->textureSlot<0)return sample(params.atlas,params.atlasWidth,params.atlasHeight,uv);int slot=data->textureSlot,x=(slot&7)*256+max(0,min(255,(int)(uv.x*256))),y=(slot>>3)*256+max(0,min(255,(int)(uv.y*256)));return params.entityAtlas[y*2048+x];}
static __forceinline__ __device__ float3 cosine(float3 n,unsigned& seed){float r=sqrtf(random(seed)),a=6.283185307f*random(seed);float3 t=norm(cross3(fabsf(n.y)<.9f?v(0,1,0):v(1,0,0),n)),b=cross3(n,t);return norm(add(add(mul(t,r*cosf(a)),mul(b,r*sinf(a))),mul(n,sqrtf(fmaxf(0,1-r*r)))));}
static __forceinline__ __device__ float hash2(float x,float y){x=x-floorf(x/128)*128;y=y-floorf(y/128)*128;float t=sinf(x*127.1f+y*311.7f)*43758.5453f;return t-floorf(t);}
static __forceinline__ __device__ float noise2(float x,float y){float ix=floorf(x),iy=floorf(y),fx=x-ix,fy=y-iy;fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);float a=hash2(ix,iy)*(1-fx)+hash2(ix+1,iy)*fx,b=hash2(ix,iy+1)*(1-fx)+hash2(ix+1,iy+1)*fx;return a*(1-fy)+b*fy;}
static __forceinline__ __device__ float3 waterNormal(float3 normal,float3 p){if(fabsf(normal.y)<.8f)return normal;float x=fmodf(p.x,16384.f),z=fmodf(p.z,16384.f),sx=0,sz=0;const float kx[3]={1.7f,.64f,-3.14f},kz[3]={.51f,2.38f,1.07f},weights[3]={.45f,.32f,.23f};
 for(int i=0;i<3;i++){float len=sqrtf(kx[i]*kx[i]+kz[i]*kz[i]),angle=x*kx[i]+z*kz[i]-sqrtf(9.81f*len)*params.time+i*1.73f,amplitude=cosf(angle)*weights[i]/len;sx+=kx[i]*amplitude;sz+=kz[i]*amplitude;}
 float qx=x*6.7f+params.time*.31f,qz=z*6.7f-params.time*.19f,e=.13f;sx+=.28f*(noise2(qx+e,qz)-noise2(qx-e,qz))/(2*e);sz+=.28f*(noise2(qx,qz+e)-noise2(qx,qz-e))/(2*e);float rx=x*1.3f,rz=z*1.3f,cx=floorf(rx),cz=floorf(rz),lx=rx-cx-.5f,lz=rz-cz-.5f,radius=sqrtf(lx*lx+lz*lz),phase=params.cloudWind*4+hash2(cx,cz);phase-=floorf(phase);float ring=radius-phase*.65f,amplitude=expf(-ring*ring*220)*(1-phase)*.045f*params.rain*params.rainRipples;
 float distance=sqrtf(dot3(add(p,mul(params.camera,-1)),add(p,mul(params.camera,-1)))),microFade=fmaxf(0,1-distance/96);
 sx=sx*params.waveStrength+(radius>.001f?lx/radius*amplitude:0)*microFade;sz=sz*params.waveStrength+(radius>.001f?lz/radius*amplitude:0)*microFade;return norm(add(normal,v(sx,0,sz)));
}
static __forceinline__ __device__ float smooth(float a,float b,float x){float t=fminf(1,fmaxf(0,(x-a)/(b-a)));return t*t*(3-2*t);}
static __forceinline__ __device__ float cloudDensity(float3 world){float x=fmodf(world.x,16384.f)+params.cloudWind,z=fmodf(world.z,16384.f)+params.cloudWind*.5f,altitude=world.y-params.cloudAltitude;if(altitude<0||altitude>32)return 0;float shape=0;
 for(int y=0;y<2;y++)for(int k=0;k<2;k++){float cx=floorf(x/8+(k==0?-.12f:.12f)),cz=floorf(z/8+(y==0?-.12f:.12f));float noise=noise2(cx*.09f,cz*.09f),occupied=noise>=.48f-params.rain*.14f?1:0,height=4*(3+floorf(noise*5));float face=fmaxf(fmaxf(fabsf(x-(cx+.5f)*8)-4,fabsf(z-(cz+.5f)*8)-4),altitude-height);shape=fmaxf(shape,occupied*(1-smooth(-.7f,.8f,face)));}
 shape*=smooth(0,3,altitude)*(1-smooth(25,32,altitude));float detail=noise2(x*.47f+world.y*.31f,z*.47f-world.y*.17f);return fmaxf(0,shape*(.65f+.35f*noise2(floorf(x/8)*.31f+floorf(world.y/4),floorf(z/8)*.31f+floorf(world.y/4)))-(1-detail)*.27f);
}
static __forceinline__ __device__ float cloudVisibility(float3 p){if(params.cloudShadows<.5f||params.sun.y<.08f||p.y>=params.cloudAltitude)return 1;float t=(params.cloudAltitude-p.y)/params.sun.y;float3 projected=add(p,mul(params.sun,t));float density=0;for(int i=0;i<4;i++)density+=cloudDensity(v(projected.x,params.cloudAltitude+4+i*8,projected.z));return expf(-density*(.4f+.25f*params.rain));}
#include "material.cuh"
