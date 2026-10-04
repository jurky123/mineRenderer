// Included after the shared CUDA driver loader in bridge.cpp. No Vulkan loader or SDK redistribution.
#include <map>
#include <cmath>
#include <array>
#include <memory>
#include <optix_stack_size.h>
#include "contract.h"
#ifndef _WIN32
#include <unistd.h>
#endif
struct RtDriver {
 Driver& d=driver();
#define RT_API(name) decltype(&::name) name=nullptr;
 RT_API(cuImportExternalMemory) RT_API(cuExternalMemoryGetMappedBuffer) RT_API(cuDestroyExternalMemory)
 RT_API(cuImportExternalSemaphore) RT_API(cuWaitExternalSemaphoresAsync) RT_API(cuSignalExternalSemaphoresAsync) RT_API(cuDestroyExternalSemaphore)
 RT_API(cuEventElapsedTime) RT_API(cuMemHostAlloc) RT_API(cuMemFreeHost) RT_API(cuEventCreate) RT_API(cuEventRecord) RT_API(cuEventQuery) RT_API(cuEventDestroy) RT_API(cuMemcpyHtoDAsync) RT_API(cuMemcpyDtoHAsync)
#undef RT_API
 RtDriver(){
#define RT_LOAD(name,exportName) name=reinterpret_cast<decltype(name)>(d.symbol(exportName));if(!name)throw std::runtime_error("RTX CUDA symbol missing: " exportName);
 RT_LOAD(cuImportExternalMemory,"cuImportExternalMemory") RT_LOAD(cuExternalMemoryGetMappedBuffer,"cuExternalMemoryGetMappedBuffer") RT_LOAD(cuDestroyExternalMemory,"cuDestroyExternalMemory")
 RT_LOAD(cuImportExternalSemaphore,"cuImportExternalSemaphore") RT_LOAD(cuWaitExternalSemaphoresAsync,"cuWaitExternalSemaphoresAsync") RT_LOAD(cuSignalExternalSemaphoresAsync,"cuSignalExternalSemaphoresAsync") RT_LOAD(cuDestroyExternalSemaphore,"cuDestroyExternalSemaphore")
 RT_LOAD(cuEventElapsedTime,"cuEventElapsedTime") RT_LOAD(cuMemHostAlloc,"cuMemHostAlloc") RT_LOAD(cuMemFreeHost,"cuMemFreeHost") RT_LOAD(cuEventCreate,"cuEventCreate") RT_LOAD(cuEventRecord,"cuEventRecord") RT_LOAD(cuEventQuery,"cuEventQuery") RT_LOAD(cuEventDestroy,"cuEventDestroy_v2") RT_LOAD(cuMemcpyHtoDAsync,"cuMemcpyHtoDAsync_v2") RT_LOAD(cuMemcpyDtoHAsync,"cuMemcpyDtoHAsync_v2")
#undef RT_LOAD
 }
};
struct RtContext {
 RtDriver api;Driver& d=api.d;CUdevice device=-1;CUcontext cuda=nullptr;CUstream stream=nullptr;
 OptixDeviceContext optix=nullptr;OptixModule module=nullptr;OptixPipeline pipeline=nullptr;OptixProgramGroup groups[4]{};
 OptixShaderBindingTable sbt{};CUdeviceptr paramsGpu=0,iasGpu=0,instancesGpu=0,sbtHit=0,probesGpu=0;
 OptixTraversableHandle ias=0;RtParams params{};
 OptixDenoiser denoiser=nullptr;CUdeviceptr denoiserState=0,denoiserScratch=0,internalGuide[2]{},denoised[2][3]{};size_t denoiserStateBytes=0,denoiserScratchBytes=0;int internalGuideStride=0,denoiserWidth=0,denoiserHeight=0,denoiserRead=0;unsigned previousOptions=0;bool previousFrame=false;float previousClip[16]{};float3 previousCamera{};
 OptixImage2D image(CUdeviceptr pointer,OptixPixelFormat format=OPTIX_PIXEL_FORMAT_FLOAT4,int stride=16){OptixImage2D image{};image.data=pointer;image.width=denoiserWidth;image.height=denoiserHeight;image.pixelStrideInBytes=stride;image.rowStrideInBytes=denoiserWidth*stride;image.format=format;return image;}
 void prepareDenoiser(int w,int h){if(denoiser)return;denoiserWidth=w;denoiserHeight=h;OptixDenoiserOptions options{};options.guideAlbedo=1;options.guideNormal=1;options.denoiseAlpha=OPTIX_DENOISER_ALPHA_MODE_COPY;check(optixDenoiserCreate(optix,OPTIX_DENOISER_MODEL_KIND_TEMPORAL_AOV,&options,&denoiser));OptixDenoiserSizes sizes{};check(optixDenoiserComputeMemoryResources(denoiser,w,h,&sizes));internalGuideStride=sizes.internalGuideLayerPixelSizeInBytes;denoiserStateBytes=sizes.stateSizeInBytes;denoiserScratchBytes=sizes.withoutOverlapScratchSizeInBytes;if(denoiserStateBytes+denoiserScratchBytes>192*1024*1024)throw std::runtime_error("RTX denoiser memory budget exceeded");denoiserState=alloc(denoiserStateBytes);owned.push_back(denoiserState);denoiserScratch=alloc(denoiserScratchBytes);owned.push_back(denoiserScratch);check(optixDenoiserSetup(denoiser,stream,w,h,denoiserState,denoiserStateBytes,denoiserScratch,denoiserScratchBytes));
  for(int bank=0;bank<2;bank++){internalGuide[bank]=alloc((size_t)w*h*sizes.internalGuideLayerPixelSizeInBytes);owned.push_back(internalGuide[bank]);check(d.cuMemsetD8Async(internalGuide[bank],0,(size_t)w*h*sizes.internalGuideLayerPixelSizeInBytes,stream));for(int signal=0;signal<3;signal++){denoised[bank][signal]=alloc((size_t)w*h*16);owned.push_back(denoised[bank][signal]);check(d.cuMemsetD8Async(denoised[bank][signal],0,(size_t)w*h*16,stream));}}
  auto buffer=[&](size_t bytes){auto p=alloc(bytes);owned.push_back(p);check(d.cuMemsetD8Async(p,0,bytes,stream));return p;};params.previousPosition=reinterpret_cast<float4*>(buffer((size_t)w*h*16));params.previousNormal=reinterpret_cast<float4*>(buffer((size_t)w*h*16));params.flow=reinterpret_cast<float*>(buffer((size_t)w*h*8));params.flowTrust=reinterpret_cast<float*>(buffer((size_t)w*h*4));
 }
 void denoise(){int write=1-denoiserRead;OptixDenoiserGuideLayer guides{};guides.albedo=image(imports[2]);guides.normal=image(imports[1]);guides.flow=image(reinterpret_cast<CUdeviceptr>(params.flow),OPTIX_PIXEL_FORMAT_FLOAT2,8);guides.flowTrustworthiness=image(reinterpret_cast<CUdeviceptr>(params.flowTrust),OPTIX_PIXEL_FORMAT_FLOAT1,4);guides.previousOutputInternalGuideLayer=image(internalGuide[denoiserRead],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,internalGuideStride);guides.outputInternalGuideLayer=image(internalGuide[write],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,internalGuideStride);
  OptixDenoiserLayer layers[3]{};OptixDenoiserAOVType types[3]={OPTIX_DENOISER_AOV_TYPE_DIFFUSE,OPTIX_DENOISER_AOV_TYPE_REFLECTION,OPTIX_DENOISER_AOV_TYPE_REFRACTION};for(int i=0;i<3;i++){layers[i].input=image(imports[4+i]);layers[i].previousOutput=image(denoised[denoiserRead][i]);layers[i].output=image(denoised[write][i]);layers[i].type=types[i];}OptixDenoiserParams control{};control.temporalModeUsePreviousLayers=previousFrame;check(optixDenoiserInvoke(denoiser,stream,&control,denoiserState,denoiserStateBytes,&guides,layers,3,0,0,denoiserScratch,denoiserScratchBytes));for(int i=0;i<3;i++)check(d.cuMemcpyDtoDAsync(imports[4+i],denoised[write][i],(size_t)denoiserWidth*denoiserHeight*16,stream));denoiserRead=write;previousFrame=true;
 }
 std::vector<CUdeviceptr> owned;std::vector<CUexternalMemory> memories;std::vector<CUdeviceptr> imports;std::vector<CUexternalSemaphore> semaphores;
 struct Section {CUdeviceptr vertices=0,gasGpu=0;OptixTraversableHandle gas=0;int x,y,z,count;unsigned long long version;};
 std::map<long long,Section> sections;
 std::vector<std::array<int,6>> invalidations;
 struct Model {CUdeviceptr vertices=0,gasGpu=0;OptixTraversableHandle gas=0;size_t gasBytes=0;int count=0;};
 struct Instance {long long model;int textureSlot;std::array<float,12> transform;};
 std::map<long long,Model> models;std::map<long long,Instance> dynamicInstances;size_t iasCapacity=0;int iasCount=0;
 void upload(CUdeviceptr pointer,const void* bytes,size_t size){void* host;check(api.cuMemHostAlloc(&host,size,CU_MEMHOSTALLOC_PORTABLE));memcpy(host,bytes,size);check(api.cuMemcpyHtoDAsync(pointer,host,size,stream));CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));hostRetired.push_back({event,host});}
 bool benchmarkRequested=false;
 struct BenchmarkRead {CUevent event;float4* pointer;int representation;};std::vector<BenchmarkRead> benchmarkReads;float4* benchmarkReference=nullptr;long long benchmarkMismatches=-1;float benchmarkMaxError=0;
 void collectBenchmark(){for(auto i=benchmarkReads.begin();i!=benchmarkReads.end();){auto ready=api.cuEventQuery(i->event);if(ready==CUDA_ERROR_NOT_READY){++i;continue;}check(ready);api.cuEventDestroy(i->event);if(i->representation==0){benchmarkReference=i->pointer;}else{benchmarkMismatches=0;benchmarkMaxError=0;if(!benchmarkReference)throw std::runtime_error("Benchmark reference missing");for(int ray=0;ray<262144;ray++){float a=benchmarkReference[ray].x,b=i->pointer[ray].x;float error=fabsf(a-b);if((a<0)!=(b<0)||error>.001f)benchmarkMismatches++;benchmarkMaxError=std::max(benchmarkMaxError,error);}api.cuMemFreeHost(benchmarkReference);benchmarkReference=nullptr;api.cuMemFreeHost(i->pointer);}i=benchmarkReads.erase(i);}}

 struct Timing {int pass;CUevent begin,end;};std::vector<Timing> timings;std::vector<long long> measured;
 CUevent beginTiming(){if(timings.size()>=64)return nullptr;CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DEFAULT));check(api.cuEventRecord(event,stream));return event;}
 void endTiming(int pass,CUevent begin){if(!begin)return;CUevent end;check(api.cuEventCreate(&end,CU_EVENT_DEFAULT));check(api.cuEventRecord(end,stream));timings.push_back({pass,begin,end});}
 struct Retired {CUevent event;std::vector<CUdeviceptr> pointers;};std::vector<Retired> retired;
 struct HostRetired {CUevent event;void* pointer;};std::vector<HostRetired> hostRetired;
 struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) EmptyRecord {char header[OPTIX_SBT_RECORD_HEADER_SIZE];};
 struct alignas(OPTIX_SBT_RECORD_ALIGNMENT) HitRecord {char header[OPTIX_SBT_RECORD_HEADER_SIZE];RtHitData data;};
 std::map<CUdeviceptr,size_t> allocationBytes;size_t allocatedBytes=0;
 CUdeviceptr alloc(size_t n){if(n==0||n>512ull*1024*1024||allocatedBytes+n>512ull*1024*1024)throw std::runtime_error("RTX CUDA scene/denoiser 512 MiB budget exceeded");CUdeviceptr p;check(d.cuMemAlloc(&p,n));allocationBytes[p]=n;allocatedBytes+=n;return p;}
 void free(CUdeviceptr pointer){if(!pointer)return;auto found=allocationBytes.find(pointer);if(found!=allocationBytes.end()){allocatedBytes-=found->second;allocationBytes.erase(found);}d.cuMemFree(pointer);}
 void retire(std::vector<CUdeviceptr> pointers){pointers.erase(std::remove(pointers.begin(),pointers.end(),0),pointers.end());if(pointers.empty())return;CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));retired.push_back({event,std::move(pointers)});}
 void uploadParams(){
  void* host;check(api.cuMemHostAlloc(&host,sizeof(RtParams),CU_MEMHOSTALLOC_PORTABLE));memcpy(host,&params,sizeof(params));check(api.cuMemcpyHtoDAsync(paramsGpu,host,sizeof(params),stream));CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));hostRetired.push_back({event,host});
 }
 void collect(){collectBenchmark();for(auto i=timings.begin();i!=timings.end();){auto ready=api.cuEventQuery(i->end);if(ready==CUDA_SUCCESS){float ms;check(api.cuEventElapsedTime(&ms,i->begin,i->end));measured.push_back(i->pass);measured.push_back((long long)(ms*1000000));api.cuEventDestroy(i->begin);api.cuEventDestroy(i->end);i=timings.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}for(auto i=hostRetired.begin();i!=hostRetired.end();){auto ready=api.cuEventQuery(i->event);if(ready==CUDA_SUCCESS){api.cuMemFreeHost(i->pointer);api.cuEventDestroy(i->event);i=hostRetired.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}for(auto i=retired.begin();i!=retired.end();){auto ready=api.cuEventQuery(i->event);if(ready==CUDA_SUCCESS){for(auto p:i->pointers)free(p);api.cuEventDestroy(i->event);i=retired.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}}
 void init(const unsigned char* uuid,const char* code){
  check(d.cuInit(0));int count;check(d.cuDeviceGetCount(&count));for(int i=0;i<count;i++){CUdevice dev;CUuuid id;check(d.cuDeviceGet(&dev,i));check(d.cuDeviceGetUuid(&id,dev));if(!memcmp(uuid,id.bytes,16)){device=dev;break;}}
  if(device<0)throw std::runtime_error("RTX CUDA/Vulkan physical UUID mismatch");
  check(d.cuDevicePrimaryCtxRetain(&cuda,device));check(d.cuCtxSetCurrent(cuda));check(d.cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));check(optixInit());OptixDeviceContextOptions contextOptions{};check(optixDeviceContextCreate(cuda,&contextOptions,&optix));
  OptixModuleCompileOptions mo{};mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_LEVEL_3;
  OptixPipelineCompileOptions po{};po.usesMotionBlur=false;po.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING|OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;po.numPayloadValues=2;po.numAttributeValues=2;po.pipelineLaunchParamsVariableName="params";po.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE|OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;
  char log[8192];size_t logSize=sizeof(log);auto result=optixModuleCreate(optix,&mo,&po,code,strlen(code),log,&logSize,&module);if(result!=OPTIX_SUCCESS)throw std::runtime_error(std::string("RT module: ")+log);
  OptixProgramGroupDesc desc[4]{};desc[0].kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;desc[0].raygen.module=module;desc[0].raygen.entryFunctionName="__raygen__lighting";
  desc[1].kind=OPTIX_PROGRAM_GROUP_KIND_MISS;desc[1].miss.module=module;desc[1].miss.entryFunctionName="__miss__radiance";
  desc[2].kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;desc[2].hitgroup.moduleCH=module;desc[2].hitgroup.entryFunctionNameCH="__closesthit__surface";desc[2].hitgroup.moduleAH=module;desc[2].hitgroup.entryFunctionNameAH="__anyhit__surface";
  desc[3].kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;desc[3].hitgroup.moduleIS=module;desc[3].hitgroup.entryFunctionNameIS="__intersection__cube";desc[3].hitgroup.moduleCH=module;desc[3].hitgroup.entryFunctionNameCH="__closesthit__cube";
  OptixProgramGroupOptions go{};logSize=sizeof(log);check(optixProgramGroupCreate(optix,desc,4,&go,log,&logSize,groups));
  OptixPipelineLinkOptions link{};link.maxTraceDepth=1;logSize=sizeof(log);check(optixPipelineCreate(optix,&po,&link,groups,4,log,&logSize,&pipeline));
  OptixStackSizes sizes{};for(auto g:groups)check(optixUtilAccumulateStackSizes(g,&sizes,pipeline));unsigned fromTraversal,fromState,continuation;check(optixUtilComputeStackSizes(&sizes,1,0,0,&fromTraversal,&fromState,&continuation));check(optixPipelineSetStackSize(pipeline,fromTraversal,fromState,continuation,2));
  EmptyRecord record{};check(optixSbtRecordPackHeader(groups[0],&record));sbt.raygenRecord=alloc(sizeof(record));owned.push_back(sbt.raygenRecord);check(d.cuMemcpyHtoD(sbt.raygenRecord,&record,sizeof(record)));
  check(optixSbtRecordPackHeader(groups[1],&record));sbt.missRecordBase=alloc(sizeof(record));owned.push_back(sbt.missRecordBase);check(d.cuMemcpyHtoD(sbt.missRecordBase,&record,sizeof(record)));sbt.missRecordCount=1;sbt.missRecordStrideInBytes=sizeof(record);
  paramsGpu=alloc(sizeof(RtParams));owned.push_back(paramsGpu);probesGpu=alloc(1536*8*sizeof(float4));owned.push_back(probesGpu);check(d.cuMemsetD8Async(probesGpu,0,1536*8*sizeof(float4),stream));params.probes=reinterpret_cast<float4*>(probesGpu);
 }
 void importMemory(long long handle,size_t allocation,size_t bytes){check(d.cuCtxSetCurrent(cuda));CUDA_EXTERNAL_MEMORY_HANDLE_DESC desc{};
#ifdef _WIN32
  desc.type=CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32;desc.handle.win32.handle=reinterpret_cast<void*>(handle);
#else
  desc.type=CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD;desc.handle.fd=(int)handle;
#endif
  desc.size=allocation;desc.flags=CUDA_EXTERNAL_MEMORY_DEDICATED;CUexternalMemory memory=nullptr;auto result=api.cuImportExternalMemory(&memory,&desc);
#ifdef _WIN32
  CloseHandle(reinterpret_cast<HANDLE>(handle));
#else
  if(result!=CUDA_SUCCESS)close((int)handle);
#endif
  check(result);memories.push_back(memory);CUDA_EXTERNAL_MEMORY_BUFFER_DESC view{};view.size=bytes;CUdeviceptr pointer;check(api.cuExternalMemoryGetMappedBuffer(&pointer,memory,&view));imports.push_back(pointer);
 }
 void importSemaphore(long long handle){CUDA_EXTERNAL_SEMAPHORE_HANDLE_DESC desc{};
#ifdef _WIN32
  desc.type=CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32;desc.handle.win32.handle=reinterpret_cast<void*>(handle);
#else
  desc.type=CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD;desc.handle.fd=(int)handle;
#endif
  CUexternalSemaphore semaphore=nullptr;auto result=api.cuImportExternalSemaphore(&semaphore,&desc);
#ifdef _WIN32
  CloseHandle(reinterpret_cast<HANDLE>(handle));
#else
  if(result!=CUDA_SUCCESS)close((int)handle);
#endif
  check(result);semaphores.push_back(semaphore);
 }
 void update(const unsigned char* bytes,size_t size){check(d.cuCtxSetCurrent(cuda));collect();auto buildTimer=beginTiming();bool dirty=false;size_t offset=0;
  while(offset<size){if(size-offset<32)throw std::runtime_error("Truncated section batch");long long id;int x,y,z,count;unsigned long long version;memcpy(&id,bytes+offset,8);memcpy(&x,bytes+offset+8,4);memcpy(&y,bytes+offset+12,4);memcpy(&z,bytes+offset+16,4);memcpy(&count,bytes+offset+20,4);memcpy(&version,bytes+offset+24,8);offset+=32;
   if(count<0||count>600000||count%3||size-offset<(size_t)count*sizeof(RtVertex))throw std::runtime_error("Invalid RT section geometry");auto old=sections.find(id);
   if(old!=sections.end()&&old->second.version==version&&count>0){offset+=(size_t)count*sizeof(RtVertex);continue;}
   if(old!=sections.end()){retire({old->second.vertices,old->second.gasGpu});sections.erase(old);}
   if(count){if(sections.size()>=512)throw std::runtime_error("RT scene section budget exceeded");Section section{};section.x=x;section.y=y;section.z=z;section.count=count;section.version=version;
    section.vertices=alloc((size_t)count*sizeof(RtVertex));upload(section.vertices,bytes+offset,(size_t)count*sizeof(RtVertex));
    OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;auto& triangles=input.triangleArray;triangles.vertexBuffers=&section.vertices;triangles.numVertices=count;triangles.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;triangles.vertexStrideInBytes=sizeof(RtVertex);unsigned flags=OPTIX_GEOMETRY_FLAG_NONE;triangles.flags=&flags;triangles.numSbtRecords=1;
    OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));auto scratch=alloc(sizes.tempSizeInBytes);section.gasGpu=alloc(sizes.outputSizeInBytes);check(optixAccelBuild(optix,stream,&options,&input,1,scratch,sizes.tempSizeInBytes,section.gasGpu,sizes.outputSizeInBytes,&section.gas,nullptr,0));retire({scratch});sections.emplace(id,section);
   }offset+=(size_t)count*sizeof(RtVertex);dirty=true;invalidations.push_back({x,y,z,x+1,y+1,z+1});
  }
  if(dirty)rebuildScene();endTiming(0,buildTimer);
 }
 void rebuildScene(){
  retire({instancesGpu,sbtHit});instancesGpu=sbtHit=0;if(sections.empty()&&dynamicInstances.empty()){retire({iasGpu});iasGpu=0;ias=0;iasCapacity=0;iasCount=0;return;}
  std::vector<OptixInstance> instances;std::vector<HitRecord> hits;unsigned index=0;
  auto append=[&](OptixTraversableHandle gas,CUdeviceptr vertices,int slot,const float* matrix){OptixInstance instance{};memcpy(instance.transform,matrix,48);instance.instanceId=index;instance.sbtOffset=index++;instance.visibilityMask=255;instance.traversableHandle=gas;instances.push_back(instance);HitRecord hit{};check(optixSbtRecordPackHeader(groups[2],&hit));hit.data.vertices=reinterpret_cast<RtVertex*>(vertices);hit.data.textureSlot=slot;hits.push_back(hit);};
  for(auto& entry:sections){auto& section=entry.second;float transform[12]={1,0,0,section.x*16.f,0,1,0,section.y*16.f,0,0,1,section.z*16.f};append(section.gas,section.vertices,-1,transform);}
  for(auto& entry:dynamicInstances){auto& instance=entry.second;auto& model=models.at(instance.model);append(model.gas,model.vertices,instance.textureSlot,instance.transform.data());}
  instancesGpu=alloc(instances.size()*sizeof(OptixInstance));upload(instancesGpu,instances.data(),instances.size()*sizeof(OptixInstance));sbtHit=alloc(hits.size()*sizeof(HitRecord));upload(sbtHit,hits.data(),hits.size()*sizeof(HitRecord));sbt.hitgroupRecordBase=sbtHit;sbt.hitgroupRecordCount=hits.size();sbt.hitgroupRecordStrideInBytes=sizeof(HitRecord);
  OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instancesGpu;input.instanceArray.numInstances=instances.size();OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE|OPTIX_BUILD_FLAG_ALLOW_UPDATE;bool refit=iasGpu&&iasCount==(int)instances.size();options.operation=refit?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));if(iasCapacity<sizes.outputSizeInBytes){retire({iasGpu});iasGpu=alloc(sizes.outputSizeInBytes);iasCapacity=sizes.outputSizeInBytes;refit=false;options.operation=OPTIX_BUILD_OPERATION_BUILD;}auto scratchBytes=refit?sizes.tempUpdateSizeInBytes:sizes.tempSizeInBytes;auto scratch=alloc(scratchBytes);check(optixAccelBuild(optix,stream,&options,&input,1,scratch,scratchBytes,iasGpu,iasCapacity,&ias,nullptr,0));retire({scratch});iasCount=instances.size();
 }
 void updateDynamic(const unsigned char* bytes,size_t size){
  check(d.cuCtxSetCurrent(cuda));collect();std::map<long long,Instance> next;std::map<long long,int> references;for(auto& instance:dynamicInstances)references[instance.second.model]++;size_t offset=0;bool dirty=false;auto timer=beginTiming();
  while(offset<size){if(size-offset<72)throw std::runtime_error("Truncated RT instance batch");long long id,version;int count,slot;memcpy(&id,bytes+offset,8);memcpy(&version,bytes+offset+8,8);memcpy(&count,bytes+offset+16,4);memcpy(&slot,bytes+offset+20,4);Instance instance{};instance.model=version;instance.textureSlot=slot;memcpy(instance.transform.data(),bytes+offset+24,48);offset+=72;if(count<0||count>24576||count%3||slot<0||slot>=64||size-offset<(size_t)count*sizeof(RtVertex)||next.size()>=64)throw std::runtime_error("Invalid RT instance geometry");
   for(float value:instance.transform)if(!std::isfinite(value))throw std::runtime_error("Invalid RT transform");if(count==0)throw std::runtime_error("Empty RT instance");auto existing=models.find(version);if(existing!=models.end()&&existing->second.count!=count)throw std::runtime_error("RT model version collision");if(existing==models.end()){
    Model model{};auto prior=dynamicInstances.find(id);bool refit=false;if(prior!=dynamicInstances.end()&&references[prior->second.model]==1&&std::none_of(next.begin(),next.end(),[&](const auto& entry){return entry.second.model==prior->second.model;})){auto old=models.find(prior->second.model);if(old!=models.end()&&old->second.count==count){model=old->second;models.erase(old);refit=true;}}
    if(!refit){model.count=count;model.vertices=alloc((size_t)count*sizeof(RtVertex));}upload(model.vertices,bytes+offset,(size_t)count*sizeof(RtVertex));
    OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;auto& a=input.triangleArray;a.vertexBuffers=&model.vertices;a.numVertices=count;a.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;a.vertexStrideInBytes=sizeof(RtVertex);unsigned flags=OPTIX_GEOMETRY_FLAG_NONE;a.flags=&flags;a.numSbtRecords=1;OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_ALLOW_UPDATE|OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=refit?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));if(!refit){model.gasBytes=sizes.outputSizeInBytes;model.gasGpu=alloc(model.gasBytes);}auto scratchBytes=refit?sizes.tempUpdateSizeInBytes:sizes.tempSizeInBytes;auto scratch=alloc(scratchBytes);check(optixAccelBuild(optix,stream,&options,&input,1,scratch,scratchBytes,model.gasGpu,model.gasBytes,&model.gas,nullptr,0));retire({scratch});models.emplace(version,model);dirty=true;
   }
   auto prior=dynamicInstances.find(id);if(prior==dynamicInstances.end()||prior->second.model!=version||prior->second.textureSlot!=slot)dirty=true;else for(int j=0;j<12;j++)if(fabsf(prior->second.transform[j]-instance.transform[j])>.001f)dirty=true;
   next[id]=instance;offset+=(size_t)count*sizeof(RtVertex);
  }
  if(next.size()!=dynamicInstances.size())dirty=true;dynamicInstances=std::move(next);std::map<long long,int> used;for(auto& instance:dynamicInstances)used[instance.second.model]++;for(auto iterator=models.begin();iterator!=models.end();){if(!used[iterator->first]){retire({iterator->second.vertices,iterator->second.gasGpu});iterator=models.erase(iterator);}else ++iterator;}if(dirty)rebuildScene();endTiming(0,timer);
 }
 void benchmark(){
  if(!benchmarkReads.empty())return;benchmarkMismatches=-1;
  std::vector<RtCube> cubes;std::vector<RtVertex> vertices;const int face[6][4]={{0,4,6,2},{1,3,7,5},{0,1,5,4},{2,6,7,3},{0,2,3,1},{4,5,7,6}};const int indices[6]={0,1,2,2,3,0};
  for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++){float3 lo=make_float3(x*1.25f,y*1.25f,z*1.25f),hi=make_float3(lo.x+1,lo.y+1,lo.z+1);cubes.push_back({lo,hi});for(int f=0;f<6;f++)for(int index:indices){int corner=face[f][index];RtVertex vertex{};vertex.p=make_float3((corner&1)?hi.x:lo.x,(corner&2)?hi.y:lo.y,(corner&4)?hi.z:lo.z);vertex.tint=0xffffffff;vertices.push_back(vertex);}}
  std::vector<CUdeviceptr> garbage;auto copy=[&](const void* data,size_t bytes){auto pointer=alloc(bytes);garbage.push_back(pointer);check(d.cuMemcpyHtoD(pointer,data,bytes));return pointer;};auto triangleVertices=copy(vertices.data(),vertices.size()*sizeof(RtVertex)),cubeVertices=copy(cubes.data(),cubes.size()*sizeof(RtCube));auto saved=params;auto savedSbt=sbt;
  unsigned flags=OPTIX_GEOMETRY_FLAG_NONE;for(int representation=0;representation<2;representation++){OptixBuildInput input{};if(representation==0){input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;auto& a=input.triangleArray;a.vertexBuffers=&triangleVertices;a.numVertices=vertices.size();a.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;a.vertexStrideInBytes=sizeof(RtVertex);a.flags=&flags;a.numSbtRecords=1;}else{input.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;auto& a=input.customPrimitiveArray;a.aabbBuffers=&cubeVertices;a.numPrimitives=cubes.size();a.strideInBytes=sizeof(RtCube);a.flags=&flags;a.numSbtRecords=1;}
   OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));auto scratch=alloc(sizes.tempSizeInBytes),gas=alloc(sizes.outputSizeInBytes);garbage.push_back(scratch);garbage.push_back(gas);OptixTraversableHandle handle;check(optixAccelBuild(optix,stream,&options,&input,1,scratch,sizes.tempSizeInBytes,gas,sizes.outputSizeInBytes,&handle,nullptr,0));HitRecord record{};check(optixSbtRecordPackHeader(groups[representation==0?2:3],&record));record.data.vertices=reinterpret_cast<RtVertex*>(representation==0?triangleVertices:cubeVertices);record.data.textureSlot=-1;sbt.hitgroupRecordBase=copy(&record,sizeof(record));sbt.hitgroupRecordCount=1;sbt.hitgroupRecordStrideInBytes=sizeof(record);auto result=alloc(262144*16);garbage.push_back(result);params.scene=handle;params.mode=7;params.diffuse=reinterpret_cast<float4*>(result);
   for(int run=0;run<10;run++){auto timer=beginTiming();uploadParams();check(optixLaunch(pipeline,stream,paramsGpu,sizeof(params),&sbt,262144,1,1));endTiming(6+representation,timer);}
   void* host;check(api.cuMemHostAlloc(&host,262144*16,CU_MEMHOSTALLOC_PORTABLE));check(api.cuMemcpyDtoHAsync(host,result,262144*16,stream));CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));benchmarkReads.push_back({event,reinterpret_cast<float4*>(host),representation});
  }params=saved;sbt=savedSbt;retire(std::move(garbage));benchmarkRequested=false;
 }
 void render(const float* settings,int width,int height,int aw,int ah,int iw,int ih,unsigned options,unsigned debug){
  check(d.cuCtxSetCurrent(cuda));collect();prepareDenoiser(width,height);if(imports.size()!=13||semaphores.size()!=2||!ias)throw std::runtime_error("RTX resources/scene not ready");
  CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};check(api.cuWaitExternalSemaphoresAsync(&semaphores[0],&wait,1,stream));
  auto floatPointer=[&](int i){return reinterpret_cast<float4*>(imports[i]);};params.position=floatPointer(0);params.normal=floatPointer(1);params.albedo=floatPointer(2);params.material=floatPointer(3);params.diffuse=floatPointer(4);params.specular=floatPointer(5);params.transmission=floatPointer(6);params.sunVisibility=floatPointer(11);
  params.atlas=reinterpret_cast<unsigned*>(imports[7]);params.ids=reinterpret_cast<unsigned*>(imports[8]);params.lut=reinterpret_cast<unsigned*>(imports[9]);params.normalMap=reinterpret_cast<unsigned*>(imports[10]);params.entityAtlas=reinterpret_cast<unsigned*>(imports[12]);params.scene=ias;
  params.width=width;params.height=height;params.atlasWidth=aw;params.atlasHeight=ah;params.idsWidth=iw;params.idsHeight=ih;if((previousOptions&31)!=(options&31))previousFrame=false;previousOptions=options;params.options=options;params.debug=debug;params.camera=make_float3(settings[0],settings[1],settings[2]);params.sun=make_float3(settings[3],settings[4],settings[5]);params.sunColor=make_float3(settings[6],settings[7],settings[8]);params.sky=make_float3(settings[9],settings[10],settings[11]);memcpy(params.previousClip,previousClip,sizeof(previousClip));params.previousCamera=previousCamera;memcpy(previousClip,settings+12,sizeof(previousClip));previousCamera=params.camera;params.time=settings[28];params.waveStrength=settings[29];params.cloudWind=settings[30];params.cloudAltitude=settings[31];params.rain=settings[32];params.cloudShadows=settings[33];params.rainRipples=settings[34];params.frame++;
  for(auto section:invalidations){params.mode=2;params.invalidateMin=make_float3(section[0]*16.f,section[1]*16.f,section[2]*16.f);params.invalidateMax=make_float3(section[3]*16.f,section[4]*16.f,section[5]*16.f);uploadParams();check(optixLaunch(pipeline,stream,paramsGpu,sizeof(params),&sbt,1536,1,1));}invalidations.clear();params.mode=1;
  if(benchmarkRequested)benchmark();
  if(options&16){auto cacheTimer=beginTiming();uploadParams();check(optixLaunch(pipeline,stream,paramsGpu,sizeof(params),&sbt,1536,1,1));endTiming(1,cacheTimer);}
  for(int signal=0;signal<3;signal++){auto timer=beginTiming();params.mode=signal==0?0:signal+4;uploadParams();check(optixLaunch(pipeline,stream,paramsGpu,sizeof(params),&sbt,width*height,1,1));endTiming(2+signal,timer);}
  auto denoiseTimer=beginTiming();if((options&8)&&debug==0)denoise();else previousFrame=false;endTiming(5,denoiseTimer);params.mode=4;uploadParams();check(optixLaunch(pipeline,stream,paramsGpu,sizeof(params),&sbt,width*height,1,1));
  CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};check(api.cuSignalExternalSemaphoresAsync(&semaphores[1],&signal,1,stream));
 }
 ~RtContext(){if(!cuda)return;d.cuCtxSetCurrent(cuda);if(stream)d.cuStreamSynchronize(stream);for(auto& read:benchmarkReads){api.cuEventDestroy(read.event);api.cuMemFreeHost(read.pointer);}if(benchmarkReference)api.cuMemFreeHost(benchmarkReference);for(auto& t:timings){api.cuEventDestroy(t.begin);api.cuEventDestroy(t.end);}for(auto& h:hostRetired){api.cuMemFreeHost(h.pointer);api.cuEventDestroy(h.event);}for(auto s:semaphores)api.cuDestroyExternalSemaphore(s);for(auto p:imports)free(p);for(auto m:memories)api.cuDestroyExternalMemory(m);for(auto& r:retired){for(auto p:r.pointers)free(p);api.cuEventDestroy(r.event);}for(auto& e:sections){free(e.second.vertices);free(e.second.gasGpu);}for(auto& entry:models){free(entry.second.vertices);free(entry.second.gasGpu);}for(auto p:owned)free(p);for(auto p:{iasGpu,instancesGpu,sbtHit})if(p)free(p);while(!allocationBytes.empty())free(allocationBytes.begin()->first);if(denoiser)optixDenoiserDestroy(denoiser);if(pipeline)optixPipelineDestroy(pipeline);for(auto g:groups)if(g)optixProgramGroupDestroy(g);if(module)optixModuleDestroy(module);if(optix)optixDeviceContextDestroy(optix);if(stream)d.cuStreamDestroy(stream);d.cuDevicePrimaryCtxRelease(device);}
};
EXPORT jlong JNICALL Java_com_voxellight_nvidia_OptixNative_create(JNIEnv* e,jclass,jbyteArray id,jbyteArray code){RtContext* c=nullptr;try{if(e->GetArrayLength(id)!=16)throw std::runtime_error("Invalid UUID");unsigned char uuid[16];e->GetByteArrayRegion(id,0,16,reinterpret_cast<jbyte*>(uuid));int size=e->GetArrayLength(code);std::vector<char> ptx(size+1);e->GetByteArrayRegion(code,0,size,reinterpret_cast<jbyte*>(ptx.data()));c=new RtContext;c->init(uuid,ptx.data());return reinterpret_cast<jlong>(c);}catch(const std::exception& ex){delete c;fail(e,ex);return 0;}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_importBuffer(JNIEnv* e,jclass,jlong h,jlong external,jlong allocation,jlong size){try{reinterpret_cast<RtContext*>(h)->importMemory(external,allocation,size);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_importSemaphore(JNIEnv* e,jclass,jlong h,jlong external){try{reinterpret_cast<RtContext*>(h)->importSemaphore(external);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_updateSections(JNIEnv* e,jclass,jlong h,jobject batch){try{size_t size=e->GetDirectBufferCapacity(batch);reinterpret_cast<RtContext*>(h)->update(reinterpret_cast<unsigned char*>(buffer(e,batch,size)),size);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_render(JNIEnv* e,jclass,jlong h,jobject settings,jint w,jint height,jint aw,jint ah,jint iw,jint ih,jint options,jint debug){try{reinterpret_cast<RtContext*>(h)->render(reinterpret_cast<float*>(buffer(e,settings,140)),w,height,aw,ah,iw,ih,options,debug);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_destroy(JNIEnv*,jclass,jlong h){delete reinterpret_cast<RtContext*>(h);}

EXPORT jlongArray JNICALL Java_com_voxellight_nvidia_OptixNative_timings(JNIEnv* e,jclass,jlong handle){try{auto c=reinterpret_cast<RtContext*>(handle);c->collect();auto result=e->NewLongArray(c->measured.size());if(result)e->SetLongArrayRegion(result,0,c->measured.size(),reinterpret_cast<const jlong*>(c->measured.data()));c->measured.clear();return result;}catch(const std::exception& ex){fail(e,ex);return nullptr;}}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_invalidate(JNIEnv* e,jclass,jlong handle,jobject regions){try{auto c=reinterpret_cast<RtContext*>(handle);auto size=e->GetDirectBufferCapacity(regions);if(size<0||size%24||size>256*24)throw std::runtime_error("Invalid cache invalidation batch");auto bytes=reinterpret_cast<unsigned char*>(buffer(e,regions,size));for(int offset=0;offset<size;offset+=24){std::array<int,6> region;memcpy(region.data(),bytes+offset,24);c->invalidations.push_back(region);}}catch(const std::exception& ex){fail(e,ex);}}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_benchmark(JNIEnv*,jclass,jlong handle){reinterpret_cast<RtContext*>(handle)->benchmarkRequested=true;}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_updateInstances(JNIEnv* e,jclass,jlong handle,jobject batch){try{size_t size=e->GetDirectBufferCapacity(batch);reinterpret_cast<RtContext*>(handle)->updateDynamic(size?reinterpret_cast<unsigned char*>(buffer(e,batch,size)):nullptr,size);}catch(const std::exception& ex){fail(e,ex);}}

EXPORT jlongArray JNICALL Java_com_voxellight_nvidia_OptixNative_stats(JNIEnv* e,jclass,jlong h){auto c=reinterpret_cast<RtContext*>(h);jlong values[]={static_cast<jlong>(c->sections.size()),static_cast<jlong>(c->models.size()),static_cast<jlong>(c->dynamicInstances.size()),static_cast<jlong>(c->allocatedBytes),static_cast<jlong>(c->iasCapacity),static_cast<jlong>(c->retired.size()),c->benchmarkMismatches,static_cast<jlong>(c->benchmarkMaxError*1000000)};auto result=e->NewLongArray(8);e->SetLongArrayRegion(result,0,8,values);return result;}
