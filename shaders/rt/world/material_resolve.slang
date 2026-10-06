#include "../common/material_transport.slang"
[shader("raygeneration")]
void material_resolve(){
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x;float3 mean=0,diffuse=0,reflection=0,refraction=0;uint valid=0;
    for(uint lane=0;lane<max(1u,camera.padding);lane++){
        uint pathIndex=index+lane*camera.width*camera.height;float4 sample=output[pathIndex];
        if(sample.w>.5&&all(isfinite(sample.xyz))){
            valid++;float weight=1.0/valid;mean=mean*(1-weight)+sample.xyz*weight;
            PathAov path=pathAovs[pathIndex];diffuse=diffuse*(1-weight)+path.diffuse.xyz*weight;reflection=reflection*(1-weight)+path.reflection.xyz*weight;refraction=refraction*(1-weight)+path.refraction.xyz*weight;
        }
    }
    output[index]=float4(mean,valid);
    uint base=camera.width*camera.height*max(1u,camera.padding)+4;
    output[base+camera.width*camera.height*3+index]=float4(diffuse,valid);
    output[base+camera.width*camera.height*4+index]=float4(reflection,valid);
    output[base+camera.width*camera.height*5+index]=float4(refraction,valid);
}
