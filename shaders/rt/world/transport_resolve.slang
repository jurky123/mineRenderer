#include "../common/transport_test.slang"
[shader("raygeneration")]
void transport_resolve(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;float3 sum=0;uint valid=0;
    for(uint lane=0;lane<max(1u,camera.padding);lane++){
        float4 sample=output[index+lane*camera.width*camera.height];
        if(sample.w>.5&&all(isfinite(sample.xyz))){valid++;sum=sum*(1-1.0/valid)+sample.xyz/valid;}
    }
    output[index]=float4(valid>0?sum:float3(0),valid);
}
