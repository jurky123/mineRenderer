#include "reconstruction_fixture_functions.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void reconstruction_fixture(uint3 tid:SV_DispatchThreadID){
    float4 p=float4(0,0,10,10),n=float4(0,0,-1,.5),a=float4(.2,.3,.4,1);
    result[0]=float4(rtFlow(float2(4,2),float2(1,4)/float2(10,10),float2(10,10)),0,0);
    result[1]=float4(rtHistoryMatch(p,n,a,p,n,a,120),rtHistoryMatch(p,n,a,p+float4(.1,0,0,0),n,a,120),rtHistoryMatch(p,n,a,p+float4(.3,0,0,0),n,a,120),rtHistoryMatch(p,n,a,p+float4(0,0,.1,0),n,a,120));
    result[2]=float4(rtHistoryMatch(p,n,float4(a.xyz,-1),p,n,float4(a.xyz,-1),120),rtHistoryMatch(p,n,a,p,float4(0,0,1,.5),a,120),rtHistoryMatch(p,n,a,p,n,float4(a.xyz,2),120),rtHistoryMatch(p,n,a,p,n,float4(.8,.8,.8,1),120));
    float4 sky=float4(0,0,1,0);result[3]=float4(rtHistoryMatch(sky,n,a,sky,n,a,120),rtHistoryMatch(sky,n,a,float4(1,0,0,0),n,a,120),rtHistoryMatch(sky,n,a,p,n,a,120),rtHistoryMatch(p,n,a,sky,n,a,120));
result[4]=float4(rtIdentityMatch(21,21),rtIdentityMatch(-21,21),rtIdentityMatch(21,22),rtIdentityMatch(21,-21));
result[5]=float4(rtPreviousSurface(float3(0),float3(2,0,0),float3(0,4,0),float2(.25,.5),float3(10,20,30)),1);
}
