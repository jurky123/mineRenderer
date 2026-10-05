#pragma once
#include "math.slang"
// Same three wave components, micro-noise and rain rings as native/rt/device.cuh.
float waterHash(float x,float y){x-=floor(x/128)*128;y-=floor(y/128)*128;return frac(sin(x*127.1+y*311.7)*43758.5453);}
float waterNoise(float x,float y){float ix=floor(x),iy=floor(y),fx=x-ix,fy=y-iy;fx=fx*fx*(3-2*fx);fy=fy*fy*(3-2*fy);return lerp(lerp(waterHash(ix,iy),waterHash(ix+1,iy),fx),lerp(waterHash(ix,iy+1),waterHash(ix+1,iy+1),fx),fy);}
float3 terrainWaterNormal(float3 normal,float3 point,float3 cameraOrigin,float time,float waveStrength,float wind,float rain,float ripples) {
    if(abs(normal.y)<.8)return normal;
    float x=fmod(point.x,16384),z=fmod(point.z,16384),sx=0,sz=0;
    const float kx[3]={1.7,.64,-3.14},kz[3]={.51,2.38,1.07},weights[3]={.45,.32,.23};
    for(int i=0;i<3;i++) {
        float length=sqrt(kx[i]*kx[i]+kz[i]*kz[i]);float angle=x*kx[i]+z*kz[i]-sqrt(9.81*length)*time+i*1.73;
        float amplitude=cos(angle)*weights[i]/length;sx+=kx[i]*amplitude;sz+=kz[i]*amplitude;
    }
    float qx=x*6.7+time*.31,qz=z*6.7-time*.19,e=.13;
    sx+=.28*(waterNoise(qx+e,qz)-waterNoise(qx-e,qz))/(2*e);sz+=.28*(waterNoise(qx,qz+e)-waterNoise(qx,qz-e))/(2*e);
    float rx=x*1.3,rz=z*1.3,cx=floor(rx),cz=floor(rz),lx=rx-cx-.5,lz=rz-cz-.5,radius=sqrt(lx*lx+lz*lz);
    float phase=frac(wind*4+waterHash(cx,cz)),ring=radius-phase*.65;
    float amplitude=exp(-ring*ring*220)*(1-phase)*.045*rain*ripples,microFade=max(0,1-length(point-cameraOrigin)/96);
    sx=sx*waveStrength+(radius>.001?lx/radius*amplitude:0)*microFade;sz=sz*waveStrength+(radius>.001?lz/radius*amplitude:0)*microFade;
    return safeNormalize(normal+float3(sx,0,sz));
}
