#include "../common/medium.slang"
#include "../common/surface.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
Material fixture(uint kind,float alpha) {
    Material m=defaultMaterial();m.type=kind;m.microfacetAlpha=alpha;m.alphaV=alpha;
    m.baseColor=float3(.7,.5,.3);m.transmission=.8;m.sigmaA=float3(.16,.06,.035);m.sigmaS=float3(.01,.02,.03);
    if(kind==CONDUCTOR || kind==COATED_CONDUCTOR) setConductor(m,231);
    if(kind==COATED_DIFFUSE || kind==COATED_CONDUCTOR) {m.type=kind;m.coatWeight=.6;}
    if(kind==WATER)m.ior=1.333;
    if(kind==10) {setConductor(m,234);}
    if(kind==11) {setConductor(m,255);}
    if(kind>=12) {setConductor(m,230+kind-12);}
    return m;
}
[shader("compute")] [numthreads(1,1,1)]
void transport_parity(uint3 tid:SV_DispatchThreadID) {
    uint index=tid.x;
    uint kind=index%20;uint rough=(index/20)%9;uint angle=(index/180)%5;
    float alpha=max(.0005,rough/8.0);float cosine=.1+angle*.2;
    Material m=fixture(kind,alpha);Frame f=makeFrame(float3(0,0,1));
    float3 wo=float3(sqrt(1-cosine*cosine),0,cosine);float3 wi=normalize(float3(.3,.2,1));
    uint seed=index*9781+1;
    BsdfEval eval=evalBsdf(m,f,wo,wi,m.ior);
    BsdfSample sample=sampleBsdf(m,f,wo,seed,m.ior);
    result[index*14]=float4(eval.f,eval.pdf);
    result[index*14+1]=float4(sample.wi,sample.pdf);
    result[index*14+2]=float4(sample.weight,sample.eta);
    result[index*14+3]=float4(conductorFresnel(m,cosine),fresnelDielectric(cosine,m.ior));
    MediumDistanceSample distance=sampleMediumDistance(m.sigmaA+m.sigmaS,4,seed);
    result[index*14+4]=float4(distance.distance,distance.pdf,hg(cosine,.35),float(sample.flags));
    result[index*14+5]=float4(sampleHg(float3(0,0,1),.35,seed),ggxEnergy(alpha,cosine));
    float3 p=float3(index%100, -.0000001, -12345.5);
    result[index*14+6]=float4(offsetRayOrigin(p,float3(0,1,0),wo),shadingNormalCorrection(float3(0,0,1),f.n,wo,wi,true));
    MediumStack stack;stack.count=0;
    Medium outer={float3(.1),float3(.2),.35,1.333,11};Medium inner={float3(.3),float3(.4),0,1.5,12};
    bool a=stack.enter(outer);bool b=stack.enter(inner);float outside=stack.outside(12);bool c=stack.exit(11);
    result[index*14+7]=float4(stack.ior(),outside,float(stack.count),a&&b&&c?1:0);
    uint packed=(rough*31)|((kind>=10?255:kind==CONDUCTOR?231:10)<<8)|((angle*40)<<16);
    uint4 palette=uint4(kind%10 | (128<<8) | (96<<16) | (32u<<24),0x90a02010u,0x80204060u,0);
    Material decoded=decodeMaterial(index&65535,packed,float3(.7,.5,.3),index%2==0?16:0,palette,0x00004080u,true);
    result[index*14+8]=float4(decoded.microfacetAlpha,decoded.alphaV,decoded.ior,float(decoded.type));
    result[index*14+9]=float4(decoded.sigmaA,decoded.transmission);

    result[index*14+10]=float4(cutoutVisible(127,255,0)?1:0,cutoutVisible(128,255,0)?1:0,cutoutVisible(25,255,16)?1:0,cutoutVisible(26,255,16)?1:0);
    MediumDistanceSample tiny=sampleMediumDistance(float3(.0001),.0001,seed);
    result[index*14+11]=float4(tiny.distance,tiny.pdf,safeNormalize(float3(0)).x,float(seed>>8));

    BsdfEval glossy=evalGlossy(m,f,wo,wi);BsdfSample glossySample=sampleGlossy(m,f,wo,seed);
    result[index*14+12]=float4(glossy.f,glossy.pdf);
    result[index*14+13]=float4(glossySample.weight,glossySample.pdf);

}
