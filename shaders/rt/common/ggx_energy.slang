#pragma once
#include "math.slang"
#ifdef RT_GGX_ASSETS
float ggxEnergyCell(uint index){return asfloat(assets.Load(assets.Load(248)+index*4));}
#else
#include "ggx_energy_data.slang"
float ggxEnergyCell(uint index){return GgxEnergy[index];}
#endif

float ggxEnergy(float alpha,float cosine){float a=clamp(alpha,0,1)*31;float c=clamp(cosine,0,1)*31;int ax=(int)a;int cx=(int)c;int ay=ax<31?ax+1:ax;int cy=cx<31?cx+1:cx;float x=a-ax;float y=c-cx;return (ggxEnergyCell(ax*32+cx)*(1-x)+ggxEnergyCell(ay*32+cx)*x)*(1-y)+(ggxEnergyCell(ax*32+cy)*(1-x)+ggxEnergyCell(ay*32+cy)*x)*y;}
float ggxAverageEnergy(float alpha){float sum=0;for(int i=0;i<16;i++){float c=(i+.5f)/16;sum+=ggxEnergy(alpha,c)*2*c/16;}return sum;}
