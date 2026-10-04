#pragma once
#include <optix.h>
#include "bsdf.h"
#ifndef __CUDACC__
struct float3 {float x,y,z;};
struct alignas(16) float4 {float x,y,z,w;};
inline float3 make_float3(float x,float y,float z){return {x,y,z};}
#endif
struct RtVertex {float3 p;struct {float x,y;} uv;float3 n;unsigned tint,flags;};
struct RtCube {float3 minimum,maximum;};
struct RtHitData {RtVertex* vertices;int textureSlot;};
struct RtAreaLight {RtVertex a,b,c;float area,cdf;unsigned identity;};
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
 float time,waveStrength,cloudWind,cloudAltitude,rain,cloudShadows,rainRipples;unsigned options,debug;float underwater;float4 pointPosition,pointIntensity;rt::Medium cameraWater;
 RtAreaLight* lights;int lightCount;float lightPower;
 float4 *surfaceKey,*previousKey,*referenceSum,*previousSignal;unsigned referenceSpp,referenceSamples;float fireflyClamp;
 float4* caustics;float4* causticHistory;unsigned* counters;unsigned* counterTotals;
};
struct RtPayload {rt::Material bsdf;unsigned objectId,primitiveId;float3 p,n,geometryNormal,color,emission,absorption;float roughness,f0,ior,transmission;unsigned metal,flags;float distance;int hit;};

static_assert(sizeof(RtVertex)==40,"RT vertex ABI");

static_assert(sizeof(float3)==12 && alignof(float4)==16,"CUDA vector ABI");
static_assert(sizeof(RtHitData)==16 && sizeof(RtCube)==24,"RTX host/device ABI");

static_assert(sizeof(RtParams)==560 && sizeof(RtPayload)==252 && sizeof(RtAreaLight)==132 && sizeof(rt::Material)==140,"Material 3 host/device ABI");
