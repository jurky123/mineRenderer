// Coarse-grained JNI: one frame batch per call. All CUDA work stays on one Java worker.
#include <jni.h>
#include <cuda.h>
#include <optix.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <stdexcept>
#include <string>
#include <vector>
#include <cstring>
#ifdef _WIN32
#include <windows.h>
#define EXPORT extern "C" __declspec(dllexport)
#else
#include <dlfcn.h>
#define EXPORT extern "C" __attribute__((visibility("default")))
#endif
static_assert(sizeof(jlong)==8,"JNI handles require a 64-bit jlong");
struct Driver {
#ifdef _WIN32
 HMODULE lib=LoadLibraryA("nvcuda.dll");
 void* symbol(const char* s){return reinterpret_cast<void*>(GetProcAddress(lib,s));}
#else
 void* lib=dlopen("libcuda.so.1",RTLD_NOW|RTLD_LOCAL);
 void* symbol(const char* s){return dlsym(lib,s);}
#endif
#define API(name) decltype(&::name) name=nullptr;
 API(cuInit) API(cuDeviceGetCount) API(cuDeviceGet) API(cuDeviceGetUuid) API(cuDevicePrimaryCtxRetain) API(cuDevicePrimaryCtxRelease)
 API(cuCtxSetCurrent) API(cuMemAlloc) API(cuMemFree) API(cuMemcpyHtoD) API(cuMemcpyDtoH) API(cuMemcpyDtoDAsync) API(cuMemsetD8Async)
 API(cuModuleLoadData) API(cuModuleUnload) API(cuModuleGetFunction) API(cuLaunchKernel) API(cuStreamCreate) API(cuStreamDestroy) API(cuStreamSynchronize)
#undef API
 Driver(){if(!lib)throw std::runtime_error("NVIDIA CUDA driver unavailable");
#define LOAD(name,exportName) name=reinterpret_cast<decltype(name)>(symbol(exportName));if(!name)throw std::runtime_error("CUDA symbol missing: " exportName);
 LOAD(cuInit,"cuInit") LOAD(cuDeviceGetCount,"cuDeviceGetCount") LOAD(cuDeviceGet,"cuDeviceGet") LOAD(cuDeviceGetUuid,"cuDeviceGetUuid")
 LOAD(cuDevicePrimaryCtxRetain,"cuDevicePrimaryCtxRetain") LOAD(cuDevicePrimaryCtxRelease,"cuDevicePrimaryCtxRelease_v2") LOAD(cuCtxSetCurrent,"cuCtxSetCurrent")
 LOAD(cuMemAlloc,"cuMemAlloc_v2") LOAD(cuMemFree,"cuMemFree_v2") LOAD(cuMemcpyHtoD,"cuMemcpyHtoD_v2") LOAD(cuMemcpyDtoH,"cuMemcpyDtoH_v2") LOAD(cuMemcpyDtoDAsync,"cuMemcpyDtoDAsync_v2") LOAD(cuMemsetD8Async,"cuMemsetD8Async")
 LOAD(cuModuleLoadData,"cuModuleLoadData") LOAD(cuModuleUnload,"cuModuleUnload") LOAD(cuModuleGetFunction,"cuModuleGetFunction") LOAD(cuLaunchKernel,"cuLaunchKernel")
 LOAD(cuStreamCreate,"cuStreamCreate") LOAD(cuStreamDestroy,"cuStreamDestroy_v2") LOAD(cuStreamSynchronize,"cuStreamSynchronize")
#undef LOAD
 }
 // Keep the loaded driver for process lifetime: OptiX stubs may reference it.
};
static Driver& driver(){static Driver d;return d;}
static void check(CUresult r){if(r!=CUDA_SUCCESS)throw std::runtime_error("CUDA error "+std::to_string((int)r));}
static void check(OptixResult r){if(r!=OPTIX_SUCCESS)throw std::runtime_error("OptiX error "+std::to_string((int)r));}
struct Context {
 Driver& d;CUdevice device=-1;CUcontext cuda=nullptr;CUstream stream=nullptr;CUmodule module=nullptr;CUfunction paths=nullptr,temporal=nullptr;
 OptixDeviceContext optix=nullptr;OptixDenoiser denoiser=nullptr;int width=0,height=0,samples=0,epoch=0,read=0;
 CUdeviceptr position=0,normal=0,albedo=0,grid=0,params=0,sum=0,raw=0,guide=0,output=0,state=0,scratch=0,intensity=0;
 CUdeviceptr history[2]{},moments[2]{},previousPosition=0,previousNormal=0;
 size_t stateBytes=0,scratchBytes=0;std::vector<CUdeviceptr> allocations;
 Context():d(driver()){}
 CUdeviceptr alloc(size_t bytes){CUdeviceptr p;check(d.cuMemAlloc(&p,bytes));allocations.push_back(p);return p;}
 void init(const unsigned char* uuid,const char* ptx,int w,int h){
  if(w<1||h<1||w>640||h>360)throw std::runtime_error("Path trace dimensions exceed 640x360 budget");width=w;height=h;
  check(d.cuInit(0));int count=0;check(d.cuDeviceGetCount(&count));
  for(int i=0;i<count;i++){CUdevice dev;CUuuid id;check(d.cuDeviceGet(&dev,i));check(d.cuDeviceGetUuid(&id,dev));if(!std::memcmp(id.bytes,uuid,16)){device=dev;break;}}
  if(device<0)throw std::runtime_error("No CUDA device matches the Vulkan GPU UUID");
  check(d.cuDevicePrimaryCtxRetain(&cuda,device));check(d.cuCtxSetCurrent(cuda));check(d.cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));
  check(d.cuModuleLoadData(&module,ptx));check(d.cuModuleGetFunction(&paths,module,"paths"));check(d.cuModuleGetFunction(&temporal,module,"temporal"));
  check(optixInit());OptixDeviceContextOptions co{};check(optixDeviceContextCreate(cuda,&co,&optix));
  OptixDenoiserOptions options{};options.guideAlbedo=1;options.guideNormal=1;options.denoiseAlpha=OPTIX_DENOISER_ALPHA_MODE_COPY;
  check(optixDenoiserCreate(optix,OPTIX_DENOISER_MODEL_KIND_HDR,&options,&denoiser));
  size_t bytes=(size_t)w*h*16;position=alloc(bytes);normal=alloc(bytes);albedo=alloc(bytes);sum=alloc(bytes);raw=alloc(bytes);guide=alloc(bytes);output=alloc(bytes);
  for(int i=0;i<2;i++){history[i]=alloc(bytes);moments[i]=alloc(bytes);}previousPosition=alloc(bytes);previousNormal=alloc(bytes);
  grid=alloc(80*80*80*4);params=alloc(41*4);intensity=alloc(4);
  OptixDenoiserSizes sizes{};check(optixDenoiserComputeMemoryResources(denoiser,w,h,&sizes));stateBytes=sizes.stateSizeInBytes;scratchBytes=sizes.withoutOverlapScratchSizeInBytes;
  if(scratchBytes<sizes.computeIntensitySizeInBytes)scratchBytes=sizes.computeIntensitySizeInBytes;
  if(stateBytes+scratchBytes>192*1024*1024)throw std::runtime_error("OptiX denoiser exceeds 192 MiB budget");
  state=alloc(stateBytes);scratch=alloc(scratchBytes);check(optixDenoiserSetup(denoiser,stream,w,h,state,stateBytes,scratch,scratchBytes));check(d.cuMemsetD8Async(sum,0,bytes,stream));
 }
 OptixImage2D image(CUdeviceptr p){OptixImage2D i{};i.data=p;i.width=width;i.height=height;i.rowStrideInBytes=width*16;i.pixelStrideInBytes=16;i.format=OPTIX_PIXEL_FORMAT_FLOAT4;return i;}
 int render(const void* pos,const void* normals,const void* alb,const void* vox,const void* settings,void* result,bool reset,bool denoise){
  check(d.cuCtxSetCurrent(cuda));(void)reset; // Reuse is validated per pixel by the settings/history guides, never by camera equality.
  size_t bytes=(size_t)width*height*16;check(d.cuMemsetD8Async(sum,0,bytes,stream));
  check(d.cuMemcpyHtoD(position,pos,bytes));check(d.cuMemcpyHtoD(normal,normals,bytes));check(d.cuMemcpyHtoD(albedo,alb,bytes));check(d.cuMemcpyHtoD(grid,vox,80*80*80*4));check(d.cuMemcpyHtoD(params,settings,41*4));
  int count=width*height,batchSample=0;void* args[]={&position,&normal,&albedo,&grid,&params,&sum,&raw,&guide,&count,&batchSample,&samples};
  for(int i=0;i<8;i++){batchSample=i;check(d.cuLaunchKernel(paths,(count+127)/128,1,1,128,1,1,0,stream,args,nullptr));++samples;}
  int write=1-read;void* historyArgs[]={&raw,&position,&normal,&previousPosition,&previousNormal,&history[read],&moments[read],&history[write],&moments[write],&params,&width,&height};
  check(d.cuLaunchKernel(temporal,(count+127)/128,1,1,128,1,1,0,stream,historyArgs,nullptr));read=write;
  check(d.cuMemcpyDtoDAsync(previousPosition,position,bytes,stream));check(d.cuMemcpyDtoDAsync(previousNormal,normal,bytes,stream));
  if(denoise){auto input=image(history[read]);check(optixDenoiserComputeIntensity(denoiser,stream,&input,intensity,scratch,scratchBytes));
   OptixDenoiserParams dp{};dp.hdrIntensity=intensity;OptixDenoiserGuideLayer guides{};guides.albedo=image(albedo);guides.normal=image(guide);
   OptixDenoiserLayer layer{};layer.input=input;layer.output=image(output);
   check(optixDenoiserInvoke(denoiser,stream,&dp,state,stateBytes,&guides,&layer,1,0,0,scratch,scratchBytes));
  }
  check(d.cuStreamSynchronize(stream));check(d.cuMemcpyDtoH(result,denoise?output:history[read],bytes));return ++epoch;
 }
 ~Context(){if(!cuda)return;d.cuCtxSetCurrent(cuda);if(stream)d.cuStreamSynchronize(stream);if(denoiser)optixDenoiserDestroy(denoiser);if(optix)optixDeviceContextDestroy(optix);for(auto p:allocations)d.cuMemFree(p);if(module)d.cuModuleUnload(module);if(stream)d.cuStreamDestroy(stream);d.cuDevicePrimaryCtxRelease(device);}
};
static void fail(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
static void* buffer(JNIEnv* e,jobject b,size_t bytes){void* p=e->GetDirectBufferAddress(b);jlong size=e->GetDirectBufferCapacity(b);if(!p||size<0||(size_t)size<bytes)throw std::runtime_error("Invalid native direct-buffer capacity");return p;}
EXPORT jlong JNICALL Java_com_voxellight_adapter_OptixBridge_create(JNIEnv* e,jclass,jbyteArray id,jbyteArray code,jint w,jint h){
 Context* c=nullptr;try{if(e->GetArrayLength(id)!=16)throw std::runtime_error("GPU UUID must have 16 bytes");unsigned char uuid[16];e->GetByteArrayRegion(id,0,16,reinterpret_cast<jbyte*>(uuid));int n=e->GetArrayLength(code);if(n<1||n>8*1024*1024)throw std::runtime_error("Invalid PTX size");std::vector<char> ptx(n+1);e->GetByteArrayRegion(code,0,n,reinterpret_cast<jbyte*>(ptx.data()));c=new Context;c->init(uuid,ptx.data(),w,h);return reinterpret_cast<jlong>(c);}catch(const std::exception& ex){delete c;fail(e,ex);return 0;}}
EXPORT jint JNICALL Java_com_voxellight_adapter_OptixBridge_trace(JNIEnv* e,jclass,jlong handle,jobject pos,jobject normals,jobject alb,jobject vox,jobject settings,jobject result,jboolean reset,jboolean denoise){
 try{auto* c=reinterpret_cast<Context*>(handle);if(!c)throw std::runtime_error("Closed path tracer");size_t bytes=(size_t)c->width*c->height*16;return c->render(buffer(e,pos,bytes),buffer(e,normals,bytes),buffer(e,alb,bytes),buffer(e,vox,80*80*80*4),buffer(e,settings,164),buffer(e,result,bytes),reset,denoise);}catch(const std::exception& ex){fail(e,ex);return 0;}}
EXPORT void JNICALL Java_com_voxellight_adapter_OptixBridge_destroy(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Context*>(handle);}
