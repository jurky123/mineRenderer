#define RT_CPU_FIXTURE 1
#define RT_POLICY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void cache_lobes(uint3 tid:SV_DispatchThreadID){
    uint i=tid.x;Material m=defaultMaterial();m.type=ROUGH_DIFFUSE;m.baseColor=float3(.7,.5,.3);m.microfacetAlpha=.15+.85*(i%4)/3;m.alphaV=.2+.8*(i%3)/2;m.f0=(i%2)==0?.04:.16;
    float cosine=.02+.96*((i/4)%5)/4;Frame frame=makeFrame(float3(0,0,1));float3 wo=float3(sqrt(1-cosine*cosine),0,cosine),wi=normalize(float3(.3,.2,1));
    BsdfEval full=evalBsdf(m,frame,wo,wi),d=evalDiffuseLobe(m,frame,wo,wi),s=evalSpecularLobe(m,frame,wo,wi);
    output[i*7]=float4(d.f+s.f,lerp(d.pdf,s.pdf,specProbability(m)));
    uint seed=i*9781+1;BsdfSample sample=sampleGlossy(m,frame,wo,seed);BsdfEval sampled=evalSpecularLobe(m,frame,wo,sample.wi);
    output[i*7+1]=float4(sample.weight,sample.pdf);output[i*7+2]=float4(sampled.f*(max(0,dot(frame.n,sample.wi))/max(1e-20,sampled.pdf)),sampled.pdf);
    // Analytic incident radiance L=2 + .25*x + .5*z on the hemisphere.
    uint address=rtCacheBase()+i*10;float3 M0=float3(4.5*Pi),Mx=float3(Pi/6),My=0,Mz=float3(7*Pi/3);
    pageFeedback[address+4]=asuint(float4(M0*.2820947918,0));pageFeedback[address+5]=asuint(float4(Mx*.4886025119,0));pageFeedback[address+6]=0;pageFeedback[address+7]=asuint(float4(Mz*.4886025119,0));
    output[i*7+3]=float4(rtDiffuseResponse(address,m,frame,wo),0);
    float sum=0,sum2=0;uint alive=0;seed=i*5731+9;
    for(uint j=0;j<32768;j++){BsdfSample cached=sampleCachedSpecular(m,frame,wo,seed);float value=cached.weight.x;sum+=value;sum2+=value*value;if(cached.pdf>0)alive++;}
    output[i*7+5]=float4(sum/32768,sum2/32768,alive/32768.0,specProbability(m));
    BsdfSample roulette=sampleCachedSpecular(m,frame,wo,seed);BsdfEval conditional=evalSpecularLobe(m,frame,wo,roulette.wi);
    float3 grazingOut=normalize(float3(1,0,1e-8)),grazingIn=normalize(float3(.7,.7,i%2==0?1e-8:1e-6));
    BsdfEval grazingFull=evalBsdf(m,frame,grazingOut,grazingIn),grazingD=evalDiffuseLobe(m,frame,grazingOut,grazingIn),grazingS=evalSpecularLobe(m,frame,grazingOut,grazingIn);
    output[i*7+6]=float4(roulette.pdf>0?maxComponent(abs(roulette.weight*roulette.pdf-conditional.f*max(0,dot(frame.n,roulette.wi)))):0,maxComponent(abs(grazingD.f+grazingS.f-grazingFull.f)),grazingFull.pdf,grazingS.pdf*specProbability(m)+grazingD.pdf*(1-specProbability(m)));
    float neePdf=.37,specWeight=powerHeuristic(s.pdf*specProbability(m),neePdf);
    output[i*7+4]=float4(cacheDirectBsdf(m,frame,wo,wi,neePdf,false)+s.f*specWeight,maxComponent(abs(cacheDirectBsdf(m,frame,wo,wi,neePdf,true)-full.f)));
}
