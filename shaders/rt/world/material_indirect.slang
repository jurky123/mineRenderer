#include "../common/material_transport.slang"
struct DispatchSettings {uint bounce;};
[[vk::push_constant]] ConstantBuffer<DispatchSettings> dispatchSettings;
[shader("raygeneration")]
void material_indirect() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    if(compactPaths()){
        uint bounce=dispatchSettings.bounce;
        if(bounce==0||DispatchRaysIndex().x>=pageFeedback[520u+bounce].w)return;
        uint capacity=camera.width*camera.height*max(1u,camera.padding);
        index=pageFeedback[544u+(bounce&1u)*capacity+DispatchRaysIndex().x].x;
    }
    PathHot hot=loadHot(index);if(hot.origin.w==0||hot.padding!=0)return;
    PathState path=loadPath(index);
    advance(path);storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
}
