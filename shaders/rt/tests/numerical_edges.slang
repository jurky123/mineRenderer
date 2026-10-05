#include "../common/bsdf.slang"
#include "accumulation_fixture.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void numerical_edges(uint3 tid:SV_DispatchThreadID){
    float nan=asfloat(0x7fc00000u),inf=asfloat(0x7f800000u);
    result[0]=float4(fresnelDielectric(0,1),fresnelDielectric(0,1.5),powerHeuristic(1e30,1e30),powerHeuristic(inf,inf));
    Material m=(Material)0;m.type=WATER;m.transmission=.8;m.microfacetAlpha=.1;m.alphaV=.1;
    uint seed=1;BsdfSample sample=sampleBsdf(m,makeFrame(float3(0,0,1)),float3(0,0,1),seed,1);
    result[1]=float4(sample.weight,sample.pdf);result[2]=float4(sample.wi,sample.eta);
    result[3]=accumulateSample(float4(nan,0,0,1),float4(.2,.3,.4,12),64);
    result[4]=accumulateSample(float4(100,100,100,0),float4(.2,.3,.4,12),64);
    result[5]=accumulateSample(float4(.8,.6,.4,1),float4(nan,inf,nan,nan),64);
    result[6]=accumulateSample(float4(.8,.6,.4,1),float4(.2,.3,.4,64),64);
    result[7]=accumulateSample(float4(1e30,1e30,1e30,1),float4(1e30,1e30,1e30,64),64);
}
