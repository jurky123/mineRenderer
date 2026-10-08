#define RT_CPU_FIXTURE 1
#define RT_KERNEL_ASSET_FIXTURE 1
#define RT_POLICY_FIXTURE 1
#include "../common/material_transport.slang"
SurfacePayload fixtureTrace(float3 origin,float3 direction,float maximum){return (SurfacePayload)0;}
[shader("compute")] [numthreads(1,1,1)]
void diffuse_kernel(uint3 tid:SV_DispatchThreadID){
    uint i=tid.x;Material m=defaultMaterial();m.type=ROUGH_DIFFUSE;m.baseColor=float3(.7,.5,.3);
    float root=.1+.4*(i%8)/7;m.f0=root*root;m.microfacetAlpha=.15+.85*((i/64)%4)/3;
    float cosine=.001+.998*((i/8)%8)/7;Frame frame=makeFrame(float3(0,0,1));float3 wo=float3(sqrt(1-cosine*cosine),0,cosine);
    float a=2,bn=-.9,bx=(i%2==0?.8:-.8),by=.3;uint address=rtCacheBase()+i*10;
    float3 M0=float3(2*Pi*a+Pi*bn),Mx=float3(2*Pi/3*bx),My=float3(2*Pi/3*by),Mz=float3(Pi*a+2*Pi/3*bn);
    pageFeedback[address+4]=asuint(float4(M0*.2820947918,0));pageFeedback[address+5]=asuint(float4(Mx*.4886025119,0));pageFeedback[address+6]=asuint(float4(My*.4886025119,0));pageFeedback[address+7]=asuint(float4(Mz*.4886025119,0));
    output[i*3]=float4(rtDiffuseResponse(address,m,frame,wo),0);
    output[i*3+2]=float4(rtDiffuseResponseQuadrature(address,m,frame,wo),0);
    // A clipped hemisphere must preserve the old nonlinear quadrature exactly.
    a=.1;M0=float3(2*Pi*a+Pi*bn);Mz=float3(Pi*a+2*Pi/3*bn);
    pageFeedback[address+4]=asuint(float4(M0*.2820947918,0));pageFeedback[address+7]=asuint(float4(Mz*.4886025119,0));
    output[i*3+1]=float4(rtDiffuseResponse(address,m,frame,wo)-rtDiffuseResponseQuadrature(address,m,frame,wo),0);
}
