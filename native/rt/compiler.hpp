// Included inside RtContext: compiler ownership never shares mutable frame state.
 OptixPipelineCompileOptions pipelineOptions(){OptixPipelineCompileOptions po{};po.traversableGraphFlags=OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING|OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;po.numPayloadValues=2;po.numAttributeValues=2;po.pipelineLaunchParamsVariableName="params";po.usesPrimitiveTypeFlags=OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE|OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;return po;}
 std::string cacheStatus,gpuName,nvidiaDriver="unavailable";int driverVersion=0;bool strictReference=false;
 static long long timestamp(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
 void readNvidiaDriver(){
#ifdef _WIN32
  auto library=LoadLibraryA("nvml.dll");if(!library)return;auto symbol=[&](const char* name){return reinterpret_cast<void*>(GetProcAddress(library,name));};
#else
  auto library=dlopen("libnvidia-ml.so.1",RTLD_NOW|RTLD_LOCAL);if(!library)return;auto symbol=[&](const char* name){return dlsym(library,name);};
#endif
  auto init=reinterpret_cast<int(*)()>(symbol("nvmlInit_v2"));auto version=reinterpret_cast<int(*)(char*,unsigned)>(symbol("nvmlSystemGetDriverVersion"));auto shutdown=reinterpret_cast<int(*)()>(symbol("nvmlShutdown"));
  if(init&&version&&shutdown&&init()==0){char value[128]{};if(version(value,sizeof(value))==0)nvidiaDriver=value;shutdown();}
#ifdef _WIN32
  FreeLibrary(library);
#else
  dlclose(library);
#endif
 }
 void configureCache(const char* path){
  std::filesystem::create_directories(path);auto probe=std::filesystem::path(path)/("write-test-"+std::to_string(reinterpret_cast<uintptr_t>(this)));
  {std::ofstream file(probe);if(!file||!(file<<"VoxelLight"))throw std::runtime_error("OptiX cache directory is not writable");}std::filesystem::remove(probe);
  check(optixDeviceContextSetCacheLocation(optix,path));check(optixDeviceContextSetCacheEnabled(optix,1));
  int enabled=0;char location[4096]{};size_t low=0,high=0;check(optixDeviceContextGetCacheEnabled(optix,&enabled));check(optixDeviceContextGetCacheLocation(optix,location,sizeof(location)));check(optixDeviceContextGetCacheDatabaseSizes(optix,&low,&high));
  // Keep SDK watermarks until measured entries justify an increase; optional measured override.
  if(const char* bytes=std::getenv("VOXELLIGHT_RT_CACHE_HIGH_BYTES")){size_t requested=std::stoull(bytes);if(requested>high){high=requested;low=high/2;check(optixDeviceContextSetCacheDatabaseSizes(optix,low,high));check(optixDeviceContextGetCacheDatabaseSizes(optix,&low,&high));}}
  cacheStatus=", rtCacheEnabled="+std::to_string(enabled)+", rtCachePath="+location+", rtCacheLowWater="+std::to_string(low)+", rtCacheHighWater="+std::to_string(high);
  fprintf(stderr,"VoxelLight OptiX cache%s\n",cacheStatus.c_str());
 }
 void compileModule(OptixModule& target,const char* name,const std::vector<char>& code){
  OptixModuleCompileOptions mo{};mo.debugLevel=OPTIX_COMPILE_DEBUG_LEVEL_NONE;mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
  const char* profile=std::getenv("VOXELLIGHT_RT_PROFILE");const char* optimization=std::getenv("VOXELLIGHT_RT_OPTIMIZATION");
  if(!strictReference&&profile&&std::string(profile)=="DEVELOPMENT")mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_LEVEL_0;
  if(optimization){std::string value=optimization;if(value=="0")mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_LEVEL_0;else if(value=="1")mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_LEVEL_1;else if(value=="2")mo.optLevel=OPTIX_COMPILE_OPTIMIZATION_LEVEL_2;}
  auto po=pipelineOptions();std::vector<char> log(1024*1024);size_t logSize=log.size();OptixTask first=nullptr;
  auto start=std::chrono::steady_clock::now();rtInitializationStage=3;
  fprintf(stderr,"VoxelLight compile start module=%s bytes=%zu optimization=%d profile=%s driver=%s cudaDriverApi=%d GPU=%s timestamp=%lld\n",name,code.size()-1,int(mo.optLevel),strictReference?"REFERENCE_STRICT":profile?profile:"REALTIME_RELEASE",nvidiaDriver.c_str(),driverVersion,gpuName.c_str(),timestamp());
  // This guard also covers the initial task-creation call. Cancellation targets
  // only this isolated OptiX context; every task is joined before guard/module destruction.
  struct Watchdog {
   std::mutex lock;std::condition_variable wake;bool done=false;std::atomic<bool> timeout{false};std::thread thread;
   Watchdog(OptixDeviceContext context,const char* name,std::chrono::steady_clock::time_point deadline):thread([this,context,name,deadline]{std::unique_lock<std::mutex> guard(lock);if(!wake.wait_until(guard,deadline,[&]{return done;})){timeout=true;fprintf(stderr,"VoxelLight compile timeout module=%s; requesting cancellation; draining tasks before destroy\n",name);auto result=optixDeviceContextCancelCreations(context,OPTIX_CREATION_FLAG_NONE);if(result!=OPTIX_SUCCESS)fprintf(stderr,"VoxelLight cancellation error=%d\n",int(result));}}){}
   void finish(){{std::lock_guard<std::mutex> guard(lock);done=true;}wake.notify_all();if(thread.joinable())thread.join();}
   ~Watchdog(){finish();}
  } watchdog(optix,name,start+std::chrono::seconds(strictReference?600:120));
  auto creation=optixModuleCreateWithTasks(optix,&mo,&po,code.data(),code.size()-1,log.data(),&logSize,&target,&first);
  if(creation!=OPTIX_SUCCESS)throw std::runtime_error(std::string(name)+" creation: OptiX error "+std::to_string(int(creation))+"; "+diagnostics()+"; "+log.data());
  if(first)rtCompileScheduled++;
  std::atomic<int> taskError{0};std::atomic<unsigned> serial{0};
  std::exception_ptr failure;
  try{rt::compileTasks(first,4,[&](OptixTask task){
   check(d.cuCtxSetCurrent(cuda));unsigned id=++serial;auto began=std::chrono::steady_clock::now();size_t keySize=0;std::string key="unavailable";
   if(optixTaskGetSerializationKey(task,nullptr,&keySize)==OPTIX_SUCCESS&&keySize){std::vector<unsigned char> bytes(keySize);if(optixTaskGetSerializationKey(task,bytes.data(),&keySize)==OPTIX_SUCCESS){key.clear();const char* hex="0123456789abcdef";for(auto byte:bytes){key+=hex[byte>>4];key+=hex[byte&15];}}}
   auto thread=std::hash<std::thread::id>{}(std::this_thread::get_id());
   fprintf(stderr,"VoxelLight task start module=%s id=%u handle=%p key=%s timestamp=%lld thread=%zu\n",name,id,(void*)task,key.c_str(),timestamp(),thread);
   OptixTask children[32]{};unsigned count=0;auto result=optixTaskExecute(task,children,32,&count);
   rtCompileScheduled+=count;rtCompileFinished++;
   fprintf(stderr,"VoxelLight task finish module=%s id=%u timestamp=%lld active_ms=%lld children=%u thread=%zu result=%d\n",name,id,timestamp(),(long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-began).count(),count,thread,int(result));
   if(result!=OPTIX_SUCCESS){int noError=0;taskError.compare_exchange_strong(noError,int(result));optixModuleCancelCreation(target,OPTIX_CREATION_FLAG_NONE);}
   return std::vector<OptixTask>(children,children+count);
  });}catch(...){failure=std::current_exception();optixModuleCancelCreation(target,OPTIX_CREATION_FLAG_BLOCK_UNTIL_EFFECTIVE);}
  watchdog.finish();
  auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();fprintf(stderr,"VoxelLight compile finish module=%s elapsed_ms=%lld timeout=%d\n",name,(long long)ms,int(watchdog.timeout.load()));
  if(failure)std::rethrow_exception(failure);
  OptixModuleCompileState state;check(optixModuleGetCompilationState(target,&state));
  if(watchdog.timeout||taskError||state!=OPTIX_MODULE_COMPILE_STATE_COMPLETED)throw std::runtime_error(std::string(name)+" module compilation failed/timeout: OptiX error "+std::to_string(taskError.load())+"; "+diagnostics()+"; "+log.data());
 }
 void launch(unsigned count){
  if(params.mode==2||params.mode==4||params.mode==10||params.mode==11||params.mode==12||params.mode==13||params.mode==14){params.utilityCount=count;void* args[]={&params};check(d.cuLaunchKernel(utility,(count+127)/128,1,1,128,1,1,0,stream,args,nullptr));return;}
  auto table=sbt;if(params.mode==8)table.raygenRecord=causticRecord;
  check(optixLaunch(params.mode==8?causticPipeline:pipeline,stream,paramsGpu,sizeof(params),&table,count,1,1));
 }
