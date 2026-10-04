#pragma once
 #include "environment.h"
// Material lookup, sampling and path transport deliberately have separate responsibilities.
static __forceinline__ __device__ rt::Vec rv(float3 a){return rt::V(a.x,a.y,a.z);}
static __forceinline__ __device__ float3 cv(rt::Vec a){return v(a.x,a.y,a.z);}
static __forceinline__ __device__ rt::Material decodeMaterial(unsigned id,unsigned packed,float3 color,unsigned flags){
 rt::Material m;m.multipleScattering=(params.options&256)!=0;unsigned index=id&65535;
 m.baseColor=rv(color);float perceptualRoughness=1-(packed&255)/255.f;m.microfacetAlpha=perceptualRoughness*perceptualRoughness;m.alphaV=m.microfacetAlpha;
 unsigned green=(packed>>8)&255,blue=(packed>>16)&255;m.f0=green<230?green/255.f:.04f;m.porosity=blue<=64?blue/64.f:0;m.sss=blue>=65?(blue-65)/190.f:0;
 unsigned a=params.lut[index+65536],b=params.lut[index+2*65536],c=params.lut[index+3*65536],d=params.lut[index+4*65536];
 m.type=a&255;m.ior=fmaxf(1,((a>>8)&255)*3/255.f);m.coatWeight=((a>>16)&255)/255.f;m.coatAlpha=((a>>24)&255)/255.f;m.coatIOR=fmaxf(1,(d&255)*3/255.f);m.alphaV=fmaxf(.0005f,((d>>8)&255)/255.f);
 m.sigmaA=rt::V((b&255)*8/255.f,((b>>8)&255)*8/255.f,((b>>16)&255)*8/255.f);m.phaseG=((b>>24)&255)*1.8f/255.f-.9f;
 m.sigmaS=rt::V((c&255)/255.f,((c>>8)&255)/255.f,((c>>16)&255)/255.f);m.transmission=((c>>24)&255)/255.f;
 if(green>=230)rt::setConductor(m,green<=237?green:255);
 if(flags&16){m.type=rt::ROUGH_DIFFUSE;m.coatWeight=0;m.f0=.04f;m.ior=1.5f;m.transmission=0;m.alphaV=m.microfacetAlpha;}
 if(m.sss>0&&!rt::metal(m)&&!rt::dielectric(m)){m.type=rt::DIFFUSE_TRANSMISSION;m.coatWeight=0;m.transmission=m.sss*.5f;}

 m.materialId=index;m.mediumId=index+1;return m;
}
