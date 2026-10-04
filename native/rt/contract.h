#pragma once
#include <optix.h>
#ifndef __CUDACC__
struct float3 {float x,y,z;};
struct alignas(16) float4 {float x,y,z,w;};
inline float3 make_float3(float x,float y,float z){return {x,y,z};}
#endif
struct RtVertex {float3 p;struct {float x,y;} uv;float3 n;unsigned tint,flags;};
struct RtCube {float3 minimum,maximum;};
struct RtHitData {RtVertex* vertices;int textureSlot;};
struct RtParams {
 OptixTraversableHandle scene;
 float4 *position,*normal,*albedo,*material,*diffuse,*specular,*transmission,*sunVisibility;
 unsigned *atlas,*ids,*lut,*normalMap,*entityAtlas;
 float4 *previousPosition,*previousNormal;
 float* flow;float* flowTrust;float previousClip[16];float3 previousCamera;
 float4* probes; // 8 float4/probe: world tag, four RGB L1 coefficients, distance/moments/age, reserve.
 int width,height,atlasWidth,atlasHeight,idsWidth,idsHeight,frame,mode;
 float3 camera,sun,sunColor,sky;
 float3 invalidateMin,invalidateMax;
 float time,waveStrength,cloudWind,cloudAltitude,rain,cloudShadows,rainRipples;unsigned options,debug;
};
struct RtPayload {float3 p,n,geometryNormal,color,emission,absorption;float roughness,f0,ior,transmission;unsigned metal,flags;float distance;int hit;};

static_assert(sizeof(RtVertex)==40,"RT vertex ABI");

static_assert(sizeof(float3)==12 && alignof(float4)==16,"CUDA vector ABI");
static_assert(sizeof(RtParams)==376 && sizeof(RtPayload)==104 && sizeof(RtHitData)==16 && sizeof(RtCube)==24,"RTX host/device ABI");
