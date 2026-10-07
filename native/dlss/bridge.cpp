// VoxelLight's JNI adapter to the official NVIDIA NGX Vulkan Ray Reconstruction API.
#include <jni.h>
#include <vulkan/vulkan.h>
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_helpers_dlssd_vk.h"
#include <string>
#include <cstdio>
#include <cstdint>

struct Session {VkDevice device;NVSDK_NGX_Parameter* params=nullptr;NVSDK_NGX_Handle* feature=nullptr;int w=0,h=0,ow=0,oh=0;};
static bool check(JNIEnv* env,NVSDK_NGX_Result result,const char* operation){
    if(!NVSDK_NGX_FAILED(result))return true;
    char text[160];std::snprintf(text,sizeof(text),"DLSS RR %s failed: 0x%08x",operation,(unsigned)result);
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),text);return false;
}
static std::wstring wide(JNIEnv* env,jstring input){
    auto chars=env->GetStringChars(input,nullptr);auto count=env->GetStringLength(input);std::wstring result;
    for(int i=0;i<count;i++)result.push_back((wchar_t)chars[i]);env->ReleaseStringChars(input,chars);return result;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_voxellight_nvidia_DlssNative_extensions(JNIEnv* env,jclass,jboolean device){
    unsigned instances=0,devices=0;const char** instanceNames=nullptr;const char** deviceNames=nullptr;
    if(!check(env,NVSDK_NGX_VULKAN_RequiredExtensions(&instances,&instanceNames,&devices,&deviceNames),"extension requirements"))return nullptr;
    unsigned count=device?devices:instances;auto names=device?deviceNames:instanceNames;
    auto result=env->NewObjectArray(count,env->FindClass("java/lang/String"),nullptr);
    for(unsigned i=0;i<count;i++){auto name=env->NewStringUTF(names[i]);env->SetObjectArrayElement(result,i,name);env->DeleteLocalRef(name);}return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_voxellight_nvidia_DlssNative_create(JNIEnv* env,jclass,jlong instance,jlong physical,jlong device,jlong gipa,jlong gdpa,jstring path){
    auto directory=wide(env,path);const wchar_t* paths[]={directory.c_str()};NVSDK_NGX_FeatureCommonInfo info={};info.PathListInfo.Path=paths;info.PathListInfo.Length=1;
    auto s=new Session{(VkDevice)(uintptr_t)device};
    if(!check(env,NVSDK_NGX_VULKAN_Init_with_ProjectID("8a9f13ac-8546-4c74-bd4e-2c586ad91f08",NVSDK_NGX_ENGINE_TYPE_CUSTOM,"VoxelLight",directory.c_str(),(VkInstance)(uintptr_t)instance,(VkPhysicalDevice)(uintptr_t)physical,s->device,(PFN_vkGetInstanceProcAddr)(uintptr_t)gipa,(PFN_vkGetDeviceProcAddr)(uintptr_t)gdpa,&info),"init")){delete s;return 0;}
    if(!check(env,NVSDK_NGX_VULKAN_GetCapabilityParameters(&s->params),"capabilities")){NVSDK_NGX_VULKAN_Shutdown1(s->device);delete s;return 0;}
    int available=0;NVSDK_NGX_Parameter_GetI(s->params,NVSDK_NGX_Parameter_SuperSamplingDenoising_Available,&available);
    if(!available){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"DLSS Ray Reconstruction is unavailable on this device/driver");NVSDK_NGX_VULKAN_DestroyParameters(s->params);NVSDK_NGX_VULKAN_Shutdown1(s->device);delete s;return 0;}
    return (jlong)(uintptr_t)s;
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_voxellight_nvidia_DlssNative_optimal(JNIEnv* env,jclass,jlong handle,jint w,jint h,jint quality){
    auto s=(Session*)(uintptr_t)handle;void* callback=nullptr;
    NVSDK_NGX_Parameter_GetVoidPointer(s->params,NVSDK_NGX_Parameter_DLSSDOptimalSettingsCallback,&callback);
    if(!callback){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"DLSS RR optimal-size callback unavailable");return nullptr;}
    NVSDK_NGX_Parameter_SetUI(s->params,NVSDK_NGX_Parameter_Width,w);NVSDK_NGX_Parameter_SetUI(s->params,NVSDK_NGX_Parameter_Height,h);NVSDK_NGX_Parameter_SetI(s->params,NVSDK_NGX_Parameter_PerfQualityValue,quality);NVSDK_NGX_Parameter_SetI(s->params,NVSDK_NGX_Parameter_RTXValue,0);
    if(!check(env,((PFN_NVSDK_NGX_DLSS_GetOptimalSettingsCallback)callback)(s->params),"optimal size"))return nullptr;
    unsigned rw=0,rh=0;NVSDK_NGX_Parameter_GetUI(s->params,NVSDK_NGX_Parameter_OutWidth,&rw);NVSDK_NGX_Parameter_GetUI(s->params,NVSDK_NGX_Parameter_OutHeight,&rh);
    jint data[]={(jint)rw,(jint)rh};auto array=env->NewIntArray(2);env->SetIntArrayRegion(array,0,2,data);return array;
}
extern "C" JNIEXPORT void JNICALL Java_com_voxellight_nvidia_DlssNative_evaluate(JNIEnv* env,jclass,jlong handle,jlong command,jlongArray images,jint w,jint h,jint ow,jint oh,jint quality,jfloat jx,jfloat jy,jboolean reset,jfloat frameMs,jfloatArray matrices){
    auto s=(Session*)(uintptr_t)handle;auto cmd=(VkCommandBuffer)(uintptr_t)command;
    if(!s->feature){
        NVSDK_NGX_DLSSD_Create_Params create={};create.InWidth=w;create.InHeight=h;create.InTargetWidth=ow;create.InTargetHeight=oh;create.InPerfQualityValue=(NVSDK_NGX_PerfQuality_Value)quality;create.InDenoiseMode=NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;create.InRoughnessMode=NVSDK_NGX_DLSS_Roughness_Mode_Packed;create.InUseHWDepth=NVSDK_NGX_DLSS_Depth_Type_HW;
        create.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR|NVSDK_NGX_DLSS_Feature_Flags_MVLowRes|NVSDK_NGX_DLSS_Feature_Flags_DepthInverted|NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
        if(!check(env,NGX_VULKAN_CREATE_DLSSD_EXT1(s->device,cmd,1,1,&s->feature,s->params,&create),"create feature"))return;
        s->w=w;s->h=h;s->ow=ow;s->oh=oh;
    }
    if(w!=s->w||h!=s->h||ow!=s->ow||oh!=s->oh){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"DLSS feature dimensions changed without recreation");return;}
    jlong handles[21];env->GetLongArrayRegion(images,0,21,handles);NVSDK_NGX_Resource_VK resources[7];
    VkImageSubresourceRange range={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    for(int i=0;i<7;i++)resources[i]=NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView)(uintptr_t)handles[i*3],(VkImage)(uintptr_t)handles[i*3+1],range,(VkFormat)handles[i*3+2],i==6?ow:w,i==6?oh:h,i==6);
    float matrix[32];env->GetFloatArrayRegion(matrices,0,32,matrix);
    NVSDK_NGX_VK_DLSSD_Eval_Params params={};params.pInColor=&resources[0];params.pInDepth=&resources[1];params.pInMotionVectors=&resources[2];params.pInNormals=&resources[3];params.pInDiffuseAlbedo=&resources[4];params.pInSpecularAlbedo=&resources[5];params.pInOutput=&resources[6];
    params.InRenderSubrectDimensions.Width=w;params.InRenderSubrectDimensions.Height=h;params.InJitterOffsetX=jx;params.InJitterOffsetY=jy;params.InMVScaleX=1;params.InMVScaleY=1;params.InReset=reset?1:0;params.InPreExposure=1;params.InExposureScale=1;params.InFrameTimeDeltaInMsec=frameMs;params.pInWorldToViewMatrix=matrix;params.pInViewToClipMatrix=matrix+16;
    check(env,NGX_VULKAN_EVALUATE_DLSSD_EXT(cmd,s->feature,s->params,&params),"evaluate");
}
extern "C" JNIEXPORT void JNICALL Java_com_voxellight_nvidia_DlssNative_destroy(JNIEnv*,jclass,jlong handle){
    auto s=(Session*)(uintptr_t)handle;if(!s)return;if(s->feature)NVSDK_NGX_VULKAN_ReleaseFeature(s->feature);if(s->params)NVSDK_NGX_VULKAN_DestroyParameters(s->params);NVSDK_NGX_VULKAN_Shutdown1(s->device);delete s;
}
