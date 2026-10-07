#include "../common/material_transport.slang"
struct DispatchSettings {uint bounce;};
[[vk::push_constant]] ConstantBuffer<DispatchSettings> dispatchSettings;
[shader("raygeneration")]
void material_indirect() {
    uint2 pixel=DispatchRaysIndex().xy;uint index=pixel.y*camera.width+pixel.x+DispatchRaysIndex().z*camera.width*camera.height;
    if(compactBounce(dispatchSettings.bounce)){
        uint bounce=dispatchSettings.bounce;
        if(bounce==0||DispatchRaysIndex().x>=pageFeedback[520u+bounce].x)return;
        uint capacity=camera.width*camera.height*max(1u,camera.padding);
        index=pageFeedback[1648u+(bounce&1u)*capacity+DispatchRaysIndex().x].x;
    }
    PathHot hot=loadHot(index);if(hot.origin.w==0||(hot.state&32u)!=0)return;
    PathState path=loadPath(index);
#ifdef RT_ITERATIVE
    for(uint bounce=1;bounce<6&&path.origin.w!=0&&path.padding==0;bounce++)advance(path);
    storePath(index,path);output[index]=pathOutput(path);
#else
    advance(path);storePath(index,path);output[index]=pathOutput(path);appendContinuation(index,path);
#endif
}
