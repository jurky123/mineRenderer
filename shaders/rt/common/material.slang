#pragma once
// Ported from native/rt/bsdf.h; preserve Material 3 transport semantics.
#include "math.slang"
static const uint DIFFUSE=0;
static const uint ROUGH_DIFFUSE=1;
static const uint CONDUCTOR=2;
static const uint DIELECTRIC=3;
static const uint THIN_DIELECTRIC=4;
static const uint COATED_DIFFUSE=5;
static const uint COATED_CONDUCTOR=6;
static const uint DIFFUSE_TRANSMISSION=7;
static const uint EMISSIVE=8;
static const uint WATER=9;
static const uint DIFFUSE_LOBE=1;
static const uint GLOSSY=2;
static const uint SPECULAR=4;
static const uint REFLECTION=8;
static const uint TRANSMISSION=16;
static const uint DELTA=32;
static const uint DISPERSIVE=64;
struct Material {
 float3 baseColor;float3 eta;float3 k;float3 emission;float3 sigmaA;float3 sigmaS;
 float microfacetAlpha;float alphaV;float f0;float ior;float transmission;float coatWeight;float coatAlpha;float coatIOR;float porosity;float sss;float phaseG;float thickness;float abbeNumber;bool multipleScattering;
 uint type;uint conductorId;uint materialId;uint mediumId;
};
Material defaultMaterial() { Material m;
    m.baseColor=float3(.5f,.5f,.5f);
    m.eta=float3(1.5f,1.5f,1.5f);
    m.k=float3(0,0,0);
    m.emission=float3(0,0,0);
    m.sigmaA=float3(0,0,0);
    m.sigmaS=float3(0,0,0);
    m.microfacetAlpha=.5f;
    m.alphaV=.5f;
    m.f0=.04f;
    m.ior=1.5f;
    m.transmission=0;
    m.coatWeight=0;
    m.coatAlpha=.04f;
    m.coatIOR=1.5f;
    m.porosity=0;
    m.sss=0;
    m.phaseG=0;
    m.thickness=.0625f;
    m.abbeNumber=0;
    m.multipleScattering=true;
    m.type=uint(ROUGH_DIFFUSE);
    m.conductorId=0;
    m.materialId=0;
    m.mediumId=0;
    return m;
}
void setConductor(inout Material m,uint id){
 // LabPBR predefined 230..237: iron, gold, aluminium, chrome, copper, lead, platinum, silver.
 const float3 eta[8]={float3(2.9114f,2.9497f,2.5845f),float3(.18299f,.42108f,1.3734f),float3(1.3456f,.96521f,.61722f),float3(3.1071f,3.1812f,2.323f),float3(.27105f,.67693f,1.3164f),float3(1.91f,1.83f,1.44f),float3(2.3757f,2.0847f,1.8453f),float3(.15943f,.14512f,.13547f)};
 const float3 k[8]={float3(3.0893f,2.9318f,2.767f),float3(3.4242f,2.3459f,1.7704f),float3(7.4746f,6.3995f,5.3031f),float3(3.3314f,3.3291f,3.135f),float3(3.6092f,2.6248f,2.2921f),float3(3.51f,3.4f,3.18f),float3(4.2655f,3.7153f,3.1365f),float3(3.9291f,3.19f,2.3808f)};
 m.conductorId=id;if(id>=230&&id<=237){m.eta=eta[id-230];m.k=k[id-230];}m.type=m.coatWeight>0?COATED_CONDUCTOR:CONDUCTOR;
}
// The same five-plane palette uploaded by PbrAtlas. No reinterpretation of LabPBR channels.
Material decodeMaterial(uint id,uint packed,float3 color,uint flags,uint4 palette,uint optical,bool multipleScattering) {
    Material m=defaultMaterial();m.multipleScattering=multipleScattering;m.baseColor=color;
    float roughness=1-(packed&255)/255.0;m.microfacetAlpha=roughness*roughness;m.alphaV=m.microfacetAlpha;
    uint green=(packed>>8)&255;uint blue=(packed>>16)&255;
    m.f0=green<230?green/255.0:.04;m.porosity=blue<=64?blue/64.0:0;m.sss=blue>=65?(blue-65)/190.0:0;
    uint a=palette.x;uint b=palette.y;uint c=palette.z;uint d=optical;
    m.type=a&255;m.ior=max(1,((a>>8)&255)*3/255.0);m.coatWeight=((a>>16)&255)/255.0;m.coatAlpha=((a>>24)&255)/255.0;
    m.coatIOR=max(1,(d&255)*3/255.0);m.alphaV=max(.0005,((d>>8)&255)/255.0);
    m.sigmaA=float3(b&255,(b>>8)&255,(b>>16)&255)*8/255.0;m.phaseG=((b>>24)&255)*1.8/255.0-.9;
    m.sigmaS=float3(c&255,(c>>8)&255,(c>>16)&255)/255.0;m.transmission=((c>>24)&255)/255.0;
    if(green>=230)setConductor(m,green<=237?green:255);
    if((flags&16)!=0){m.type=ROUGH_DIFFUSE;m.coatWeight=0;m.f0=.04;m.ior=1.5;m.transmission=0;m.alphaV=m.microfacetAlpha;}
    bool isMetal=m.type==CONDUCTOR||m.type==COATED_CONDUCTOR;
    bool isDielectric=m.type==DIELECTRIC||m.type==THIN_DIELECTRIC||m.type==WATER;
    if(m.sss>0&&!isMetal&&!isDielectric){m.type=DIFFUSE_TRANSMISSION;m.coatWeight=0;m.transmission=m.sss*.5;}
    m.materialId=id&65535;m.mediumId=m.materialId+1;return m;
}
