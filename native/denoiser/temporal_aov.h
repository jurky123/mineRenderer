#pragma once
#include <optix.h>
#include <optix_stubs.h>
#include <cuda.h>
#include <array>

// Caller owns context/stream and buffers. This module creates no trace pipeline or AS.
// Allocate/setup/clear internal guides and both output banks before the first frame.
namespace voxellight {
struct TemporalAovFrame {
    OptixImage2D albedo{},normal{},flow{},flowTrust{};
    OptixImage2D previousInternalGuide{},outputInternalGuide{};
    std::array<OptixImage2D,1> input{},previousOutput{},output{};
    CUdeviceptr averageColor=0;
    bool previousValid=false;
};
inline OptixResult createTemporalAov(OptixDeviceContext context,OptixDenoiser* denoiser){
    OptixDenoiserOptions options{};options.guideAlbedo=1;options.guideNormal=1;
    options.denoiseAlpha=OPTIX_DENOISER_ALPHA_MODE_COPY;
    return optixDenoiserCreate(context,OPTIX_DENOISER_MODEL_KIND_TEMPORAL_AOV,&options,denoiser);
}
inline OptixResult denoiseTemporalAov(OptixDenoiser denoiser,CUstream stream,
        CUdeviceptr state,size_t stateBytes,CUdeviceptr scratch,size_t scratchBytes,const TemporalAovFrame& frame){
    OptixDenoiserGuideLayer guides{};guides.albedo=frame.albedo;guides.normal=frame.normal;
    guides.flow=frame.flow;guides.flowTrustworthiness=frame.flowTrust;
    guides.previousOutputInternalGuideLayer=frame.previousInternalGuide;
    guides.outputInternalGuideLayer=frame.outputInternalGuide;
    OptixDenoiserLayer layers[1]{};
    OptixDenoiserAOVType types[1]={OPTIX_DENOISER_AOV_TYPE_BEAUTY};
    for(int i=0;i<1;i++){layers[i].input=frame.input[i];layers[i].previousOutput=frame.previousOutput[i];layers[i].output=frame.output[i];layers[i].type=types[i];}
    OptixDenoiserParams control{};control.temporalModeUsePreviousLayers=frame.previousValid;control.hdrAverageColor=frame.averageColor;
    return optixDenoiserInvoke(denoiser,stream,&control,state,stateBytes,&guides,layers,1,0,0,scratch,scratchBytes);
}
}
