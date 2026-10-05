#pragma once
#include "math.slang"
bool cutoutVisible(uint textureAlpha,uint tintAlpha,uint flags) {
    uint product=textureAlpha*tintAlpha;return (flags&16)!=0?product*10>=65025:product*2>=65025;
}
float offsetComponent(float p,float n) {
    if(n==0)return p;
    float step=max(1e-6,abs(p)*2.384185791015625e-7);
    float q=p+n*step;
    if(q==0)return asfloat(n>0?1u:0x80000001u);
    uint bits=asuint(q);return asfloat((q>0)==(n>0)?bits+1:bits-1);
}
float3 offsetRayOrigin(float3 p,float3 geometricNormal,float3 direction) {
    float3 n=dot(geometricNormal,direction)>=0?geometricNormal:-geometricNormal;
    return float3(offsetComponent(p.x,n.x),offsetComponent(p.y,n.y),offsetComponent(p.z,n.z));
}
bool validShadingHemisphere(float3 ng,float3 ns,float3 wo,float3 wi) {
    return dot(ng,wo)*dot(ng,wi)*dot(ns,wo)*dot(ns,wi)>0;
}
float shadingNormalCorrection(float3 ng,float3 ns,float3 wo,float3 wi,bool importance) {
    if(!validShadingHemisphere(ng,ns,wo,wi))return 0;
    if(!importance)return 1;
    float denominator=abs(dot(ng,wo)*dot(ns,wi));return denominator>1e-8?abs(dot(ns,wo)*dot(ng,wi))/denominator:0;
}
