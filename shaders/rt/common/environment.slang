#pragma once
#include "bsdf.slang"
#include "medium.slang"
// assets binding 6: RGBA32F map, per-cell prefix/mass/row total/solid angle,
// and per-row prefix/mass/global total. Header stores their byte offsets.
uint2 environmentSize(){return assets.Load2(84);}
float4 environmentCellData(uint2 pixel){uint2 size=environmentSize();return asfloat(assets.Load4(assets.Load(28)+(pixel.y*size.x+pixel.x)*16));}
float4 environmentRowData(uint row){return asfloat(assets.Load4(assets.Load(44)+row*16));}
uint2 environmentCell(float3 direction) {
    uint2 size=environmentSize();float phi=atan2(direction.z,direction.x);if(phi<0)phi+=2*Pi;
    return min(uint2((uint)(phi*size.x/(2*Pi)),(uint)(acos(clamp(direction.y,-1,1))*size.y/Pi)),size-1);
}
float environmentSolidAngle(uint row){uint2 size=environmentSize();return 2*Pi/size.x*(cos(Pi*row/size.y)-cos(Pi*(row+1)/size.y));}
float environmentPower(){return environmentRowData(environmentSize().y-1).z;}
float3 environmentRadiance(float3 direction){uint2 size=environmentSize(),pixel=environmentCell(direction);return asfloat(assets.Load3(assets.Load(12)+(pixel.y*size.x+pixel.x)*16));}
float environmentDensity(float3 direction) {
    float total=environmentPower();if(total<=0)return 1/(4*Pi);
    float4 cell=environmentCellData(environmentCell(direction));return cell.y/(total*cell.w);
}
float3 sampleEnvironment(inout uint seed) {
    uint2 size=environmentSize();float total=environmentPower();
    if(total<=0){float y=1-2*rng(seed),phi=2*Pi*rng(seed),r=sqrt(max(0,1-y*y));return float3(r*cos(phi),y,r*sin(phi));}
    float value=rng(seed)*total;uint lo=0,hi=size.y-1;
    while(lo<hi){uint mid=(lo+hi)/2;if(environmentRowData(mid).x<=value)lo=mid+1;else hi=mid;}uint row=lo;
    value=rng(seed)*environmentRowData(row).y;lo=0;hi=size.x-1;
    while(lo<hi){uint mid=(lo+hi)/2;if(environmentCellData(uint2(mid,row)).x<=value)lo=mid+1;else hi=mid;}uint column=lo;
    float y=lerp(cos(Pi*row/size.y),cos(Pi*(row+1)/size.y),rng(seed));float phi=2*Pi*(column+rng(seed))/size.x,r=sqrt(max(0,1-y*y));return float3(r*cos(phi),y,r*sin(phi));
}
float luminance(float3 rgb){return max(0,dot(rgb,float3(.2126,.7152,.0722)));}
float4 directionalLight(){return asfloat(assets.Load4(96));}
float3 directionalIrradiance(){return asfloat(assets.Load3(112));}
float3 lightProbabilities() {
    float sun=luminance(directionalIrradiance()),env=max(.001,environmentPower());
    // Held delta light has its own deterministic connection; sky power cannot starve it.
    return float3(sun,env,0)/(sun+env);
}
float3 sampleCone(float3 axis,float omega,inout uint seed) {
    float c=1-rng(seed)*omega/(2*Pi),r=sqrt(max(0,1-c*c)),a=2*Pi*rng(seed);Frame frame=makeFrame(axis);return frame.world(float3(r*cos(a),r*sin(a),c));
}
struct LightSample {float3 wi,radiance;float distance,pdf;uint kind;};
LightSample sampleOneLight(float3 point,inout uint seed) {
    float3 probabilities=lightProbabilities();float choice=rng(seed);LightSample light=(LightSample)0;light.distance=4096;
    if(choice<probabilities.x) {
        float4 sun=directionalLight();light.kind=0;light.wi=sampleCone(sun.xyz,sun.w,seed);light.pdf=probabilities.x/sun.w;light.radiance=directionalIrradiance()/sun.w;
    } else {
        light.kind=1;light.wi=sampleEnvironment(seed);light.pdf=probabilities.y*environmentDensity(light.wi);light.radiance=environmentRadiance(light.wi);
    }

    return light;
}
LightSample sampleHeldLight(float3 point) {
    LightSample light=(LightSample)0;light.kind=3;
    if(asfloat(assets.Load(140))<=0)return light;
    float3 delta=asfloat(assets.Load3(128))-point;light.distance=length(delta);
    if(light.distance<.025)return light;
    light.wi=delta/light.distance;light.pdf=1;
    light.radiance=asfloat(assets.Load3(144))/(light.distance*light.distance);return light;
}
float3 missRadiance(float3 direction,float previousPdf,bool previousDelta) {
    float3 probabilities=lightProbabilities();float envPdf=probabilities.y*environmentDensity(direction);
    float3 value=environmentRadiance(direction)*(previousDelta?1:powerHeuristic(previousPdf,envPdf));
    float4 sun=directionalLight();
    if(probabilities.x>0&&dot(direction,sun.xyz)>1-sun.w/(2*Pi))value+=directionalIrradiance()/sun.w*(previousDelta?1:powerHeuristic(previousPdf,probabilities.x/sun.w));
    return value;
}

float lightMisWeight(LightSample light,float bsdfPdf,bool continuation) {
    // At the depth limit there is no BSDF technique to pair with this connection.
    return light.kind==3||!continuation?1:powerHeuristic(light.pdf,bsdfPdf);
}
MediumStack cameraMedia() {
    MediumStack media;media.count=0;
    if(assets.Load(92)!=0) {
        float4 absorption=asfloat(assets.Load4(160)),scattering=asfloat(assets.Load4(176));
        Medium water={absorption.xyz,scattering.xyz,scattering.w,absorption.w,0xfffffffeu};media.enter(water);
    }
    return media;
}
