// Denoiser-only CUDA interop. No OptiX tracing, GAS/IAS, PTX or runtime compiler.
#include <jni.h>
#include <cuda.h>
#include <optix_function_table_definition.h>
#include "temporal_aov.h"
#include <stdexcept>
#include <string>
#include <cstring>
#include <algorithm>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#include <unistd.h>
#endif
namespace {
void* symbol(const char* name){
#ifdef _WIN32
 static HMODULE library=LoadLibraryA("nvcuda.dll");return library?reinterpret_cast<void*>(GetProcAddress(library,name)):nullptr;
#else
 static void* library=dlopen("libcuda.so.1",RTLD_NOW|RTLD_LOCAL);return library?dlsym(library,name):nullptr;
#endif
}
#define CUDA_FN(name) decltype(&name) name##_fn=reinterpret_cast<decltype(&name)>(symbol(#name));
CUDA_FN(cuInit) CUDA_FN(cuDeviceGetCount) CUDA_FN(cuDeviceGet) CUDA_FN(cuDeviceGetUuid_v2)
CUDA_FN(cuDevicePrimaryCtxRetain) CUDA_FN(cuDevicePrimaryCtxRelease_v2) CUDA_FN(cuCtxPushCurrent_v2) CUDA_FN(cuCtxPopCurrent_v2)
CUDA_FN(cuStreamCreate) CUDA_FN(cuStreamDestroy_v2) CUDA_FN(cuStreamSynchronize)
CUDA_FN(cuMemAlloc_v2) CUDA_FN(cuMemFree_v2) CUDA_FN(cuMemsetD8Async) CUDA_FN(cuMemcpyDtoDAsync_v2)
CUDA_FN(cuImportExternalMemory) CUDA_FN(cuExternalMemoryGetMappedBuffer) CUDA_FN(cuDestroyExternalMemory)
CUDA_FN(cuImportExternalSemaphore) CUDA_FN(cuDestroyExternalSemaphore) CUDA_FN(cuWaitExternalSemaphoresAsync) CUDA_FN(cuSignalExternalSemaphoresAsync)
#undef CUDA_FN
void check(CUresult result){if(result!=CUDA_SUCCESS)throw std::runtime_error("CUDA denoiser error "+std::to_string(int(result)));}
void check(OptixResult result){if(result!=OPTIX_SUCCESS)throw std::runtime_error("OptiX denoiser error "+std::to_string(int(result)));}
struct Current{Current(CUcontext c){check(cuCtxPushCurrent_v2_fn(c));}~Current(){CUcontext c;cuCtxPopCurrent_v2_fn(&c);}};
struct Session {
 CUdevice device=-1;CUcontext context=nullptr;CUstream stream=nullptr;OptixDeviceContext optix=nullptr;OptixDenoiser denoiser=nullptr;
 CUexternalMemory memory=nullptr;CUdeviceptr mapped=0,state=0,scratch=0,average=0,previous=0,guide[2]={0,0};CUexternalSemaphore ready=nullptr,done=nullptr;
 size_t stateBytes=0,scratchBytes=0,plane=0,guideBytes=0;unsigned width=0,height=0,bank=0;
 ~Session(){if(!context)return;try{Current current(context);if(stream)cuStreamSynchronize_fn(stream);if(denoiser)optixDenoiserDestroy(denoiser);if(optix)optixDeviceContextDestroy(optix);for(auto p:{state,scratch,average,previous,guide[0],guide[1],mapped})if(p)cuMemFree_v2_fn(p);if(memory)cuDestroyExternalMemory_fn(memory);if(ready)cuDestroyExternalSemaphore_fn(ready);if(done)cuDestroyExternalSemaphore_fn(done);if(stream)cuStreamDestroy_v2_fn(stream);}catch(...){}cuDevicePrimaryCtxRelease_v2_fn(device);}
 OptixImage2D image(CUdeviceptr ptr,OptixPixelFormat format=OPTIX_PIXEL_FORMAT_FLOAT4,unsigned stride=16){OptixImage2D image{};image.data=ptr;image.width=width;image.height=height;image.rowStrideInBytes=width*stride;image.pixelStrideInBytes=stride;image.format=format;return image;}
};
void closeHandle(jlong handle){
#ifdef _WIN32
 CloseHandle(reinterpret_cast<HANDLE>(handle));
#else
 close(int(handle));
#endif
}
void importMemory(Session& s,jlong handle,size_t size){CUDA_EXTERNAL_MEMORY_HANDLE_DESC desc{};
#ifdef _WIN32
 desc.type=CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32;desc.handle.win32.handle=reinterpret_cast<void*>(handle);
#else
 desc.type=CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD;desc.handle.fd=int(handle);
#endif
 desc.size=size;desc.flags=CUDA_EXTERNAL_MEMORY_DEDICATED;CUresult result=cuImportExternalMemory_fn(&s.memory,&desc);
#ifdef _WIN32
 closeHandle(handle);
#else
 if(result!=CUDA_SUCCESS)closeHandle(handle);
#endif
 check(result);CUDA_EXTERNAL_MEMORY_BUFFER_DESC buffer{};buffer.size=s.plane*6;check(cuExternalMemoryGetMappedBuffer_fn(&s.mapped,s.memory,&buffer));
}
void importSemaphore(CUexternalSemaphore& semaphore,jlong handle){CUDA_EXTERNAL_SEMAPHORE_HANDLE_DESC desc{};
#ifdef _WIN32
 desc.type=CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32;desc.handle.win32.handle=reinterpret_cast<void*>(handle);
#else
 desc.type=CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD;desc.handle.fd=int(handle);
#endif
 CUresult result=cuImportExternalSemaphore_fn(&semaphore,&desc);
#ifdef _WIN32
 closeHandle(handle);
#else
 if(result!=CUDA_SUCCESS)closeHandle(handle);
#endif
 check(result);
}
void fail(JNIEnv* env,const std::exception& error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_voxellight_nvidia_OptixDenoiserNative_create(JNIEnv* env,jclass,jbyteArray uuid,jlong memory,jlong allocation,jlong ready,jlong done,jint width,jint height){
 Session* session=nullptr;bool memoryUsed=false,readyUsed=false,doneUsed=false;
 try{
  if(!cuInit_fn||!cuDeviceGetUuid_v2_fn||!cuDeviceGetCount_fn||!cuDeviceGet_fn||!cuDevicePrimaryCtxRetain_fn||!cuDevicePrimaryCtxRelease_v2_fn||!cuCtxPushCurrent_v2_fn||!cuCtxPopCurrent_v2_fn||!cuStreamCreate_fn||!cuStreamDestroy_v2_fn||!cuStreamSynchronize_fn||!cuMemAlloc_v2_fn||!cuMemFree_v2_fn||!cuMemsetD8Async_fn||!cuMemcpyDtoDAsync_v2_fn||!cuImportExternalMemory_fn||!cuExternalMemoryGetMappedBuffer_fn||!cuDestroyExternalMemory_fn||!cuImportExternalSemaphore_fn||!cuDestroyExternalSemaphore_fn||!cuWaitExternalSemaphoresAsync_fn||!cuSignalExternalSemaphoresAsync_fn)throw std::runtime_error("NVIDIA CUDA driver unavailable");check(cuInit_fn(0));int count=0;check(cuDeviceGetCount_fn(&count));jbyte id[16];if(env->GetArrayLength(uuid)!=16)throw std::runtime_error("Invalid Vulkan UUID");env->GetByteArrayRegion(uuid,0,16,id);
  CUdevice device=-1;for(int i=0;i<count;i++){CUdevice candidate;CUuuid value;check(cuDeviceGet_fn(&candidate,i));check(cuDeviceGetUuid_v2_fn(&value,candidate));if(std::memcmp(value.bytes,id,16)==0){device=candidate;break;}}
  if(device<0)throw std::runtime_error("Vulkan and CUDA GPU UUID mismatch");
  session=new Session;session->device=device;session->width=width;session->height=height;session->plane=size_t(width)*height*16;
  check(cuDevicePrimaryCtxRetain_fn(&session->context,device));Current current(session->context);check(cuStreamCreate_fn(&session->stream,CU_STREAM_NON_BLOCKING));check(optixInit());OptixDeviceContextOptions options{};check(optixDeviceContextCreate(session->context,&options,&session->optix));check(voxellight::createTemporalAov(session->optix,&session->denoiser));
  memoryUsed=true;importMemory(*session,memory,allocation);readyUsed=true;importSemaphore(session->ready,ready);doneUsed=true;importSemaphore(session->done,done);
  OptixDenoiserSizes sizes{};check(optixDenoiserComputeMemoryResources(session->denoiser,width,height,&sizes));session->stateBytes=sizes.stateSizeInBytes;session->scratchBytes=std::max(sizes.withoutOverlapScratchSizeInBytes,sizes.computeAverageColorSizeInBytes);session->guideBytes=size_t(width)*height*sizes.internalGuideLayerPixelSizeInBytes;
  for(auto pair:{std::pair<CUdeviceptr*,size_t>{&session->state,session->stateBytes},{&session->scratch,session->scratchBytes},{&session->average,16},{&session->previous,session->plane},{&session->guide[0],session->guideBytes},{&session->guide[1],session->guideBytes}})check(cuMemAlloc_v2_fn(pair.first,pair.second));
  check(cuMemsetD8Async_fn(session->previous,0,session->plane,session->stream));for(auto p:session->guide)check(cuMemsetD8Async_fn(p,0,session->guideBytes,session->stream));
  check(optixDenoiserSetup(session->denoiser,session->stream,width,height,session->state,session->stateBytes,session->scratch,session->scratchBytes));check(cuStreamSynchronize_fn(session->stream));return reinterpret_cast<jlong>(session);
 }catch(const std::exception& error){delete session;if(!memoryUsed)closeHandle(memory);if(!readyUsed)closeHandle(ready);if(!doneUsed)closeHandle(done);fail(env,error);return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_voxellight_nvidia_OptixDenoiserNative_invoke(JNIEnv* env,jclass,jlong handle,jboolean previousValid){
 auto& s=*reinterpret_cast<Session*>(handle);bool consumed=false,signalled=false;
 try{Current current(s.context);CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};check(cuWaitExternalSemaphoresAsync_fn(&s.ready,&wait,1,s.stream));consumed=true;
  voxellight::TemporalAovFrame frame{};frame.albedo=s.image(s.mapped+s.plane);frame.normal=s.image(s.mapped+s.plane*2);frame.flow=s.image(s.mapped+s.plane*3,OPTIX_PIXEL_FORMAT_FLOAT2);frame.flowTrust=s.image(s.mapped+s.plane*4,OPTIX_PIXEL_FORMAT_FLOAT1);frame.previousValid=previousValid;
  frame.previousInternalGuide=s.image(s.guide[s.bank],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,unsigned(s.guideBytes/(s.width*s.height)));frame.outputInternalGuide=s.image(s.guide[1-s.bank],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,unsigned(s.guideBytes/(s.width*s.height)));
  for(int i=0;i<1;i++){unsigned plane=i==0?0:4+i;frame.input[i]=s.image(s.mapped+s.plane*plane);frame.output[i]=s.image(s.mapped+s.plane*(5+i));frame.previousOutput[i]=s.image(s.previous+s.plane*i);}
  auto beauty=frame.input[0];check(optixDenoiserComputeAverageColor(s.denoiser,s.stream,&beauty,s.average,s.scratch,s.scratchBytes));frame.averageColor=s.average;
  check(voxellight::denoiseTemporalAov(s.denoiser,s.stream,s.state,s.stateBytes,s.scratch,s.scratchBytes,frame));
  check(cuMemcpyDtoDAsync_v2_fn(s.previous,s.mapped+s.plane*5,s.plane,s.stream));s.bank=1-s.bank;
  CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};check(cuSignalExternalSemaphoresAsync_fn(&s.done,&signal,1,s.stream));signalled=true;
 }catch(const std::exception& error){if(consumed&&!signalled){try{Current current(s.context);CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};check(cuSignalExternalSemaphoresAsync_fn(&s.done,&signal,1,s.stream));}catch(...){}}fail(env,error);}
}
extern "C" JNIEXPORT void JNICALL Java_com_voxellight_nvidia_OptixDenoiserNative_destroy(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Session*>(handle);}
