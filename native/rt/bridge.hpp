#include <cstdlib>
#include <cstdio>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>
#include "compiler_log.hpp"
// Included after the shared CUDA driver loader in bridge.cpp. No Vulkan loader or SDK redistribution.
#include <map>
#include <atomic>
#include "task_pool.h"
static std::atomic<int> rtInitializationStage{0};
static std::atomic<unsigned> rtCompileFinished{0},rtCompileScheduled{0};
#include <cmath>
#include <array>
#include <memory>
#include <optix_stack_size.h>
#include "contract.h"
#include "environment.h"
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
 OptixDeviceContext optix=nullptr;OptixModule module=nullptr,hitModule=nullptr,causticModule=nullptr;OptixPipeline pipeline=nullptr,causticPipeline=nullptr;OptixModule signalModules[3]{},callableModules[3]{};OptixProgramGroup callableGroups[6]{};OptixProgramGroup groups[4]{},signalGroups[4]{},causticGroup=nullptr;CUdeviceptr signalRecords[4]{};CUmodule utilityModule=nullptr;CUfunction utility=nullptr;CUdeviceptr causticRecord=0;
 OptixShaderBindingTable sbt{};CUdeviceptr paramsGpu=0,iasGpu=0,instancesGpu=0,sbtHit=0,probesGpu=0;
 CUdeviceptr lightsGpu=0;std::vector<RtAreaLight> areaLights;
 OptixTraversableHandle ias=0;RtParams params{};CUevent referenceReady=nullptr;
 bool referenceFinished(){if(!referenceReady)return true;check(d.cuCtxSetCurrent(cuda));auto ready=api.cuEventQuery(referenceReady);if(ready==CUDA_ERROR_NOT_READY)return false;check(ready);api.cuEventDestroy(referenceReady);referenceReady=nullptr;return true;}
 rt::ReferenceSweep referenceSweep;bool referenceClear=true;unsigned referencePixels=rt::ReferenceInitialPixels;float referenceMilliseconds=0;CUdeviceptr referenceGuides[4]{},referenceEnvironment=0;
 void resetReference(){params.referenceSamples=0;referenceSweep.reset();referenceClear=true;previousFrame=false;}
 OptixDenoiser denoiser=nullptr;CUdeviceptr denoiserState=0,denoiserScratch=0,internalGuide[2]{},denoised[2][3]{};size_t denoiserStateBytes=0,denoiserScratchBytes=0;int internalGuideStride=0,denoiserWidth=0,denoiserHeight=0,denoiserRead=0;unsigned previousOptions=0;bool previousFrame=false;float previousClip[16]{};float3 previousCamera{};
 OptixImage2D image(CUdeviceptr pointer,OptixPixelFormat format=OPTIX_PIXEL_FORMAT_FLOAT4,int stride=16){OptixImage2D image{};image.data=pointer;image.width=denoiserWidth;image.height=denoiserHeight;image.pixelStrideInBytes=stride;image.rowStrideInBytes=denoiserWidth*stride;image.format=format;return image;}
 void prepareDenoiser(int w,int h,bool full){if(denoiserWidth)return;denoiserWidth=w;denoiserHeight=h;if(!full){OptixDenoiserOptions options{};options.guideAlbedo=1;options.guideNormal=1;options.denoiseAlpha=OPTIX_DENOISER_ALPHA_MODE_COPY;check(optixDenoiserCreate(optix,OPTIX_DENOISER_MODEL_KIND_TEMPORAL_AOV,&options,&denoiser));OptixDenoiserSizes sizes{};check(optixDenoiserComputeMemoryResources(denoiser,w,h,&sizes));internalGuideStride=sizes.internalGuideLayerPixelSizeInBytes;denoiserStateBytes=sizes.stateSizeInBytes;denoiserScratchBytes=sizes.withoutOverlapScratchSizeInBytes;if(denoiserStateBytes+denoiserScratchBytes>192*1024*1024)throw std::runtime_error("RTX denoiser memory budget exceeded");denoiserState=alloc(denoiserStateBytes);owned.push_back(denoiserState);denoiserScratch=alloc(denoiserScratchBytes);owned.push_back(denoiserScratch);check(optixDenoiserSetup(denoiser,stream,w,h,denoiserState,denoiserStateBytes,denoiserScratch,denoiserScratchBytes));
  for(int bank=0;bank<2;bank++){internalGuide[bank]=alloc((size_t)w*h*sizes.internalGuideLayerPixelSizeInBytes);owned.push_back(internalGuide[bank]);check(d.cuMemsetD8Async(internalGuide[bank],0,(size_t)w*h*sizes.internalGuideLayerPixelSizeInBytes,stream));for(int signal=0;signal<3;signal++){denoised[bank][signal]=alloc((size_t)w*h*16);owned.push_back(denoised[bank][signal]);check(d.cuMemsetD8Async(denoised[bank][signal],0,(size_t)w*h*16,stream));}}
}
  auto buffer=[&](size_t bytes){auto p=alloc(bytes);owned.push_back(p);check(d.cuMemsetD8Async(p,0,bytes,stream));return p;};size_t guides=full?16:(size_t)w*h*16;params.previousPosition=reinterpret_cast<float4*>(buffer(guides));params.previousNormal=reinterpret_cast<float4*>(buffer(guides));params.flow=reinterpret_cast<float*>(buffer(full?16:(size_t)w*h*8));params.flowTrust=reinterpret_cast<float*>(buffer(full?16:(size_t)w*h*4));params.previousKey=reinterpret_cast<float4*>(buffer(guides));params.previousSignal=reinterpret_cast<float4*>(buffer(guides));params.referenceSum=reinterpret_cast<float4*>(buffer((size_t)w*h*16*(full?1:3)));for(auto& guide:referenceGuides)guide=buffer(guides);referenceEnvironment=buffer(rt::EnvironmentWidth*rt::EnvironmentHeight*16);params.environmentCdf=reinterpret_cast<float*>(buffer((rt::EnvironmentWidth*rt::EnvironmentHeight+rt::EnvironmentHeight)*4));params.caustics=reinterpret_cast<float4*>(buffer(4096*32));params.causticHistory=reinterpret_cast<float4*>(buffer(4096*32));params.counters=reinterpret_cast<unsigned*>(buffer((size_t)w*h*9*sizeof(unsigned)));params.counterTotals=reinterpret_cast<unsigned*>(buffer(36));check(api.cuMemHostAlloc(reinterpret_cast<void**>(&counterHost),36,CU_MEMHOSTALLOC_PORTABLE));
 }
 void denoise(){int write=1-denoiserRead;OptixDenoiserGuideLayer guides{};guides.albedo=image(imports[2]);guides.normal=image(imports[1]);guides.flow=image(reinterpret_cast<CUdeviceptr>(params.flow),OPTIX_PIXEL_FORMAT_FLOAT2,8);guides.flowTrustworthiness=image(reinterpret_cast<CUdeviceptr>(params.flowTrust),OPTIX_PIXEL_FORMAT_FLOAT1,4);guides.previousOutputInternalGuideLayer=image(internalGuide[denoiserRead],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,internalGuideStride);guides.outputInternalGuideLayer=image(internalGuide[write],OPTIX_PIXEL_FORMAT_INTERNAL_GUIDE_LAYER,internalGuideStride);
  OptixDenoiserLayer layers[3]{};OptixDenoiserAOVType types[3]={OPTIX_DENOISER_AOV_TYPE_DIFFUSE,OPTIX_DENOISER_AOV_TYPE_REFLECTION,OPTIX_DENOISER_AOV_TYPE_REFRACTION};for(int i=0;i<3;i++){layers[i].input=image(imports[4+i]);layers[i].previousOutput=image(denoised[denoiserRead][i]);layers[i].output=image(denoised[write][i]);layers[i].type=types[i];}OptixDenoiserParams control{};control.temporalModeUsePreviousLayers=previousFrame;check(optixDenoiserInvoke(denoiser,stream,&control,denoiserState,denoiserStateBytes,&guides,layers,3,0,0,denoiserScratch,denoiserScratchBytes));for(int i=0;i<3;i++)check(d.cuMemcpyDtoDAsync(imports[4+i],denoised[write][i],(size_t)denoiserWidth*denoiserHeight*16,stream));denoiserRead=write;previousFrame=true;
 }
 std::array<unsigned,9> operationStats{};unsigned* counterHost=nullptr;CUevent counterReady=nullptr;
 std::vector<CUdeviceptr> owned;std::vector<CUexternalMemory> memories;std::vector<CUdeviceptr> imports;std::vector<CUexternalSemaphore> semaphores;
 struct Section {CUdeviceptr vertices=0,gasGpu=0;OptixTraversableHandle gas=0;int x,y,z,count;unsigned long long version;std::vector<RtAreaLight> emitters;};
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
 CUdeviceptr alloc(size_t n){if(n==0||n>1536ull*1024*1024||allocatedBytes+n>((params.options&(1u<<25))?1536ull:512ull)*1024*1024)throw std::runtime_error("RTX CUDA scene/denoiser 512 MiB budget exceeded");CUdeviceptr p;check(d.cuMemAlloc(&p,n));allocationBytes[p]=n;allocatedBytes+=n;return p;}
 void free(CUdeviceptr pointer){if(!pointer)return;auto found=allocationBytes.find(pointer);if(found!=allocationBytes.end()){allocatedBytes-=found->second;allocationBytes.erase(found);}d.cuMemFree(pointer);}
 void retire(std::vector<CUdeviceptr> pointers){pointers.erase(std::remove(pointers.begin(),pointers.end(),0),pointers.end());if(pointers.empty())return;CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));retired.push_back({event,std::move(pointers)});}
 void uploadParams(){
  void* host;check(api.cuMemHostAlloc(&host,sizeof(RtParams),CU_MEMHOSTALLOC_PORTABLE));memcpy(host,&params,sizeof(params));check(api.cuMemcpyHtoDAsync(paramsGpu,host,sizeof(params),stream));CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));hostRetired.push_back({event,host});
 }
 void collect(){if(counterReady&&api.cuEventQuery(counterReady)==CUDA_SUCCESS){memcpy(operationStats.data(),counterHost,36);api.cuEventDestroy(counterReady);counterReady=nullptr;}collectBenchmark();for(auto i=timings.begin();i!=timings.end();){auto ready=api.cuEventQuery(i->end);if(ready==CUDA_SUCCESS){float ms;check(api.cuEventElapsedTime(&ms,i->begin,i->end));if(params.referenceSpp&&i->pass>=2&&i->pass<=4){if(i->pass==2)referenceMilliseconds=0;referenceMilliseconds+=ms;if(i->pass==4)referencePixels=rt::referenceBudget(referencePixels,(unsigned long long)(referenceMilliseconds*1000000));}measured.push_back(i->pass);measured.push_back((long long)(ms*1000000));api.cuEventDestroy(i->begin);api.cuEventDestroy(i->end);i=timings.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}for(auto i=hostRetired.begin();i!=hostRetired.end();){auto ready=api.cuEventQuery(i->event);if(ready==CUDA_SUCCESS){api.cuMemFreeHost(i->pointer);api.cuEventDestroy(i->event);i=hostRetired.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}for(auto i=retired.begin();i!=retired.end();){auto ready=api.cuEventQuery(i->event);if(ready==CUDA_SUCCESS){for(auto p:i->pointers)free(p);api.cuEventDestroy(i->event);i=retired.erase(i);}else if(ready==CUDA_ERROR_NOT_READY)++i;else check(ready);}}
 std::mutex diagnosticMutex;std::string compilerDiagnostics;
 static void compilerLog(unsigned level,const char* tag,const char* message,void* data){if(level>2&&(!tag||(!strstr(tag,"CACHE")&&!strstr(tag,"COMPIL")&&!strstr(tag,"PIPELINE"))))return;rtLog("VoxelLight OptiX [%u][%s] %s\n",level,tag?tag:"",message?message:"");if(level>2)return;auto* self=static_cast<RtContext*>(data);std::lock_guard<std::mutex> guard(self->diagnosticMutex);std::string line=std::string(tag?tag:"")+": "+(message?message:"")+"\n";constexpr size_t limit=1024*1024;if(line.size()>limit)line=line.substr(line.size()-limit);if(self->compilerDiagnostics.size()+line.size()>limit)self->compilerDiagnostics.erase(0,self->compilerDiagnostics.size()+line.size()-limit);self->compilerDiagnostics+=line;}
 std::string diagnostics(){std::lock_guard<std::mutex> guard(diagnosticMutex);return compilerDiagnostics;}
#include "compiler.hpp"
 void init(const unsigned char* uuid,const std::vector<std::vector<char>>& code,bool full,const char* cache){
  strictReference=full;
  rtInitializationStage=1;check(d.cuInit(0));int count;check(d.cuDeviceGetCount(&count));for(int i=0;i<count;i++){CUdevice dev;CUuuid id;check(d.cuDeviceGet(&dev,i));check(d.cuDeviceGetUuid(&id,dev));if(!memcmp(uuid,id.bytes,16)){device=dev;break;}}
  if(device<0)throw std::runtime_error("RTX CUDA/Vulkan physical UUID mismatch");
  check(d.cuDevicePrimaryCtxRetain(&cuda,device));check(d.cuCtxSetCurrent(cuda));check(d.cuStreamCreate(&stream,CU_STREAM_NON_BLOCKING));rtInitializationStage=2;check(optixInit());OptixDeviceContextOptions contextOptions{};contextOptions.logCallbackFunction=compilerLog;contextOptions.logCallbackData=this;contextOptions.logCallbackLevel=4;check(optixDeviceContextCreate(cuda,&contextOptions,&optix));
  auto getName=reinterpret_cast<decltype(&::cuDeviceGetName)>(d.symbol("cuDeviceGetName"));auto getVersion=reinterpret_cast<decltype(&::cuDriverGetVersion)>(d.symbol("cuDriverGetVersion"));char gpu[256]{};if(getName)check(getName(gpu,sizeof(gpu),device));gpuName=gpu;if(getVersion)check(getVersion(&driverVersion));readNvidiaDriver();configureCache(cache);
  auto po=pipelineOptions();std::vector<char> log(1024*1024);size_t logSize=log.size();rtCompileFinished=0;rtCompileScheduled=0;
  auto compilationCheck=[&](OptixResult result,const char* stage){if(result!=OPTIX_SUCCESS)throw std::runtime_error(std::string(stage)+": OptiX error "+std::to_string((int)result)+"; "+diagnostics()+"; "+log.data());};
  compileModule(hitModule,"rt_hit",code[0]);compileModule(module,full?"rt_reference":"rt_realtime",code[1]);
  if(!full){const char* names[]={"rt_specular","rt_transmission","rt_probes"};for(int i=0;i<3;i++)compileModule(signalModules[i],names[i],code[i+2]);compileModule(causticModule,"rt_caustics",code[5]);}
  compileModule(callableModules[0],full?"rt_reference_transport":"rt_transport",code[7]);compileModule(callableModules[1],"rt_visibility",code[8]);compileModule(callableModules[2],"rt_bsdf",code[9]);
  check(d.cuModuleLoadData(&utilityModule,code[6].data()));check(d.cuModuleGetFunction(&utility,utilityModule,"rtUtility"));
  OptixProgramGroupDesc desc[4]{};desc[0].kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;desc[0].raygen.module=module;desc[0].raygen.entryFunctionName=full?"__raygen__reference":"__raygen__diffuse";
  desc[1].kind=OPTIX_PROGRAM_GROUP_KIND_MISS;desc[1].miss.module=hitModule;desc[1].miss.entryFunctionName="__miss__radiance";
  desc[2].kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;desc[2].hitgroup.moduleCH=hitModule;desc[2].hitgroup.entryFunctionNameCH="__closesthit__surface";desc[2].hitgroup.moduleAH=hitModule;desc[2].hitgroup.entryFunctionNameAH="__anyhit__surface";
  desc[3].kind=OPTIX_PROGRAM_GROUP_KIND_HITGROUP;desc[3].hitgroup.moduleIS=hitModule;desc[3].hitgroup.entryFunctionNameIS="__intersection__cube";desc[3].hitgroup.moduleCH=hitModule;desc[3].hitgroup.entryFunctionNameCH="__closesthit__cube";
  auto groupStart=timestamp();rtInitializationStage=4;OptixProgramGroupOptions go{};logSize=log.size();compilationCheck(optixProgramGroupCreate(optix,desc,4,&go,log.data(),&logSize,groups),"RT program groups");
  rtLog("VoxelLight program groups profile=%s elapsed_ms=%lld\n",full?"reference":"realtime",timestamp()-groupStart);auto linkStart=timestamp();
  OptixProgramGroupDesc callables[6]{};const char* callableNames[]={"__continuation_callable__transport","__continuation_callable__visibility","__direct_callable__evaluate","__direct_callable__sample","__direct_callable__evaluate_glossy","__direct_callable__sample_glossy"};
  auto callableStart=timestamp();for(int i=0;i<6;i++){callables[i].kind=OPTIX_PROGRAM_GROUP_KIND_CALLABLES;if(i<2){callables[i].callables.moduleCC=callableModules[i];callables[i].callables.entryFunctionNameCC=callableNames[i];}else{callables[i].callables.moduleDC=callableModules[2];callables[i].callables.entryFunctionNameDC=callableNames[i];}}logSize=log.size();compilationCheck(optixProgramGroupCreate(optix,callables,6,&go,log.data(),&logSize,callableGroups),"Transport callable groups");rtLog("VoxelLight program groups profile=callables elapsed_ms=%lld\n",timestamp()-callableStart);
  std::vector<OptixProgramGroup> realtimeGroups(groups,groups+4);realtimeGroups.insert(realtimeGroups.end(),callableGroups,callableGroups+6);
  {OptixProgramGroupDesc signals[4]{};const char* entries[]={"__raygen__specular","__raygen__transmission","__raygen__probes","__raygen__benchmark"};auto began=timestamp();for(int i=full?3:0;i<4;i++){signals[i].kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;signals[i].raygen.module=i==3?hitModule:signalModules[i];signals[i].raygen.entryFunctionName=entries[i];}logSize=log.size();compilationCheck(optixProgramGroupCreate(optix,signals+(full?3:0),full?1:4,&go,log.data(),&logSize,signalGroups+(full?3:0)),"Realtime signal groups");rtLog("VoxelLight program groups profile=signals elapsed_ms=%lld\n",timestamp()-began);realtimeGroups.insert(realtimeGroups.end(),signalGroups+(full?3:0),signalGroups+4);}
  rtInitializationStage=5;OptixPipelineLinkOptions link{};link.maxTraceDepth=1;logSize=log.size();linkStart=timestamp();compilationCheck(optixPipelineCreate(optix,&po,&link,realtimeGroups.data(),realtimeGroups.size(),log.data(),&logSize,&pipeline),"RT pipeline link");
  rtLog("VoxelLight pipeline link profile=%s elapsed_ms=%lld\n",full?"reference":"realtime",timestamp()-linkStart);
  auto stack=[&](OptixPipeline target,const std::vector<OptixProgramGroup>& entries){OptixStackSizes sizes{};for(auto g:entries)check(optixUtilAccumulateStackSizes(g,&sizes,target));unsigned a,b,c;check(optixUtilComputeStackSizes(&sizes,1,2,1,&a,&b,&c));check(optixPipelineSetStackSize(target,a,b,c,2));};
  if(!full){auto began=timestamp();OptixProgramGroupDesc cg{};cg.kind=OPTIX_PROGRAM_GROUP_KIND_RAYGEN;cg.raygen.module=causticModule;cg.raygen.entryFunctionName="__raygen__caustics";logSize=log.size();compilationCheck(optixProgramGroupCreate(optix,&cg,1,&go,log.data(),&logSize,&causticGroup),"Caustic group");rtLog("VoxelLight program groups profile=caustics elapsed_ms=%lld\n",timestamp()-began);std::vector<OptixProgramGroup> entries={causticGroup,groups[1],groups[2],groups[3]};entries.insert(entries.end(),callableGroups,callableGroups+6);began=timestamp();logSize=log.size();compilationCheck(optixPipelineCreate(optix,&po,&link,entries.data(),entries.size(),log.data(),&logSize,&causticPipeline),"Caustic link");rtLog("VoxelLight pipeline link profile=caustics elapsed_ms=%lld\n",timestamp()-began);stack(causticPipeline,entries);}
  rtInitializationStage=6;stack(pipeline,realtimeGroups);
  auto pack=[&](OptixProgramGroup group){EmptyRecord record{};check(optixSbtRecordPackHeader(group,&record));auto pointer=alloc(sizeof(record));owned.push_back(pointer);check(d.cuMemcpyHtoD(pointer,&record,sizeof(record)));return pointer;};
  EmptyRecord callableRecords[6]{};for(int i=0;i<6;i++)check(optixSbtRecordPackHeader(callableGroups[i],&callableRecords[i]));sbt.callablesRecordBase=alloc(sizeof(callableRecords));owned.push_back(sbt.callablesRecordBase);check(d.cuMemcpyHtoD(sbt.callablesRecordBase,callableRecords,sizeof(callableRecords)));sbt.callablesRecordCount=6;sbt.callablesRecordStrideInBytes=sizeof(EmptyRecord);
  sbt.raygenRecord=pack(groups[0]);sbt.missRecordBase=pack(groups[1]);sbt.missRecordCount=1;sbt.missRecordStrideInBytes=sizeof(EmptyRecord);
  signalRecords[3]=pack(signalGroups[3]);if(!full){causticRecord=pack(causticGroup);for(int i=0;i<3;i++)signalRecords[i]=pack(signalGroups[i]);}
  paramsGpu=alloc(sizeof(RtParams));owned.push_back(paramsGpu);probesGpu=alloc(1536*8*sizeof(float4));owned.push_back(probesGpu);check(d.cuMemsetD8Async(probesGpu,0,1536*8*sizeof(float4),stream));params.probes=reinterpret_cast<float4*>(probesGpu);rtInitializationStage=7;
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
  while(offset<size){if(size-offset<32)throw std::runtime_error("Truncated section batch");long long id;int x,y,z,count;unsigned long long version;std::vector<RtAreaLight> emitters;memcpy(&id,bytes+offset,8);memcpy(&x,bytes+offset+8,4);memcpy(&y,bytes+offset+12,4);memcpy(&z,bytes+offset+16,4);memcpy(&count,bytes+offset+20,4);memcpy(&version,bytes+offset+24,8);offset+=32;
   if(count<0||count>600000||count%3||size-offset<(size_t)count*sizeof(RtVertex))throw std::runtime_error("Invalid RT section geometry");auto old=sections.find(id);
   if(old!=sections.end()&&old->second.version==version&&count>0){offset+=(size_t)count*sizeof(RtVertex);continue;}
   if(old!=sections.end()){retire({old->second.vertices,old->second.gasGpu});sections.erase(old);}
   if(count){if(sections.size()>=512)throw std::runtime_error("RT scene section budget exceeded");Section section{};section.x=x;section.y=y;section.z=z;section.count=count;section.version=version;
    auto vertices=reinterpret_cast<const RtVertex*>(bytes+offset);for(int t=0;t<count;t+=3){auto a=vertices[t],b=vertices[t+1],c=vertices[t+2];if(!(a.flags>>16)&&!(a.flags&32))continue;float ux=b.p.x-a.p.x,uy=b.p.y-a.p.y,uz=b.p.z-a.p.z,vx=c.p.x-a.p.x,vy=c.p.y-a.p.y,vz=c.p.z-a.p.z;float area=.5f*sqrtf(powf(uy*vz-uz*vy,2)+powf(uz*vx-ux*vz,2)+powf(ux*vy-uy*vx,2));if(area<=1e-8f)continue;section.emitters.push_back({a,b,c,area,0,(unsigned)(t/3)});}

    section.vertices=alloc((size_t)count*sizeof(RtVertex));upload(section.vertices,bytes+offset,(size_t)count*sizeof(RtVertex));
    OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;auto& triangles=input.triangleArray;triangles.vertexBuffers=&section.vertices;triangles.numVertices=count;triangles.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;triangles.vertexStrideInBytes=sizeof(RtVertex);unsigned flags=OPTIX_GEOMETRY_FLAG_NONE;triangles.flags=&flags;triangles.numSbtRecords=1;
    OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;options.operation=OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));auto scratch=alloc(sizes.tempSizeInBytes);section.gasGpu=alloc(sizes.outputSizeInBytes);check(optixAccelBuild(optix,stream,&options,&input,1,scratch,sizes.tempSizeInBytes,section.gasGpu,sizes.outputSizeInBytes,&section.gas,nullptr,0));retire({scratch});sections.emplace(id,section);
   }offset+=(size_t)count*sizeof(RtVertex);dirty=true;invalidations.push_back({x,y,z,x+1,y+1,z+1});
  }
  if(dirty){rebuildScene();resetReference();}endTiming(0,buildTimer);
 }
 void rebuildScene(){
  retire({instancesGpu,sbtHit});instancesGpu=sbtHit=0;if(sections.empty()&&dynamicInstances.empty()){retire({iasGpu});iasGpu=0;ias=0;iasCapacity=0;iasCount=0;return;}
  std::vector<OptixInstance> instances;std::vector<HitRecord> hits;unsigned index=0;areaLights.clear();std::vector<rt::LightCluster> clusters;float lightPower=0;
  auto append=[&](OptixTraversableHandle gas,CUdeviceptr vertices,int slot,const float* matrix){OptixInstance instance{};memcpy(instance.transform,matrix,48);instance.instanceId=index;instance.sbtOffset=index++;instance.visibilityMask=255;instance.traversableHandle=gas;instances.push_back(instance);HitRecord hit{};check(optixSbtRecordPackHeader(groups[2],&hit));hit.data.vertices=reinterpret_cast<RtVertex*>(vertices);hit.data.textureSlot=slot;hits.push_back(hit);};
  for(auto& entry:sections){auto& section=entry.second;float transform[12]={1,0,0,section.x*16.f,0,1,0,section.y*16.f,0,0,1,section.z*16.f};unsigned begin=areaLights.size(),identity=index;append(section.gas,section.vertices,-1,transform);
   for(auto light:section.emitters){for(auto vertex:{&light.a,&light.b,&light.c}){vertex->p.x+=section.x*16;vertex->p.y+=section.y*16;vertex->p.z+=section.z*16;}light.identity=identity*600001u+light.identity;lightPower+=light.area*fmaxf((light.a.flags>>16)/15.f,(light.a.flags&32)?1.f:0.f)*2.4f;light.cdf=lightPower;areaLights.push_back(light);}if(areaLights.size()>begin)clusters.push_back({begin,(unsigned)areaLights.size(),lightPower});}
  for(auto& entry:dynamicInstances){auto& instance=entry.second;auto& model=models.at(instance.model);append(model.gas,model.vertices,instance.textureSlot,instance.transform.data());}
  retire({lightsGpu});lightsGpu=0;params.lightCount=areaLights.size();params.lightPower=lightPower;if(!areaLights.empty()){lightsGpu=alloc(areaLights.size()*sizeof(RtAreaLight));upload(lightsGpu,areaLights.data(),areaLights.size()*sizeof(RtAreaLight));}params.lights=reinterpret_cast<RtAreaLight*>(lightsGpu);retire({reinterpret_cast<CUdeviceptr>(params.lightClusters)});params.lightClusters=nullptr;params.lightClusterCount=clusters.size();if(!clusters.empty()){auto pointer=alloc(clusters.size()*sizeof(rt::LightCluster));upload(pointer,clusters.data(),clusters.size()*sizeof(rt::LightCluster));params.lightClusters=reinterpret_cast<rt::LightCluster*>(pointer);}
  instancesGpu=alloc(instances.size()*sizeof(OptixInstance));upload(instancesGpu,instances.data(),instances.size()*sizeof(OptixInstance));sbtHit=alloc(hits.size()*sizeof(HitRecord));upload(sbtHit,hits.data(),hits.size()*sizeof(HitRecord));sbt.hitgroupRecordBase=sbtHit;sbt.hitgroupRecordCount=hits.size();sbt.hitgroupRecordStrideInBytes=sizeof(HitRecord);
  OptixBuildInput input{};input.type=OPTIX_BUILD_INPUT_TYPE_INSTANCES;input.instanceArray.instances=instancesGpu;input.instanceArray.numInstances=instances.size();OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE|OPTIX_BUILD_FLAG_ALLOW_UPDATE;bool refit=iasGpu&&iasCount==(int)instances.size();options.operation=refit?OPTIX_BUILD_OPERATION_UPDATE:OPTIX_BUILD_OPERATION_BUILD;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));if(iasCapacity<sizes.outputSizeInBytes){retire({iasGpu});iasGpu=alloc(sizes.outputSizeInBytes);iasCapacity=sizes.outputSizeInBytes;refit=false;options.operation=OPTIX_BUILD_OPERATION_BUILD;}auto scratchBytes=refit?sizes.tempUpdateSizeInBytes:sizes.tempSizeInBytes;auto scratch=alloc(scratchBytes);check(optixAccelBuild(optix,stream,&options,&input,1,scratch,scratchBytes,iasGpu,iasCapacity,&ias,nullptr,0));retire({scratch});iasCount=instances.size();
 }
 void updateDynamic(const unsigned char* bytes,size_t size){
  // A converged reference is a frozen scene snapshot; animation must not reset every frame.
  if(params.referenceSpp&&!referenceClear)return;
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
  if(next.size()!=dynamicInstances.size())dirty=true;dynamicInstances=std::move(next);std::map<long long,int> used;for(auto& instance:dynamicInstances)used[instance.second.model]++;for(auto iterator=models.begin();iterator!=models.end();){if(!used[iterator->first]){retire({iterator->second.vertices,iterator->second.gasGpu});iterator=models.erase(iterator);}else ++iterator;}if(dirty){rebuildScene();resetReference();}endTiming(0,timer);
 }
 void benchmark(){
  if(!benchmarkReads.empty())return;benchmarkMismatches=-1;
  std::vector<RtCube> cubes;std::vector<RtVertex> vertices;const int face[6][4]={{0,4,6,2},{1,3,7,5},{0,1,5,4},{2,6,7,3},{0,2,3,1},{4,5,7,6}};const int indices[6]={0,1,2,2,3,0};
  for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++){float3 lo=make_float3(x*1.25f,y*1.25f,z*1.25f),hi=make_float3(lo.x+1,lo.y+1,lo.z+1);cubes.push_back({lo,hi});for(int f=0;f<6;f++)for(int index:indices){int corner=face[f][index];RtVertex vertex{};vertex.p=make_float3((corner&1)?hi.x:lo.x,(corner&2)?hi.y:lo.y,(corner&4)?hi.z:lo.z);vertex.tint=0xffffffff;vertices.push_back(vertex);}}
  std::vector<CUdeviceptr> garbage;auto copy=[&](const void* data,size_t bytes){auto pointer=alloc(bytes);garbage.push_back(pointer);check(d.cuMemcpyHtoD(pointer,data,bytes));return pointer;};auto triangleVertices=copy(vertices.data(),vertices.size()*sizeof(RtVertex)),cubeVertices=copy(cubes.data(),cubes.size()*sizeof(RtCube));auto saved=params;auto savedSbt=sbt;
  unsigned flags=OPTIX_GEOMETRY_FLAG_NONE;for(int representation=0;representation<2;representation++){OptixBuildInput input{};if(representation==0){input.type=OPTIX_BUILD_INPUT_TYPE_TRIANGLES;auto& a=input.triangleArray;a.vertexBuffers=&triangleVertices;a.numVertices=vertices.size();a.vertexFormat=OPTIX_VERTEX_FORMAT_FLOAT3;a.vertexStrideInBytes=sizeof(RtVertex);a.flags=&flags;a.numSbtRecords=1;}else{input.type=OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;auto& a=input.customPrimitiveArray;a.aabbBuffers=&cubeVertices;a.numPrimitives=cubes.size();a.strideInBytes=sizeof(RtCube);a.flags=&flags;a.numSbtRecords=1;}
   OptixAccelBuildOptions options{};options.buildFlags=OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;OptixAccelBufferSizes sizes;check(optixAccelComputeMemoryUsage(optix,&options,&input,1,&sizes));auto scratch=alloc(sizes.tempSizeInBytes),gas=alloc(sizes.outputSizeInBytes);garbage.push_back(scratch);garbage.push_back(gas);OptixTraversableHandle handle;check(optixAccelBuild(optix,stream,&options,&input,1,scratch,sizes.tempSizeInBytes,gas,sizes.outputSizeInBytes,&handle,nullptr,0));HitRecord record{};check(optixSbtRecordPackHeader(groups[representation==0?2:3],&record));record.data.vertices=reinterpret_cast<RtVertex*>(representation==0?triangleVertices:cubeVertices);record.data.textureSlot=-1;sbt.hitgroupRecordBase=copy(&record,sizeof(record));sbt.hitgroupRecordCount=1;sbt.hitgroupRecordStrideInBytes=sizeof(record);auto result=alloc(262144*16);garbage.push_back(result);params.scene=handle;params.mode=7;params.diffuse=reinterpret_cast<float4*>(result);
   for(int run=0;run<10;run++){auto timer=beginTiming();uploadParams();launch(262144);endTiming(6+representation,timer);}
   void* host;check(api.cuMemHostAlloc(&host,262144*16,CU_MEMHOSTALLOC_PORTABLE));check(api.cuMemcpyDtoHAsync(host,result,262144*16,stream));CUevent event;check(api.cuEventCreate(&event,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(event,stream));benchmarkReads.push_back({event,reinterpret_cast<float4*>(host),representation});
  }params=saved;sbt=savedSbt;retire(std::move(garbage));benchmarkRequested=false;
 }
 void render(const float* settings,int width,int height,int aw,int ah,int iw,int ih,unsigned options,unsigned debug){
  check(d.cuCtxSetCurrent(cuda));collect();params.options=options;prepareDenoiser(width,height,(options&(1u<<25))!=0);if(imports.size()!=15||semaphores.size()!=2||!ias)throw std::runtime_error("RTX resources/scene not ready");
  CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS wait{};check(api.cuWaitExternalSemaphoresAsync(&semaphores[0],&wait,1,stream));
  auto frozen=params;auto floatPointer=[&](int i){return reinterpret_cast<float4*>(imports[i]);};params.position=floatPointer(0);params.normal=floatPointer(1);params.albedo=floatPointer(2);params.material=floatPointer(3);params.diffuse=floatPointer(4);params.specular=floatPointer(5);params.transmission=floatPointer(6);params.sunVisibility=floatPointer(11);params.surfaceKey=floatPointer(13);
  params.atlas=reinterpret_cast<unsigned*>(imports[7]);params.ids=reinterpret_cast<unsigned*>(imports[8]);params.lut=reinterpret_cast<unsigned*>(imports[9]);params.normalMap=reinterpret_cast<unsigned*>(imports[10]);params.entityAtlas=reinterpret_cast<unsigned*>(imports[12]);params.scene=ias;params.environmentMap=reinterpret_cast<float4*>(imports[14]);
  params.width=width;params.height=height;params.atlasWidth=aw;params.atlasHeight=ah;params.idsWidth=iw;params.idsHeight=ih;if((previousOptions&63)!=(options&63))previousFrame=false;previousOptions=options;params.options=params.referenceSpp?(options&~48u):options;params.debug=debug;params.camera=make_float3(settings[0],settings[1],settings[2]);params.sun=make_float3(settings[3],settings[4],settings[5]);params.sunColor=make_float3(settings[6],settings[7],settings[8]);params.sky=make_float3(settings[9],settings[10],settings[11]);memcpy(params.inverseCamera,settings+52,64);memcpy(params.previousClip,previousClip,sizeof(previousClip));params.previousCamera=previousCamera;memcpy(previousClip,settings+12,sizeof(previousClip));previousCamera=params.camera;params.time=settings[28];params.waveStrength=settings[29];params.cloudWind=settings[30];params.cloudAltitude=settings[31];params.rain=settings[32];params.cloudShadows=settings[33];params.rainRipples=settings[34];params.underwater=settings[35];params.pointPosition={settings[36],settings[37],settings[38],settings[39]};params.pointIntensity={settings[40],settings[41],settings[42],settings[43]};params.cameraWater={rt::V(settings[44],settings[45],settings[46]),rt::V(settings[47],settings[48],settings[49]),settings[50],settings[51],0xfffffffeu};if(params.referenceSpp&&!referenceClear){params.sun=frozen.sun;params.sunColor=frozen.sunColor;params.sky=frozen.sky;params.time=frozen.time;params.waveStrength=frozen.waveStrength;params.cloudWind=frozen.cloudWind;params.rain=frozen.rain;params.pointPosition=frozen.pointPosition;params.pointIntensity=frozen.pointIntensity;}
  unsigned referenceCount=params.referenceSpp?referenceSweep.count(width*height,params.referenceSpp,referencePixels):0;
  if(params.referenceSpp){
   if(referenceClear){check(d.cuMemcpyDtoDAsync(referenceEnvironment,imports[14],rt::EnvironmentWidth*rt::EnvironmentHeight*16,stream));if(!(options&(1u<<25)))for(int i=0;i<4;i++)check(d.cuMemcpyDtoDAsync(referenceGuides[i],imports[i],(size_t)width*height*16,stream));for(int i:{4,5,6,11,13})check(d.cuMemsetD8Async(imports[i],0,(size_t)width*height*16,stream));check(d.cuMemsetD8Async(imports[13],255,(size_t)width*height*16,stream));referenceClear=false;}
   else if(!(options&(1u<<25)))for(int i=0;i<4;i++)check(d.cuMemcpyDtoDAsync(imports[i],referenceGuides[i],(size_t)width*height*16,stream));
  }
  if(params.referenceSpp)params.environmentMap=reinterpret_cast<float4*>(referenceEnvironment);
  params.launchOffset=0;params.frame++;
  if(options&128){auto timer=beginTiming();for(int mode:{13,14}){params.mode=mode;uploadParams();launch(mode==13?rt::EnvironmentHeight:1);}endTiming(9,timer);}
check(d.cuMemsetD8Async(reinterpret_cast<CUdeviceptr>(params.counters),0,(size_t)width*height*36,stream));
  for(auto section:invalidations){params.mode=2;params.invalidateMin=make_float3(section[0]*16.f,section[1]*16.f,section[2]*16.f);params.invalidateMax=make_float3(section[3]*16.f,section[4]*16.f,section[5]*16.f);uploadParams();launch(1536);}invalidations.clear();params.mode=1;
  if(benchmarkRequested)benchmark();
  if((options&16)&&!params.referenceSpp){auto cacheTimer=beginTiming();uploadParams();launch(1536);endTiming(1,cacheTimer);}
  if(!params.referenceSpp&&(options&32)){auto timer=beginTiming();for(int mode:{10,8,11}){params.mode=mode;uploadParams();launch(4096);}endTiming(8,timer);}
  for(int signal=0;signal<3;signal++){auto timer=beginTiming();params.mode=signal==0?0:signal+4;
   if(params.referenceSpp){if(referenceCount){params.launchOffset=referenceSweep.offset;uploadParams();launch(referenceCount);}params.launchOffset=0;}
   else{uploadParams();launch(width*height);}endTiming(2+signal,timer);
  }
  auto denoiseTimer=beginTiming();if((options&8)&&debug==0&&params.referenceSpp==0)denoise();else previousFrame=false;endTiming(5,denoiseTimer);if(params.referenceSpp){if(!(options&(1u<<25)))for(int i=0;i<4;i++)check(d.cuMemcpyDtoDAsync(referenceGuides[i],imports[i],(size_t)width*height*16,stream));referenceSweep.advance(width*height,referenceCount);params.referenceSamples=referenceSweep.samples;}
  else{params.mode=4;uploadParams();launch(width*height);}
  if(!counterReady&&params.frame%60==0){check(d.cuMemsetD8Async(reinterpret_cast<CUdeviceptr>(params.counterTotals),0,36,stream));params.mode=12;params.launchOffset=params.referenceSpp?(referenceSweep.offset?referenceSweep.offset-referenceCount:width*height-referenceCount):0;uploadParams();if(!params.referenceSpp||referenceCount)launch(params.referenceSpp?referenceCount:width*height);params.launchOffset=0;check(api.cuMemcpyDtoHAsync(counterHost,reinterpret_cast<CUdeviceptr>(params.counterTotals),36,stream));check(api.cuEventCreate(&counterReady,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(counterReady,stream));}
  CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS signal{};check(api.cuSignalExternalSemaphoresAsync(&semaphores[1],&signal,1,stream));
  if(params.referenceSpp){check(api.cuEventCreate(&referenceReady,CU_EVENT_DISABLE_TIMING));check(api.cuEventRecord(referenceReady,stream));}
 }
 ~RtContext(){if(!cuda)return;d.cuCtxSetCurrent(cuda);if(stream)d.cuStreamSynchronize(stream);if(referenceReady)api.cuEventDestroy(referenceReady);if(counterReady)api.cuEventDestroy(counterReady);if(counterHost)api.cuMemFreeHost(counterHost);for(auto& read:benchmarkReads){api.cuEventDestroy(read.event);api.cuMemFreeHost(read.pointer);}if(benchmarkReference)api.cuMemFreeHost(benchmarkReference);for(auto& t:timings){api.cuEventDestroy(t.begin);api.cuEventDestroy(t.end);}for(auto& h:hostRetired){api.cuMemFreeHost(h.pointer);api.cuEventDestroy(h.event);}for(auto s:semaphores)api.cuDestroyExternalSemaphore(s);for(auto p:imports)free(p);for(auto m:memories)api.cuDestroyExternalMemory(m);for(auto& r:retired){for(auto p:r.pointers)free(p);api.cuEventDestroy(r.event);}for(auto& e:sections){free(e.second.vertices);free(e.second.gasGpu);}for(auto& entry:models){free(entry.second.vertices);free(entry.second.gasGpu);}for(auto p:owned)free(p);for(auto p:{iasGpu,instancesGpu,sbtHit})if(p)free(p);while(!allocationBytes.empty())free(allocationBytes.begin()->first);if(denoiser)optixDenoiserDestroy(denoiser);if(causticPipeline)optixPipelineDestroy(causticPipeline);if(causticGroup)optixProgramGroupDestroy(causticGroup);if(pipeline)optixPipelineDestroy(pipeline);for(auto g:callableGroups)if(g)optixProgramGroupDestroy(g);for(auto g:signalGroups)if(g)optixProgramGroupDestroy(g);for(auto g:groups)if(g)optixProgramGroupDestroy(g);for(auto m:callableModules)if(m)optixModuleDestroy(m);for(auto m:signalModules)if(m)optixModuleDestroy(m);if(module)optixModuleDestroy(module);if(hitModule)optixModuleDestroy(hitModule);if(causticModule)optixModuleDestroy(causticModule);if(utilityModule)d.cuModuleUnload(utilityModule);if(optix)optixDeviceContextDestroy(optix);if(stream)d.cuStreamDestroy(stream);d.cuDevicePrimaryCtxRelease(device);}
};
EXPORT jlong JNICALL Java_com_voxellight_nvidia_OptixNative_initializationTasks(JNIEnv*,jclass){return (static_cast<jlong>(rtCompileScheduled.load())<<32)|rtCompileFinished.load();}
EXPORT jint JNICALL Java_com_voxellight_nvidia_OptixNative_initializationStage(JNIEnv*,jclass){return rtInitializationStage.load();}
EXPORT jlong JNICALL Java_com_voxellight_nvidia_OptixNative_create(JNIEnv* e,jclass,jbyteArray id,jobjectArray codes,jboolean full,jstring cache){RtContext* c=nullptr;try{if(e->GetArrayLength(id)!=16||e->GetArrayLength(codes)!=10)throw std::runtime_error("Invalid RTX module bundle");unsigned char uuid[16];e->GetByteArrayRegion(id,0,16,reinterpret_cast<jbyte*>(uuid));std::vector<std::vector<char>> code;for(int i=0;i<10;i++){auto bytes=static_cast<jbyteArray>(e->GetObjectArrayElement(codes,i));int n=e->GetArrayLength(bytes);code.emplace_back(n+1);e->GetByteArrayRegion(bytes,0,n,reinterpret_cast<jbyte*>(code.back().data()));e->DeleteLocalRef(bytes);}const char* path=e->GetStringUTFChars(cache,nullptr);std::string location(path);e->ReleaseStringUTFChars(cache,path);c=new RtContext;c->init(uuid,code,full,location.c_str());return reinterpret_cast<jlong>(c);}catch(const std::exception& ex){delete c;fail(e,ex);return 0;}}
EXPORT jstring JNICALL Java_com_voxellight_nvidia_OptixNative_cacheStatus(JNIEnv* e,jclass,jlong h){return e->NewStringUTF(reinterpret_cast<RtContext*>(h)->cacheStatus.c_str());}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_importBuffer(JNIEnv* e,jclass,jlong h,jlong external,jlong allocation,jlong size){try{reinterpret_cast<RtContext*>(h)->importMemory(external,allocation,size);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_importSemaphore(JNIEnv* e,jclass,jlong h,jlong external){try{reinterpret_cast<RtContext*>(h)->importSemaphore(external);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_updateSections(JNIEnv* e,jclass,jlong h,jobject batch){try{size_t size=e->GetDirectBufferCapacity(batch);reinterpret_cast<RtContext*>(h)->update(reinterpret_cast<unsigned char*>(buffer(e,batch,size)),size);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_render(JNIEnv* e,jclass,jlong h,jobject settings,jint w,jint height,jint aw,jint ah,jint iw,jint ih,jint options,jint debug){try{reinterpret_cast<RtContext*>(h)->render(reinterpret_cast<float*>(buffer(e,settings,272)),w,height,aw,ah,iw,ih,options,debug);}catch(const std::exception& ex){fail(e,ex);}}
EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_destroy(JNIEnv*,jclass,jlong h){delete reinterpret_cast<RtContext*>(h);}

EXPORT jlongArray JNICALL Java_com_voxellight_nvidia_OptixNative_timings(JNIEnv* e,jclass,jlong handle){try{auto c=reinterpret_cast<RtContext*>(handle);c->collect();auto result=e->NewLongArray(c->measured.size());if(result)e->SetLongArrayRegion(result,0,c->measured.size(),reinterpret_cast<const jlong*>(c->measured.data()));c->measured.clear();return result;}catch(const std::exception& ex){fail(e,ex);return nullptr;}}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_invalidate(JNIEnv* e,jclass,jlong handle,jobject regions){try{auto c=reinterpret_cast<RtContext*>(handle);auto size=e->GetDirectBufferCapacity(regions);if(size<0||size%24||size>256*24)throw std::runtime_error("Invalid cache invalidation batch");auto bytes=reinterpret_cast<unsigned char*>(buffer(e,regions,size));for(int offset=0;offset<size;offset+=24){std::array<int,6> region;memcpy(region.data(),bytes+offset,24);c->invalidations.push_back(region);}}catch(const std::exception& ex){fail(e,ex);}}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_benchmark(JNIEnv*,jclass,jlong handle){reinterpret_cast<RtContext*>(handle)->benchmarkRequested=true;}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_updateInstances(JNIEnv* e,jclass,jlong handle,jobject batch){try{size_t size=e->GetDirectBufferCapacity(batch);reinterpret_cast<RtContext*>(handle)->updateDynamic(size?reinterpret_cast<unsigned char*>(buffer(e,batch,size)):nullptr,size);}catch(const std::exception& ex){fail(e,ex);}}

EXPORT jlongArray JNICALL Java_com_voxellight_nvidia_OptixNative_stats(JNIEnv* e,jclass,jlong h){try{auto c=reinterpret_cast<RtContext*>(h);// Telemetry must not perform CUDA work from a settings draw.
 jlong values[]={static_cast<jlong>(c->sections.size()),static_cast<jlong>(c->models.size()),static_cast<jlong>(c->dynamicInstances.size()),static_cast<jlong>(c->allocatedBytes),static_cast<jlong>(c->iasCapacity),static_cast<jlong>(c->retired.size()),c->benchmarkMismatches,static_cast<jlong>(c->benchmarkMaxError*1000000),c->params.referenceSamples,c->params.lightCount,c->operationStats[0],c->operationStats[1],c->operationStats[2],c->operationStats[3],c->operationStats[4],c->operationStats[5],c->operationStats[6],c->operationStats[7],c->operationStats[8],c->referenceSweep.offset,c->referencePixels};auto result=e->NewLongArray(21);if(result)e->SetLongArrayRegion(result,0,21,values);return result;}catch(const std::exception& ex){fail(e,ex);return nullptr;}}

EXPORT void JNICALL Java_com_voxellight_nvidia_OptixNative_reference(JNIEnv*,jclass,jlong handle,jint spp,jboolean reset,jfloat clamp){auto c=reinterpret_cast<RtContext*>(handle);if(reset||c->params.referenceSpp!=(unsigned)spp)c->resetReference();c->params.referenceSpp=spp;c->params.fireflyClamp=clamp;}

EXPORT jboolean JNICALL Java_com_voxellight_nvidia_OptixNative_referenceFinished(JNIEnv* e,jclass,jlong h){try{return reinterpret_cast<RtContext*>(h)->referenceFinished();}catch(const std::exception& ex){fail(e,ex);return false;}}
