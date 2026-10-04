package com.voxellight.adapter;
import com.mojang.blaze3d.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vulkan.*;
import com.voxellight.nvidia.*;
import com.voxellight.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
/** Raster-primary RTX owner. Each normal frame exchanges GPU-only buffers and binary semaphores. */
final class RtxLightingPass implements com.voxellight.rt.RtBackend {
 static final RenderPipeline CAPTURE=capturePipeline(),COMPOSITE=compositePipeline(),ATLAS=atlasPipeline(),DIELECTRIC=dielectricPipeline(),ENVIRONMENT=environmentPipeline(),REFERENCE_DISPLAY=referenceDisplayPipeline();
 private static final java.util.concurrent.ExecutorService STARTUP=java.util.concurrent.Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"VoxelLight OptiX startup");thread.setDaemon(true);return thread;});
 private com.voxellight.rt.AsyncResource<Long> startup,referenceStartup;
 private boolean startupFull,fullReferenceRequested;private String referenceCompileState="idle";
 private int startupStage=-1;private long startupStarted,startupLogged;
 private long context,world=Long.MIN_VALUE,resources=Long.MIN_VALUE;
 private VulkanCudaInterop interop;
 private final RtTerrainWarmup warmup=new RtTerrainWarmup();
 private final GpuTexture[] capture=new GpuTexture[4],signals=new GpuTexture[5];
 private final GpuTextureView[] captureViews=new GpuTextureView[4],signalViews=new GpuTextureView[5];
 private GpuTextureView materialIds;
 GpuTextureView materialIds(){return materialIds;}
 private GpuTexture entityAtlas;private GpuTextureView entityAtlasView;private final IdentityHashMap<GpuTextureView,Integer> entitySlots=new IdentityHashMap<>();private int dynamicModels;
 private GpuTexture atlas,composite,dielectric;private GpuTextureView atlasView,compositeView,dielectricView;
 private GpuBuffer frame,environmentSettings;private GpuTexture environmentMap;private GpuTextureView environmentView;
 private int width,height,aw,ah,iw,ih,options=511,debug;private com.voxellight.world.VisualQuality quality=com.voxellight.world.VisualQuality.BALANCED;
 void quality(com.voxellight.world.VisualQuality value){quality=value;referenceHistory.quality(value);}
 private final com.voxellight.rt.ReferenceHistory referenceHistory=new com.voxellight.rt.ReferenceHistory();
 private float[] queuedPose,displayPose;
 private boolean reference,fullReference,referenceAnnouncement,referenceInFlight,displayValid;private int referenceScale=4;private int referenceSpp=256;private float fireflyClamp=10000;
 void reference(boolean value){if(!value){fullReferenceRequested=false;if(referenceStartup!=null){referenceStartup.close();referenceStartup=null;}if(fullReference)close();fullReference=false;}if(value&&!enabled)enable(true);reference=value;referenceAnnouncement=value;referenceHistory.invalidate();}
 boolean referenceEnabled(){return reference;}
 boolean fullReferenceActive(){return active&&fullReference;}
 void fullReference(boolean value){
  fullReferenceRequested=value;
  if(value){if(!enabled)enable(true);referenceCompileState="requested";}
  else{if(referenceStartup!=null){referenceStartup.close();referenceStartup=null;}if(fullReference)close();fullReference=false;reference=false;referenceCompileState="idle";}
  referenceHistory.invalidate();
 }
 void referenceScale(int value){if(value!=1&&value!=2&&value!=4)throw new IllegalArgumentException("Reference scale must be 1, 2 or 4");if(referenceScale!=value){referenceScale=value;close();referenceHistory.invalidate();}}

 void referenceSpp(int value){referenceSpp=value;referenceHistory.invalidate();}
 void referenceReset(){referenceAnnouncement=reference;referenceHistory.invalidate();}
 void fireflyClamp(boolean value){fireflyClamp=value?10000:0;referenceHistory.invalidate();}
 private boolean enabled,active,failed,assetsUploaded,sceneReady;
 private String state="off";
 private final Map<SectionKey,Long> resident=new HashMap<>();
 private static void recompile(){var mc=Minecraft.getInstance();if(mc.level!=null)mc.levelRenderer.invalidateCompiledGeometry(mc.level,mc.options,mc.gameRenderer.mainCamera(),mc.getBlockColors());}
 void enable(boolean value){if(enabled==value&&!failed)return;enabled=value;if(!value)discardStartup();close();failed=false;RtGeometryStream.enable(value);state=value?"waiting for compiled RT scene":"off";if(Minecraft.getInstance().level!=null)recompile();}
 void option(String option,boolean value){int bit=switch(option){case "rt_gi"->1;case "rt_reflections"->2;case "rt_transmission"->4;case "rt_denoiser"->8;case "radiance_cache"->16;case "rt_caustics"->32;case "rt_primary_glossy_nee"->64;case "rt_environment"->128;case "rt_multiscatter"->256;default->throw new IllegalArgumentException(option);};options=value?options|bit:options&~bit;referenceHistory.invalidate();}
 void benchmark(){if(context==0)throw new IllegalStateException("Enable RTX and wait for RT scene before benchmarking");OptixNative.benchmark(context);}
 void debug(int value){if(debug!=value)referenceHistory.invalidate();debug=value;}
 public com.voxellight.rt.RtTraversalBackend traversal(){return com.voxellight.rt.RtTraversalBackend.OPTIX_RT;}
 public boolean active(){return active;}
 int ownership(){return active?options:0;}
 GpuTextureView interfaceNormal(){return active?captureViews[1]:null;}
 GpuTextureView sunVisibility(){return active?signalViews[3]:null;}
 GpuTextureView surfaceKey(){return active?signalViews[4]:null;}
 GpuTextureView coverage(){return active?captureViews[0]:null;}
 GpuTextureView transmission(){return active&&(options&4)!=0?dielectricView:null;}
 void trace(CommandEncoder encoder,RenderTarget target,MaterialCapture material,ShadowRenderer shadows,Matrix4f projection,boolean observed,EnvironmentPass weather){
  OptixBridge.drainRtDiagnostics();materialIds=material.rtAtlas(0);active=false;if(!enabled||failed||!observed)return;
  String stage="Vulkan/CUDA device checks";
  try{
   var stats=com.voxellight.VoxelLightClient.scene().bridge().stats();
   if(context!=0&&(world!=stats.worldGeneration()||resources!=stats.resourceGeneration())){close();RtGeometryStream.enable(true);recompile();}
   var backend=((GpuBackendAccess)RenderSystem.getDevice()).voxellight$backend();if(!(backend instanceof VulkanDevice vk))throw new IllegalStateException("RTX requires Vulkan");
   boolean win=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");if(win?!vk.vkDevice().getCapabilities().VK_KHR_external_memory_win32||!vk.vkDevice().getCapabilities().VK_KHR_external_semaphore_win32:!vk.vkDevice().getCapabilities().VK_KHR_external_memory_fd||!vk.vkDevice().getCapabilities().VK_KHR_external_semaphore_fd)throw new IllegalStateException("Vulkan export extensions unavailable");
   if(fullReferenceRequested&&!fullReference&&context!=0){
    if(referenceStartup==null){byte[] uuid=deviceUuid(vk);referenceCompileState="compiling in background; realtime retained";
     referenceStartup=new com.voxellight.rt.AsyncResource<>(STARTUP,()->{try{return OptixBridge.createRt(uuid,true);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}},handle->STARTUP.execute(()->OptixNative.destroy(handle)));
    }
    if(referenceStartup.started()&&!referenceStartup.finished()&&OptixNative.initializationStage()==8)referenceCompileState="timed out; realtime retained; waiting for safe compiler cleanup";
    if(referenceStartup.finished()){
     long prepared=0;
     try{prepared=referenceStartup.take();}catch(RuntimeException error){referenceStartup.close();fullReferenceRequested=false;referenceCompileState="failed; realtime retained";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Full reference compile failed; realtime retained",error);}
     referenceStartup=null;
     if(prepared!=0){closeResources();fullReference=true;reference=true;referenceAnnouncement=true;context=prepared;referenceCompileState="ready";referenceHistory.invalidate();RtGeometryStream.enable(true);recompile();
      int rw=(target.width+referenceScale-1)/referenceScale,rh=(target.height+referenceScale-1)/referenceScale;
      try{init(vk,encoder,target,material,rw,rh);world=stats.worldGeneration();resources=stats.resourceGeneration();}catch(Exception error){closeResources();fullReference=reference=fullReferenceRequested=false;referenceCompileState="resource failure; restarting realtime";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Full reference resource import failed",error);return;}
     }
    }
   }
   int scale=fullReference?referenceScale:Math.max(4,Math.max((target.width+639)/640,(target.height+359)/360)),w=(target.width+scale-1)/scale,h=(target.height+scale-1)/scale;
   if(context!=0&&(width!=w||height!=h||composite.getWidth(0)!=target.width||composite.getHeight(0)!=target.height)){close();RtGeometryStream.enable(true);recompile();}
   if(context==0){
    if(!RtGeometryStream.enabled()){RtGeometryStream.enable(true);recompile();}
    stage="OptiX background initialization";
    if(startup!=null&&startupFull!=fullReference)discardStartup();
    if(startup==null){
     startupFull=fullReference;byte[] uuid=deviceUuid(vk);boolean compileFull=fullReference;startupStarted=System.nanoTime();startupLogged=startupStarted;
     org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX background initialization: {}x{} guides, reference={}; raster continues",w,h,reference);
     startup=new com.voxellight.rt.AsyncResource<>(STARTUP,()->{try{return OptixBridge.createRt(uuid,compileFull);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}},handle->STARTUP.execute(()->OptixNative.destroy(handle)));
    }
    if(!startup.finished()){
     if(!startup.started()){state="queued behind previous compiler cleanup; raster retained";return;}
     int progress;try{progress=OptixNative.initializationStage();}catch(UnsatisfiedLinkError loading){progress=0;}
     if(progress==8){state="compile timed out; raster fallback active; waiting for safe compiler cleanup";if(startupStage!=8){startupStage=8;org.slf4j.LoggerFactory.getLogger("VoxelLight").error("RTX {}",state);}return;}
     String[] stages={"loading native library","matching CUDA device","creating OptiX context","compiling split OptiX modules","creating OptiX program groups","linking OptiX pipeline","configuring OptiX stack","native context ready"};
     long tasks=progress==3?OptixNative.initializationTasks():0;
     state="initializing: "+stages[Math.max(0,Math.min(progress,stages.length-1))]+" ("+((System.nanoTime()-startupStarted)/1_000_000_000L)+"s)"+(progress==3?"; compiler tasks="+(tasks&0xffffffffL)+"/"+(tasks>>>32)+"; optimization="+System.getenv().getOrDefault("VOXELLIGHT_RT_OPTIMIZATION","default")+"":"")+"; raster retained";
     if(progress!=startupStage||System.nanoTime()-startupLogged>15_000_000_000L){startupStage=progress;startupLogged=System.nanoTime();org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX {}",state);}
     return;
    }
    context=startup.take();startup=null;
    stage="Vulkan/CUDA resource import";init(vk,encoder,target,material,w,h);world=stats.worldGeneration();resources=stats.resourceGeneration();
    org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX initialization complete; preparing scene");
   }
   // Bank A is the Vulkan display textures; bank B is CUDA-owned external output.
   var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;var pos=camera.pos;
   var currentClip=new Matrix4f(projection).mul(camera.viewRotationMatrix);var cameraKey=new Matrix4f(currentClip).translate((float)-pos.x(),(float)-pos.y(),(float)-pos.z());var pose=cameraKey.get(new float[16]);
   // Never queue a display wait for an unfinished reference window.
   if(referenceInFlight){
    if(!OptixNative.referenceFinished(context)){active=displayValid&&com.voxellight.rt.ReferenceHistory.samePose(displayPose,pose);state="reference computing asynchronously; last display retained";return;}
    interop.acquireFromCuda();consumeSignals(encoder);referenceInFlight=false;displayValid=true;displayPose=queuedPose;active=com.voxellight.rt.ReferenceHistory.samePose(displayPose,pose);state="reference window ready; display bank updated";return;
   }
   // Apply resets before instance updates so a new reference refreshes its scene snapshot.

   OptixNative.reference(context,reference?referenceSpp:0,referenceHistory.consume(cameraKey.get(new float[16]),reference),reference?0:fireflyClamp);
   var measured=OptixNative.timings(context);String[] passes={"rt_as_build_update","rt_radiance_cache","rt_diffuse","rt_specular","rt_transmission","rt_denoiser","rt_benchmark_triangles","rt_benchmark_aabb","rt_caustic","rt_environment_distribution"};for(int i=0;i+1<measured.length;i+=2)RenderPassProfile.externalGpu(passes[(int)measured[i]],measured[i+1]);
   stage="RT scene preparation";updateScene(pos.x(),pos.y(),pos.z());updateInstances(encoder);if(!sceneReady){state="warming compiled section GAS/IAS";return;}
   try(var stack=MemoryStack.stackPush()) {encoder.writeToBuffer(frame.slice(),Std140Builder.onStack(stack,144).putMat4f(new Matrix4f(projection).invert()).putMat4f(new Matrix4f(camera.viewRotationMatrix).invert()).putVec4(options,debug,Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera()==net.minecraft.world.level.material.FogType.WATER?1:0,fullReference?1:0).get());}
   var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
   var descriptor=RenderPassDescriptor.create(()->"VoxelLight RTX primary guides").withRenderArea(new RenderPass.RenderArea(0,0,width,height));for(var view:captureViews)descriptor.withColorAttachment(view,Optional.of(new Vector4f(0)));
   try(var profile=RenderPassProfile.begin(encoder,"rt_primary_guides");var pass=encoder.createRenderPass(descriptor)){
    pass.setPipeline(CAPTURE);pass.setUniform("RtFrame",frame);pass.bindTexture("SceneDepth",target.getDepthTextureView(),nearest);pass.bindTexture("MaterialDepth",material.view(3),nearest);pass.bindTexture("MaterialNormal",material.view(1),nearest);pass.bindTexture("MaterialAlbedo",material.view(0),nearest);pass.bindTexture("MaterialPbr",material.view(4),nearest);pass.bindTexture("MaterialTable",material.materialTable(),nearest);pass.draw(3,1,0,0);
   }
   for(int i=0;i<4;i++)encoder.copyTextureToBuffer(capture[i],interop.buffers[i],0,()->{},0);
   if(!assetsUploaded){
    try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight RTX texture atlas").withRenderArea(new RenderPass.RenderArea(0,0,aw,ah)).withColorAttachment(atlasView,Optional.of(new Vector4f(0))))){pass.setPipeline(ATLAS);pass.bindTexture("Sampler0",((TextureAtlas)Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)).getTextureView(),nearest);pass.draw(3,1,0,0);}
    encoder.copyTextureToBuffer(atlas,interop.buffers[7],0,()->{},0);encoder.copyTextureToBuffer(material.rtAtlas(0).texture(),interop.buffers[8],0,()->{},0);encoder.copyTextureToBuffer(material.rtAtlas(2).texture(),interop.buffers[9],0,()->{},0);encoder.copyTextureToBuffer(material.rtAtlas(1).texture(),interop.buffers[10],0,()->{},0);assetsUploaded=true;
   }
   var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;var light=shadows.light();var env=LightingEnvironment.polished(light,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);var sun=light.direction();var settings=ByteBuffer.allocateDirect(272).order(ByteOrder.nativeOrder());settings.putFloat((float)pos.x()).putFloat((float)pos.y()).putFloat((float)pos.z()).putFloat(sun.x).putFloat(sun.y).putFloat(sun.z).putFloat(env.directR()*env.directStrength()*(float)Math.PI).putFloat(env.directG()*env.directStrength()*(float)Math.PI).putFloat(env.directB()*env.directStrength()*(float)Math.PI).putFloat(env.skyR()*env.skyStrength()).putFloat(env.skyG()*env.skyStrength()).putFloat(env.skyB()*env.skyStrength());new Matrix4f(projection).mul(camera.viewRotationMatrix).get(48,settings);settings.position(112).putFloat(WaterSurface.clock()).putFloat(WaterSurface.waveStrength());for(float value:weather.rtSettings())settings.putFloat(value);settings.putFloat(Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera()==net.minecraft.world.level.material.FogType.WATER?1:0);for(float value:shadows.rtVirtualLight())settings.putFloat(value);for(float value:material.waterMedium())settings.putFloat(value);new Matrix4f(currentClip).invert().get(208,settings);settings.position(272);settings.flip();
   renderEnvironment(encoder,weather,shadows);
   stage="reference / RT dispatch and external semaphore handoff";if(reference&&referenceAnnouncement){org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX reference dispatch: target={} spp, progressive tiles, adaptive 8-1024 pixels/frame, guides={}x{}",referenceSpp,width,height);referenceAnnouncement=false;}
   interop.releaseToCuda();OptixNative.render(context,settings,width,height,aw,ah,iw,ih,(fullReference?(options&~(8|16|32)):options)|(quality.ordinal()<<16)|(fullReference?1<<25:0),debug);if(reference){referenceInFlight=true;queuedPose=pose;active=displayValid&&com.voxellight.rt.ReferenceHistory.samePose(displayPose,pose);state="reference queued asynchronously";return;}
   interop.acquireFromCuda();consumeSignals(encoder);
   active=displayValid=true;state="OptiX triangle GAS/IAS; external-memory lighting";
  }catch(Exception|LinkageError error){failed=true;state="RTX unavailable; raster retained at "+stage+": "+error.getMessage();org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("RTX lighting disabled; raster fallback retained",error);discardStartup();closeResources();RtGeometryStream.enable(false);}
 }
 private void consumeSignals(CommandEncoder encoder){
   var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
   for(int i=0;i<4;i++)copyRtTexture(encoder,interop.buffers[i==3?11:4+i],signals[i],width,height);
   copyRtTexture(encoder,interop.buffers[13],signals[4],width,height);
   for(int i=0;i<4;i++)copyRtTexture(encoder,interop.buffers[i],capture[i],width,height);
   try(var profile=RenderPassProfile.begin(encoder,"rt_dielectric_merge");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight RT dielectric signals").withRenderArea(new RenderPass.RenderArea(0,0,width,height)).withColorAttachment(dielectricView,Optional.empty()))){pass.setPipeline(DIELECTRIC);pass.setUniform("RtFrame",frame);pass.bindTexture("RtDiffuse",signalViews[0],nearest);pass.bindTexture("RtSpecular",signalViews[1],nearest);pass.bindTexture("RtTransmission",signalViews[2],nearest);pass.draw(3,1,0,0);}
 }
 /** MC 26.2: source x/y/row width/height, destination x/y/extent, mip, layer. */
 static void copyRtTexture(CommandEncoder encoder,GpuBuffer source,GpuTexture destination,int width,int height){encoder.copyBufferToTexture(source.slice(),0,0,width,height,destination,0,0,width,height,0,0);}
 private void updateScene(double x,double y,double z){
  var dirty=com.voxellight.rt.RtInvalidationQueue.drain();if(!dirty.isEmpty()){var batch=ByteBuffer.allocateDirect(dirty.size()*24).order(ByteOrder.nativeOrder());for(var region:dirty)batch.putInt(region.minX()).putInt(region.minY()).putInt(region.minZ()).putInt(region.maxX()).putInt(region.maxY()).putInt(region.maxZ());OptixNative.invalidate(context,batch.flip());}
  warmup.prepare(resident.keySet(),x,y,z);
  var updates=new ArrayList<RtGeometryStream.Section>();
  var iterator=resident.entrySet().iterator();while(iterator.hasNext()){var e=iterator.next();var k=e.getKey();if(Minecraft.getInstance().level==null||!Minecraft.getInstance().level.getChunkSource().hasChunk(k.x(),k.z())||Math.abs(k.x()*16.-x)>144||Math.abs(k.y()*16.-y)>144||Math.abs(k.z()*16.-z)>144){updates.add(new RtGeometryStream.Section(k,Long.MAX_VALUE,new byte[0]));iterator.remove();}}
  for(var section:RtGeometryStream.drain(4)){var k=section.key();if(Minecraft.getInstance().level==null||!Minecraft.getInstance().level.getChunkSource().hasChunk(k.x(),k.z())||Math.abs(k.x()*16.-x)>144||Math.abs(k.y()*16.-y)>144||Math.abs(k.z()*16.-z)>144)continue;if(!resident.containsKey(k)&&resident.size()>=512){var farthest=resident.keySet().stream().max(Comparator.comparingDouble(key->distance(key,x,y,z))).orElseThrow();if(distance(k,x,y,z)>=distance(farthest,x,y,z))continue;resident.remove(farthest);updates.add(new RtGeometryStream.Section(farthest,Long.MAX_VALUE,new byte[0]));}if(resident.containsKey(k)&&resident.get(k)==section.version())continue;updates.add(section);if(section.vertices()==0)resident.remove(k);else resident.put(k,section.version());}
  if(!updates.isEmpty()){long start=System.nanoTime();OptixNative.updateSections(context,RtGeometryStream.batch(updates));buildNs=System.nanoTime()-start;RenderPassProfile.cpu("rt_scene_submit",buildNs);builds+=updates.size();}sceneReady=!resident.isEmpty();
 }
 private void updateInstances(CommandEncoder encoder){
  var models=new ArrayList<RtDynamicStream.Model>();var slots=new ArrayList<Integer>();boolean changed=false;var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
  for(var model:RtDynamicStream.snapshot()){Integer slot=entitySlots.get(model.texture());if(slot==null){if(entitySlots.size()>=64)continue;slot=entitySlots.size();entitySlots.put(model.texture(),slot);changed=true;
   try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight RT model texture").withRenderArea(new RenderPass.RenderArea((slot&7)*256,(slot>>3)*256,256,256)).withColorAttachment(entityAtlasView,Optional.empty()))){pass.setPipeline(ATLAS);pass.bindTexture("Sampler0",model.texture(),nearest);pass.draw(3,1,0,0);}
  }models.add(model);slots.add(slot);}
  if(changed)encoder.copyTextureToBuffer(entityAtlas,interop.buffers[12],0,()->{},0);
  int size=0;for(var model:models)size+=72+model.geometry().length;var batch=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());for(int i=0;i<models.size();i++){var model=models.get(i);batch.putLong(model.id()).putLong(model.version()).putInt(model.geometry().length/40).putInt(slots.get(i));for(float value:model.transform())batch.putFloat(value);batch.put(model.geometry());}long start=System.nanoTime();OptixNative.updateInstances(context,batch.flip());RenderPassProfile.cpu("rt_instance_submit",System.nanoTime()-start);dynamicModels=models.size();sceneReady|=!models.isEmpty();
 }
 private void renderEnvironment(CommandEncoder encoder,EnvironmentPass weather,ShadowRenderer shadows){
  var sky=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.skyRenderState;var light=shadows.light();var env=LightingEnvironment.polished(light,sky.skybox==net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD,sky.sunAngle,sky.rainBrightness);var sun=light.direction();
  try(var stack=MemoryStack.stackPush()){encoder.writeToBuffer(environmentSettings.slice(),Std140Builder.onStack(stack,64).putVec4(env.directR(),env.directG(),env.directB(),env.directStrength()).putVec4(env.skyR(),env.skyG(),env.skyB(),env.skyStrength()).putVec4(env.horizonR(),env.horizonG(),env.horizonB(),env.lowerHemisphere()).putVec4(sun.x,sun.y,sun.z,0).get());}
  try(var profile=RenderPassProfile.begin(encoder,"rt_environment_map");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight shared environment radiance").withRenderArea(new RenderPass.RenderArea(0,0,256,128)).withColorAttachment(environmentView,Optional.empty()))){pass.setPipeline(ENVIRONMENT);pass.setUniform("EnvironmentSettings",weather.settings());pass.setUniform("RtEnvironmentSettings",environmentSettings);pass.draw(3,1,0,0);}
  encoder.copyTextureToBuffer(environmentMap,interop.buffers[14],0,()->{},0);
 }
 private static double distance(SectionKey key,double x,double y,double z){double dx=key.x()*16.+8-x,dy=key.y()*16.+8-y,dz=key.z()*16.+8-z;return dx*dx+dy*dy+dz*dz;}
 private long builds,buildNs;
 private static byte[] deviceUuid(VulkanDevice vk){try(var s=MemoryStack.stackPush()){var id=VkPhysicalDeviceIDProperties.calloc(s).sType$Default();var props=VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(id.address());VK11.vkGetPhysicalDeviceProperties2(vk.vkDevice().getPhysicalDevice(),props);byte[] uuid=new byte[16];id.deviceUUID().get(uuid);return uuid;}}
 private void init(VulkanDevice vk,CommandEncoder encoder,RenderTarget target,MaterialCapture material,int w,int h)throws Exception{
  width=w;height=h;iw=material.rtAtlas(0).getWidth(0);ih=material.rtAtlas(0).getHeight(0);var nativeAtlas=((TextureAtlas)Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)).getTextureView();aw=Math.min(2048,nativeAtlas.getWidth(0));ah=Math.min(2048,nativeAtlas.getHeight(0));long pixels=(long)w*h*16;
  org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX importing external memory and semaphores");
  interop=new VulkanCudaInterop(vk,context,new long[]{pixels,pixels,pixels,pixels,pixels,pixels,pixels,(long)aw*ah*4,(long)iw*ih*4,256*1280*4,(long)iw*ih*4,pixels,2048L*2048*4,pixels,256L*128*16});
  org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RTX compiling Vulkan guide/composite pipelines");
  var device=RenderSystem.getDevice();for(var pipeline:List.of(CAPTURE,COMPOSITE,ATLAS,DIELECTRIC,ENVIRONMENT))if(!device.precompilePipeline(pipeline,RenderProbe.SHADERS).isValid())throw new IllegalStateException("RTX shader invalid");
  for(int i=0;i<4;i++){capture[i]=device.createTexture("VoxelLight RTX guide "+i,GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);captureViews[i]=device.createTextureView(capture[i]);}
  for(int i=0;i<5;i++){signals[i]=device.createTexture("VoxelLight RTX signal "+i,GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);signalViews[i]=device.createTextureView(signals[i]);}
  dielectric=device.createTexture("VoxelLight RT dielectric composition",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA32_FLOAT,w,h,1,1);dielectricView=device.createTextureView(dielectric);
  entityAtlas=device.createTexture("VoxelLight RT model atlas",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,2048,2048,1,1);entityAtlasView=device.createTextureView(entityAtlas);
  atlas=device.createTexture("VoxelLight RTX native texture copy",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,aw,ah,1,1);atlasView=device.createTextureView(atlas);
  composite=device.createTexture("VoxelLight RTX HDR composition",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,target.width,target.height,1,1);compositeView=device.createTextureView(composite);
  environmentMap=device.createTexture("VoxelLight shared HDR RT environment",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA32_FLOAT,256,128,1,1);environmentView=device.createTextureView(environmentMap);environmentSettings=device.createBuffer(()->"VoxelLight RT environment palette",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,64);
  frame=device.createBuffer(()->"VoxelLight RTX frame",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,144);
 }
 GpuTextureView composite(CommandEncoder encoder,RenderTarget target,MaterialCapture material,GpuTextureView hdr){if(!active)return hdr;var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);try(var profile=RenderPassProfile.begin(encoder,"rt_composite");var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight RTX signals").withRenderArea(new RenderPass.RenderArea(0,0,target.width,target.height)).withColorAttachment(compositeView,Optional.empty()))){pass.setPipeline(COMPOSITE);pass.setUniform("RtFrame",frame);pass.bindTexture("CurrentHdr",hdr,nearest);pass.bindTexture("SceneDepth",target.getDepthTextureView(),nearest);pass.bindTexture("MaterialNormal",material.view(1),nearest);pass.bindTexture("MaterialPbr",material.view(4),nearest);pass.bindTexture("MaterialTable",material.materialTable(),nearest);pass.bindTexture("RtMaterial",captureViews[3],nearest);pass.bindTexture("RtSurfaceKey",signalViews[4],nearest);pass.bindTexture("RtPosition",captureViews[0],nearest);pass.bindTexture("RtNormal",captureViews[1],nearest);pass.bindTexture("RtDiffuse",signalViews[0],nearest);pass.bindTexture("RtSpecular",signalViews[1],nearest);pass.bindTexture("RtTransmission",dielectricView,nearest);pass.draw(3,1,0,0);}return compositeView;}
 public String status(){long[] stats=context==0?new long[21]:OptixNative.stats(context);if(context==0)stats[6]=-1;return (context==0?", rtCacheEnabled=unknown, rtCachePath=unavailable, rtCacheLowWater=unknown, rtCacheHighWater=unknown":OptixNative.cacheStatus(context))+", rtReferenceCompile="+referenceCompileState+", rtReferenceFullRequested="+fullReferenceRequested+", rtReferenceSweepPercent="+(100.*stats[19]/Math.max(1,width*height))+", rtReferencePixelsPerFrame="+stats[20]+", rtReferenceSamples="+stats[8]+", rtEmissiveNeeTriangles="+stats[9]+", rtRaysPerPixel="+(stats[10]/(double)Math.max(1,width*height))+", rtShadowRaysPerPixel="+(stats[11]/(double)Math.max(1,width*height))+", rtAverageBounce="+(stats[12]/(double)Math.max(1,stats[18]))+", rtMediumEvents="+stats[13]+", rtLightSamples="+stats[14]+", rtBsdfSamples="+stats[15]+", rtMaterialLookups="+stats[16]+", rtCausticPhotons="+stats[17]+", rtReferenceFull="+fullReference+", rtReferenceAsync="+referenceInFlight+", rtReference="+reference+", rtReferenceTargetSpp="+referenceSpp+", rt="+state+", rtBenchmarkMismatches="+stats[6]+", rtBenchmarkMaxError="+(stats[7]/1e6)+", rtGas="+(stats[0]+stats[1])+", rtIasInstances="+(stats[0]+stats[2])+", rtCudaOwnedBytes="+stats[3]+", rtRetiringAllocations="+stats[5]+", rtRadianceCache="+((options&16)!=0)+", rtDynamicModels="+dynamicModels+", rtSections="+resident.size()+", rtPending="+RtGeometryStream.pending()+", rtBuilds="+builds+", rtWarmupNs="+warmup.compileNanos()+", rtBuildNs="+buildNs+", rtCpuStaging="+(active?"false":"inactive")+", rtTraversal=optix_rt, rtRadianceCacheBytes=196608, rtDenoiser="+((options&8)!=0?"OptiX temporal AOV":"off")+"";}
 private void closeResources(){warmup.close();if(context!=0){long retired=context;var retiredInterop=interop;context=0;interop=null;if(referenceInFlight){STARTUP.execute(()->{try{OptixNative.destroy(retired);}finally{if(retiredInterop!=null)Minecraft.getInstance().execute(retiredInterop::close);}});}else{OptixNative.destroy(retired);if(retiredInterop!=null)retiredInterop.close();}}if(interop!=null){interop.close();interop=null;}referenceInFlight=displayValid=false;for(int i=0;i<4;i++){if(captureViews[i]!=null){captureViews[i].close();captureViews[i]=null;}if(capture[i]!=null){capture[i].close();capture[i]=null;}}for(int i=0;i<5;i++){if(signalViews[i]!=null){signalViews[i].close();signalViews[i]=null;}if(signals[i]!=null){signals[i].close();signals[i]=null;}}if(dielectricView!=null){dielectricView.close();dielectricView=null;}if(dielectric!=null){dielectric.close();dielectric=null;}if(entityAtlasView!=null){entityAtlasView.close();entityAtlasView=null;}if(entityAtlas!=null){entityAtlas.close();entityAtlas=null;}entitySlots.clear();dynamicModels=0;
  if(atlasView!=null){atlasView.close();atlasView=null;}if(atlas!=null){atlas.close();atlas=null;}if(compositeView!=null){compositeView.close();compositeView=null;}if(composite!=null){composite.close();composite=null;}if(frame!=null){frame.close();frame=null;}if(environmentView!=null){environmentView.close();environmentView=null;}if(environmentMap!=null){environmentMap.close();environmentMap=null;}if(environmentSettings!=null){environmentSettings.close();environmentSettings=null;}resident.clear();assetsUploaded=active=sceneReady=false;}
 private void discardStartup(){if(startup!=null){startup.close();startup=null;}if(referenceStartup!=null){referenceStartup.close();referenceStartup=null;}startupStage=-1;}
 public void close(){closeResources();RtGeometryStream.enable(false);world=resources=Long.MIN_VALUE;}
 private static RenderPipeline.Builder base(String name,BindGroupLayout layout){return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/"+name)).withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight",name)).withBindGroupLayout(layout).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);}
 private float referenceExposure=.75f;
 void referenceExposure(float ev){referenceExposure=ev;}
 void displayFullReference(CommandEncoder encoder,RenderTarget target){
  if(!fullReferenceActive()||!displayValid)return;
  try(var stack=MemoryStack.stackPush()){encoder.writeToBuffer(environmentSettings.slice(),Std140Builder.onStack(stack,64).putVec4((float)Math.pow(2,referenceExposure),0,0,0).putVec4(0,0,0,0).putVec4(0,0,0,0).putVec4(0,0,0,0).get());}
  var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
  try(var profile=RenderPassProfile.begin(encoder,"rt_reference_display");var pass=encoder.createRenderPass(()->"VoxelLight full reference display",target.getColorTextureView(),Optional.empty())){pass.setPipeline(REFERENCE_DISPLAY);pass.setUniform("RtEnvironmentSettings",environmentSettings);pass.bindTexture("ReferenceRadiance",signalViews[0],sampler);pass.draw(3,1,0,0);}
 }
 private static RenderPipeline referenceDisplayPipeline(){return base("rt_reference_display",BindGroupLayout.builder().withSampler("ReferenceRadiance").withUniform("RtEnvironmentSettings",UniformType.UNIFORM_BUFFER).build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();}
 private static RenderPipeline capturePipeline(){var b=base("rt_capture",BindGroupLayout.builder().withSampler("SceneDepth").withSampler("MaterialDepth").withSampler("MaterialNormal").withSampler("MaterialAlbedo").withSampler("MaterialPbr").withSampler("MaterialTable").withUniform("RtFrame",UniformType.UNIFORM_BUFFER).build());for(int i=0;i<4;i++)b.withColorTargetState(i,new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL));return b.build();}
 private static RenderPipeline compositePipeline(){return base("rt_composite",BindGroupLayout.builder().withSampler("CurrentHdr").withSampler("SceneDepth").withSampler("MaterialNormal").withSampler("MaterialPbr").withSampler("MaterialTable").withSampler("RtMaterial").withSampler("RtSurfaceKey").withSampler("RtPosition").withSampler("RtNormal").withSampler("RtDiffuse").withSampler("RtSpecular").withSampler("RtTransmission").withUniform("RtFrame",UniformType.UNIFORM_BUFFER).build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)).build();}
 private static RenderPipeline dielectricPipeline(){return base("rt_dielectric",BindGroupLayout.builder().withSampler("RtDiffuse").withSampler("RtSpecular").withSampler("RtTransmission").withUniform("RtFrame",UniformType.UNIFORM_BUFFER).build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).build();}
 private static RenderPipeline environmentPipeline(){return base("rt_environment",BindGroupLayout.builder().withUniform("EnvironmentSettings",UniformType.UNIFORM_BUFFER).withUniform("RtEnvironmentSettings",UniformType.UNIFORM_BUFFER).build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).build();}
 private static RenderPipeline atlasPipeline(){return base("rt_atlas",BindGroupLayout.builder().withSampler("Sampler0").build()).withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).build();}
}
