#pragma once
// Ported from native/rt/bsdf.h; preserve Material 3 transport semantics.
#include "bsdf.slang"
float hg(float cosine,float g){float d=1+g*g-2*g*cosine;return (1-g*g)/(4*Pi*d*sqrt(d));}
// Truncated exponential proposal for the finite homogeneous segment. RGB extinction
// remains in the integrand; the scalar majorant only chooses the sampling distribution.
struct MediumDistanceSample {float distance,pdf;};
MediumDistanceSample sampleMediumDistance(float3 sigmaT,float length,inout uint seed){
 float majorant=maxComponent(sigmaT);float mass=-expm1(-majorant*length);
 if(majorant<1e-6f||mass<1e-6f)return {rng(seed)*length,1/max(length,1e-8f)};
 float distance=-log1p(-rng(seed)*mass)/majorant;
 return {distance,majorant*exp(-majorant*distance)/mass};
}
float3 sampleHg(float3 axis,float g,inout uint seed){float u=rng(seed);float cosine;if(abs(g)<1e-3f)cosine=1-2*u;else{float ratio=(1-g*g)/(1-g+2*g*u);cosine=clamp((1+g*g-ratio*ratio)/(2*g),-1,1);}float phi=2*Pi*rng(seed);float r=sqrt(max(0,1-cosine*cosine));return makeFrame(axis).world(float3(r*cos(phi),r*sin(phi),cosine));}
struct Medium {float3 sigmaA,sigmaS;float g,ior;uint id;};
struct MediumStack {Medium entries[8];int count=0;
 float ior(){return count!=0?entries[count-1].ior:1;}
 int find(uint id){for(int i=count-1;i>=0;i--)if(entries[i].id==id)return i;return -1;}
 float outside(uint id){int i=find(id);return i>0?entries[i-1].ior:1;}
 [mutating] bool enter(Medium m){if(count>=8)return false;entries[count++]=m;return true;}
 [mutating] bool exit(uint id){int i=find(id);if(i<0)return false;for(int j=i;j<count-1;j++)entries[j]=entries[j+1];count--;return true;}
};