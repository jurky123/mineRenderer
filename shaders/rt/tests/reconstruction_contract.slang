#include "../common/reconstruction_math.slang"
#include "../common/compact_state.slang"
[[vk::binding(0,0)]] RWStructuredBuffer<float4> result;
[shader("compute")] [numthreads(1,1,1)]
void reconstruction_contract(uint3 tid:SV_DispatchThreadID){
    float3 p=float3(2,3,4),q=float3(4,5,6),n=float3(0,1,0);
    result[0]=float4(rrMirror(q,p,n),0);
    result[1]=float4(rrMirror(q+float3(1,0,0),p,n)-rrMirror(q,p,n),0);
    result[2]=float4(rrTransmit(float3(0,-1,0),n,1/1.5),0);
    result[3]=float4(rrTransmit(normalize(float3(.9,-.1,0)),n,1.5),0);
    result[4]=float4(rrMirror(q,p,n)-rrMirror(q,p,n),0);
    GuideHitRecord record=rrHitRecord(3.5,float2(.25,.5),0xf1234567u,0xe1234567u,123,rrEligibility(.02,false,true,.04));
    result[1285]=float4(rrHitValid(record,123)?1:0,rrHitValid(record,124)?1:0,record.hit.w==0xf1234567u?1:0,record.meta.x==0xe1234567u?1:0);
    result[1286]=float4(asfloat(record.hit.x),asfloat(record.hit.yz),float(record.meta.z));
    result[1287]=float4(float(rrEligibility(.8,false,false,.04)),float(rrEligibility(.2,true,false,0)),float(rrEligibility(.3,false,true,.04)),0);
    for(uint i=0;i<1024;i++){
        float z=1-2*(i+.5)/1024,phi=i*2.39996323;
        float3 direction=float3(sqrt(1-z*z)*cos(phi),sqrt(1-z*z)*sin(phi),z);
        float3 unpacked=unpackPathDirection(packPathDirection(direction));
        result[5+i]=float4(length(unpacked-direction),unpacked);
    }
    for(uint i=0;i<256;i++)result[1029+i]=float4(rrSpecularReflectance(float3(.04,.2,.8),(i%16)/15.0,(i/16)/15.0),1);
}
