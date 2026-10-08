package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.voxellight.adapter.RtGeometryStream;
import com.voxellight.adapter.RenderPassProfile;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/** Borrowed MC Vulkan device/queue; owns only RT resources and never creates a CUDA/OptiX context. */
public final class VulkanRtContext implements AutoCloseable {
    private final VulkanDevice device;
    private final VulkanRtPipeline pipeline;
    private final String primaryStage,indirectStage,resolveStage;
    public final VulkanRtScene scene;
    private VulkanRtBuffer output,paths,camera,feedback,pathMedia,pathAovs;
    private final java.util.concurrent.ConcurrentLinkedQueue<com.voxellight.world.SectionKey> pageRequests=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean feedbackPending;
    private long pageRequestCount;
    private final boolean transport,material;
    private final VulkanRtPipeline indirect,resolve;
    private VulkanRtBuffer[] pathBanks;
    private final long maxStorageRange;
    private final boolean indirectTracing;
    private final boolean queryAvailable,ommAvailable,serAvailable,queryEnabled,serEnabled,ommEnabled;
    private boolean compact,queueAvailable;
    private int compactMask;
    private final com.voxellight.rt.RtQueueCalibration queueCalibration=new com.voxellight.rt.RtQueueCalibration();
    private final java.util.function.Consumer<com.voxellight.debug.PassMetrics.Sample> timingObserver=queueCalibration::timing;
    private String lastWorkload="unobserved";
    private static final int FEEDBACK_HEADER=1648;
    private VulkanRtPipeline visibilityTrace,visibilityQuery,primaryVisibility,primaryShade,cacheTrain;
    private final boolean primarySplit;
    public long maxPixels(int spp){long limit=Math.min(maxStorageRange/(material?spp*288:spp*64),(1024L*1024*1024-16L*FEEDBACK_HEADER-64)/(material?(primarySplit?480L:448L)*spp+128:80L*spp));if(runtimeFlags()!=0)limit=Math.min(limit,Math.min(com.voxellight.rt.RtRealtimeLayout.maxPixels(maxStorageRange,spp), (1024L*1024*1024-16L*(FEEDBACK_HEADER+655360+6144)-64)/(spp*528L+384)));return Math.max(1,limit);}
    private final boolean realtime;
    private final com.voxellight.rt.RtExecutionOptions.Shader shaderMode;
    private final com.voxellight.rt.RtExecutionOptions.Shadow shadowMode;
    private final boolean iterative;
    private boolean runtimeAllocated,previousValid;
    private int runtimeEpoch=1;
    private long runtimeGeneration=Long.MIN_VALUE;
    private final com.voxellight.rt.RtRealtimeLighting runtimeLighting=new com.voxellight.rt.RtRealtimeLighting();
    private long runtimeSceneResets,runtimeLightResets;
    private long runtimeEventCursor=com.voxellight.rt.RtInvalidationQueue.generation(),runtimeRawEvents,runtimeLightOnlyEvents,runtimeEventOverflows;
    private String runtimeResetReason="initial";
    private final Matrix4f previousClip=new Matrix4f();
    private float previousX,previousY,previousZ;
    private String runtimeMetrics="unobserved";
    private int runtimeFlags(){if(!material||!realtime)return 0;int flags=com.voxellight.rt.RtExecutionOptions.realtime().flags(realtime);return flags|((flags&1)!=0&&com.voxellight.rt.RtExecutionOptions.cache()==com.voxellight.rt.RtExecutionOptions.Cache.PRIMARY?4:0);}
    public void runtimeLighting(float[] signature){
        if(runtimeEventCursor!=com.voxellight.rt.RtInvalidationQueue.generation()){
            var updates=com.voxellight.rt.RtInvalidationQueue.changesSince(runtimeEventCursor);runtimeEventCursor=updates.generation();
            runtimeRawEvents+=updates.changes().size();if(updates.overflow())runtimeEventOverflows++;
            for(var event:updates.changes())if(event.reasons()==com.voxellight.world.WorldSceneBridge.LIGHT)runtimeLightOnlyEvents++;
        }
        long generation=scene.historyGeneration();boolean changed=runtimeLighting.changed(signature);if(generation!=runtimeGeneration||changed){if(generation!=runtimeGeneration){runtimeSceneResets++;runtimeResetReason="committed RT terrain content";}else{runtimeLightResets++;runtimeResetReason=runtimeLighting.reason();}runtimeGeneration=generation;runtimeEpoch++;previousValid=false;}}
    private long runtimeBytes(int w,int h,int spp){return com.voxellight.rt.RtRealtimeLayout.bytes((long)w*h,spp);}
    private int frame;
    private int width,height,samples=1;
    private boolean closed,reconstructionGuides=true,rrJitter;
    public void rrJitter(boolean value){rrJitter=value;}
    public int lastFrame(){return Math.max(0,frame-1);}
    public void reconstructionGuides(boolean value){reconstructionGuides=value;}
    private final String optionalCapabilities;
    public VulkanRtContext(VulkanDevice device) {this(device,false);}
    public VulkanRtContext(VulkanDevice device,boolean transport) {this(device,transport,false);}
    public VulkanRtContext(VulkanDevice device,boolean transport,boolean material) {this(device,transport,material,false);}
    public VulkanRtContext(VulkanDevice device,boolean transport,boolean material,boolean realtime) {
        this.realtime=realtime;primarySplit=material&&realtime&&com.voxellight.rt.RtExecutionOptions.primary()==com.voxellight.rt.RtExecutionOptions.Primary.SPLIT;iterative=material&&realtime&&com.voxellight.rt.RtExecutionOptions.integrator()==com.voxellight.rt.RtExecutionOptions.Integrator.ITERATIVE;shadowMode=realtime?com.voxellight.rt.RtExecutionOptions.shadow():com.voxellight.rt.RtExecutionOptions.Shadow.EXACT;shaderMode=com.voxellight.rt.RtExecutionOptions.shader();
        if(material&&!transport)throw new IllegalArgumentException("Material transport requires continuations");
        this.transport=transport;this.material=material;
        this.device=device;indirectTracing=material&&VulkanRtCapabilities.indirectTracing(device.vkDevice().getPhysicalDevice());
        var capabilities=VulkanRtCapabilities.query(device.vkDevice().getPhysicalDevice());
        var physical=device.vkDevice().getPhysicalDevice();
        queryAvailable=material&&device.vkDevice().getCapabilities().VK_KHR_ray_query&&capabilities.extensions().contains("VK_KHR_ray_query")&&VulkanRtCapabilities.rayQuery(physical);
        queryEnabled=queryAvailable&&com.voxellight.rt.RtExecutionOptions.visibility()==com.voxellight.rt.RtExecutionOptions.Visibility.QUERY;
        serAvailable=material&&device.vkDevice().getCapabilities().VK_NV_ray_tracing_invocation_reorder&&capabilities.extensions().contains("VK_NV_ray_tracing_invocation_reorder")&&VulkanRtCapabilities.reorder(physical);serEnabled=serAvailable&&com.voxellight.rt.RtExecutionOptions.ser();
        ommAvailable=material&&device.vkDevice().getCapabilities().VK_EXT_opacity_micromap&&capabilities.extensions().contains("VK_EXT_opacity_micromap")&&VulkanRtCapabilities.micromap(physical);ommEnabled=ommAvailable&&com.voxellight.rt.RtExecutionOptions.omm();
        primaryStage=com.voxellight.rt.RtExecutionOptions.stage("material_primary",realtime,queryEnabled,serEnabled);
        indirectStage=com.voxellight.rt.RtExecutionOptions.stage("material_indirect",realtime,queryEnabled,serEnabled)+(iterative?"_iterative":"");
        resolveStage=com.voxellight.rt.RtExecutionOptions.stage("material_resolve",realtime,false,false);
        if(!capabilities.supported())throw new IllegalStateException(capabilities.reason());
        try(var stack=MemoryStack.stackPush()){var properties=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(),properties);maxStorageRange=Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());if(material&&maxStorageRange<128L*1024*1024)throw new IllegalStateException("Stable material geometry requires 128 MiB storage-buffer range");if(material&&properties.limits().maxPerStageDescriptorStorageBuffers()<15)throw new IllegalStateException("Material batch requires 15 storage-buffer bindings");}
        var optional=new java.util.TreeSet<>(capabilities.extensions());optional.retainAll(VulkanRtCapabilities.OPTIONAL);optionalCapabilities=optional.toString();
        org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT mandatory features enabled; optional advertised (feature-gated execution): {}",optional);
        if(!device.vkDevice().getCapabilities().VK_KHR_ray_tracing_pipeline || !device.vkDevice().getCapabilities().VK_KHR_acceleration_structure)
            throw new IllegalStateException("RT extensions were not enabled on Minecraft Vulkan device");
        try(var stack=MemoryStack.stackPush()) {
            var acceleration=VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(),VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration.address()));
            scene=new VulkanRtScene(device,acceleration.minAccelerationStructureScratchOffsetAlignment(),material);
        }
        try {pipeline=new VulkanRtPipeline(device,material?primaryStage:transport?"transport_primary":null);}
        catch(RuntimeException error){scene.close();throw error;}
        VulkanRtPipeline continuation=null;
        try {if(transport)continuation=new VulkanRtPipeline(device,material?indirectStage:"transport_indirect");}
        catch(RuntimeException error){pipeline.close();scene.close();throw error;}
        indirect=continuation;
        VulkanRtPipeline sampleResolve=null;
        try {sampleResolve=transport?new VulkanRtPipeline(device,material?resolveStage:"transport_resolve"):null;camera=new VulkanRtBuffer(device,96,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);}
        catch(RuntimeException error){if(camera!=null)camera.close();if(sampleResolve!=null)sampleResolve.close();if(indirect!=null)indirect.close();pipeline.close();scene.close();throw error;}
        resolve=sampleResolve;RenderPassProfile.observe(timingObserver);
    }
    public boolean render(CommandEncoder encoder,List<RtGeometryStream.Section> changes,Matrix4f inverseClip,double x,double y,double z,GpuTexture destination,int width,int height) {
        return render(encoder,changes,inverseClip,x,y,z,destination,width,height,null);
    }
    public boolean render(CommandEncoder encoder,List<RtGeometryStream.Section> changes,Matrix4f inverseClip,double x,double y,double z,GpuTexture destination,int width,int height,VulkanRtBuffer assets) {
        return renderBatch(encoder,changes,inverseClip,x,y,z,destination,width,height,assets,1);
    }
    public boolean renderBatch(CommandEncoder encoder,List<RtGeometryStream.Section> changes,Matrix4f inverseClip,double x,double y,double z,GpuTexture destination,int width,int height,VulkanRtBuffer assets,int spp){
        if(material&&assets==null)return false;
        if(closed)throw new IllegalStateException("Closed RT context");
        if(!changes.isEmpty()){prepareScene(encoder,changes,x,y,z);commitScene(encoder,x,y,z);}
        if(scene.tlas()==0)return false;
        RenderPassProfile.workload(width,height,spp,scene.generation());
        if(spp<1||spp>8)throw new IllegalArgumentException("Frame spp must be 1..8");
        if(this.width!=width||this.height!=height||samples!=spp||runtimeAllocated!=(runtimeFlags()!=0)) {
            runtimeAllocated=runtimeFlags()!=0;previousValid=false;runtimeEpoch++;
            queueAvailable=material&&indirectTracing&&(FEEDBACK_HEADER+(long)width*height*spp*(primarySplit?4:2))*16<=maxStorageRange;
            if(material){if(feedback!=null)feedback.close();feedback=new VulkanRtBuffer(device,runtimeAllocated?runtimeBytes(width,height,spp):(FEEDBACK_HEADER+(long)width*height*spp*(primarySplit?4:2))*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);encoder.writeToBuffer(feedback.slice(),ByteBuffer.allocateDirect(Math.toIntExact(feedback.size())));}
            if(output!=null)output.close();output=new VulkanRtBuffer(device,(long)width*height*spp*16+(material?64+(long)width*height*128:0),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);this.width=width;this.height=height;samples=spp;
            if(pathBanks!=null){for(var bank:pathBanks)bank.close();pathBanks=null;paths=null;}else if(paths!=null){paths.close();paths=null;}
            if(material){if(pathMedia!=null)pathMedia.close();pathMedia=new VulkanRtBuffer(device,(long)width*height*spp*288,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);pathBanks=new VulkanRtBuffer[spp];for(int lane=0;lane<spp;lane++)pathBanks[lane]=new VulkanRtBuffer(device,(long)width*height*64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);paths=pathBanks[0];if(pathAovs!=null)pathAovs.close();pathAovs=new VulkanRtBuffer(device,(long)width*height*spp*48,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);}
            else if(transport)paths=new VulkanRtBuffer(device,(long)width*height*spp*64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
        compact=!iterative&&queueAvailable&&switch(com.voxellight.rt.RtExecutionOptions.queue()){
            case FIXED->false;case COMPACT,HYBRID->true;case AUTO->queueCalibration.compact(RenderPassProfile.frameId(),width,height,spp,RenderPassProfile.enabled());
        };
        compactMask=compact?(com.voxellight.rt.RtExecutionOptions.queue()==com.voxellight.rt.RtExecutionOptions.Queue.HYBRID?queueCalibration.hybridMask(width,height,spp):62):0;
        {
            boolean lightFeedback=com.voxellight.rt.RtExecutionOptions.direct()==com.voxellight.rt.RtExecutionOptions.Direct.RIS&&(frame%32)==0;
            boolean sampledCounters=RenderPassProfile.enabled()&&(frame%8)==0;
            boolean needAlive=!iterative&&com.voxellight.rt.RtExecutionOptions.queue()==com.voxellight.rt.RtExecutionOptions.Queue.HYBRID&&queueCalibration.sampleAlive(frame,width,height,spp);
            boolean sampledAlive=!sampledCounters&&needAlive;
            if(sampledCounters||lightFeedback)RenderPassProfile.markCounterFrame();
            var data=ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());inverseClip.get(0,data);
            data.position(64).putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((material&&reconstructionGuides?1:0)+(sampledCounters?2:0)+(rrJitter?524288:0)+(primarySplit&&queueAvailable?2097152:0)+(com.voxellight.rt.RtExecutionOptions.sampling()==com.voxellight.rt.RtExecutionOptions.Sampling.SHIFT?1048576:0)+(sampledAlive?2048:0)+(shadowMode==com.voxellight.rt.RtExecutionOptions.Shadow.FAST?262144:0)+(lightFeedback?64:0)+(com.voxellight.rt.RtExecutionOptions.direct()==com.voxellight.rt.RtExecutionOptions.Direct.RIS?128:0)+(compactMask!=0?4:0)+(compactMask*4096)+(scene.hasOpaque()&&com.voxellight.rt.RtExecutionOptions.visibility()!=com.voxellight.rt.RtExecutionOptions.Visibility.LEGACY?8:0)+(scene.hasNonOpaque()?16:0)+(ommEnabled?256:0)+(sampledCounters?512:0)+(queryAvailable&&((RenderPassProfile.frameId()/8)&1)!=0?1024:0)).putInt(width).putInt(height).putInt(transport?frame++:0).putInt(spp).flip();
            encoder.writeToBuffer(camera.slice(),data);
            if(material){
                encoder.writeToBuffer(feedback.slice(0,16),ByteBuffer.allocateDirect(16));
                var counters=ByteBuffer.allocateDirect((544-513)*16).order(ByteOrder.nativeOrder());
                for(int bounce=0;bounce<6;bounce++){int offset=(520+bounce-513)*16;counters.putInt(offset,0).putInt(offset+4,1).putInt(offset+8,1);}
                encoder.writeToBuffer(feedback.slice(513*16,(544-513)*16),counters);
                var policy=ByteBuffer.allocateDirect(7*16).order(ByteOrder.nativeOrder());
                policy.putInt(0,runtimeFlags()).putInt(4,runtimeEpoch).putInt(8,runtimeLighting.gradual()?1:0).putInt(12,1024);
                previousClip.get(2*16,policy);policy.putFloat(6*16,previousX).putFloat(6*16+4,previousY).putFloat(6*16+8,previousZ).putInt(6*16+12,previousValid?1:0);
                encoder.writeToBuffer(feedback.slice(1568*16,7*16),policy);
                var policyCounters=ByteBuffer.allocateDirect(16*16).order(ByteOrder.nativeOrder());policyCounters.putInt(15*16+4,1).putInt(15*16+8,1);encoder.writeToBuffer(feedback.slice(1600*16,16*16),policyCounters);
            }
            if(primarySplit&&primaryVisibility==null)primaryVisibility=new VulkanRtPipeline(device,runtimeFlags()==0?"material_primary_visibility_full":"material_primary_visibility_realtime");
            if(primarySplit&&primaryShade==null)primaryShade=new VulkanRtPipeline(device,primaryStage.replace("material_primary","material_primary_shade"));
            if((runtimeFlags()&1)!=0&&cacheTrain==null)cacheTrain=new VulkanRtPipeline(device,"material_cache_train");
            var nativeEncoder=device.createCommandEncoder();
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_batch_"+(com.voxellight.rt.RtExecutionOptions.queue()==com.voxellight.rt.RtExecutionOptions.Queue.HYBRID?"hybrid":compact?"compact":"fixed"));var stack=MemoryStack.stackPush()) {
                var command=nativeEncoder.allocateAndBeginTransientCommandBuffer();
                VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_UNIFORM_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                pipeline.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
                if(primarySplit){
                    primaryVisibility.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
                    try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_primary_visibility")){primaryVisibility.dispatch(command,width,height,spp);}
                    VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                    primaryShade.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
                    try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_primary_shade")){if(queueAvailable)primaryShade.dispatchIndirect(command,feedback.address()+520L*16,0);else primaryShade.dispatch(command,width,height,spp);}
                }else try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_primary")){pipeline.dispatch(command,width,height,spp);}
                if(transport){
                    indirect.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
                    if(iterative){
                        VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                        try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_indirect_iterative")){indirect.dispatchBounce(command,width,height,spp,1);}
                    }else for(int bounce=1;bounce<6;bounce++){
                        VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                        try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_bounce_"+bounce)){if((compactMask&(1<<bounce))!=0)indirect.dispatchIndirect(command,feedback.address()+(520L+bounce)*16,bounce);else indirect.dispatchBounce(command,width,height,spp,bounce);}
                    }
                    VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                    if((runtimeFlags()&1)!=0){
                        cacheTrain.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
                        try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_cache_train")){if(indirectTracing)cacheTrain.dispatchIndirect(command,feedback.address()+1615L*16,0);else cacheTrain.dispatch(command,1024,1,1);}
                        VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                    }
                    resolve.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_sample_resolve")){resolve.dispatch(command,width,height,1);}
                }
                VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));nativeEncoder.execute(command);
                pipeline.retireDescriptors();if(primaryVisibility!=null)primaryVisibility.retireDescriptors();if(primaryShade!=null)primaryShade.retireDescriptors();if(cacheTrain!=null)cacheTrain.retireDescriptors();if(indirect!=null)indirect.retireDescriptors();if(resolve!=null)resolve.retireDescriptors();
            }
            if(material&&sampledCounters&&scene.hasOpaque()&&com.voxellight.rt.RtExecutionOptions.visibility()!=com.voxellight.rt.RtExecutionOptions.Visibility.LEGACY)benchmarkVisibility(assets);
            encoder.copyBufferToTexture(output.slice(),0,0,width,height,destination,0,0,width,height,0,0);
            if(material&&!feedbackPending&&((frame-1)%8)==0)readPageFeedback(encoder,sampledCounters||sampledAlive);
            previousClip.set(inverseClip).invert();previousX=(float)x;previousY=(float)y;previousZ=(float)z;previousValid=true;
            return true;
        }
    }
    private void benchmarkVisibility(VulkanRtBuffer assets){
        if(visibilityTrace==null)visibilityTrace=new VulkanRtPipeline(device,"material_visibility_trace");
        if(visibilityQuery==null&&queryAvailable)visibilityQuery=new VulkanRtPipeline(device,"material_visibility_query");
        var encoder=device.createCommandEncoder();try(var stack=MemoryStack.stackPush()){
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
            boolean queryFirst=visibilityQuery!=null&&((RenderPassProfile.frameId()/8)&1)!=0;
            var first=queryFirst?visibilityQuery:visibilityTrace;var second=queryFirst?visibilityTrace:visibilityQuery;
            replayVisibility(command,first,assets,"vulkan_rt_visibility_"+(queryFirst?"query":"trace")+"_256_first");
            if(second!=null){
                VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                replayVisibility(command,second,assets,"vulkan_rt_visibility_"+(queryFirst?"trace":"query")+"_256_second");
            }
            VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
            VulkanRtCapabilities.check(vkEndCommandBuffer(command));encoder.execute(command);visibilityTrace.retireDescriptors();if(visibilityQuery!=null)visibilityQuery.retireDescriptors();
        }
    }
    private void replayVisibility(VkCommandBuffer command,VulkanRtPipeline replay,VulkanRtBuffer assets,String name){
        replay.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia,pathAovs,scene.opaqueTlas());
        try(var pass=RenderPassProfile.beginNative(command,name)){replay.dispatch(command,256,1,1);}
    }
    private void readPageFeedback(CommandEncoder encoder,boolean sampledAlive){
        feedbackPending=true;var read=com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(()->"VoxelLight RT page requests",com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST|com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ,1616*16);
        encoder.copyToBuffer(feedback.slice(0,1616*16),read.slice());
        final int sampledWidth=width,sampledHeight=height,sampledSpp=samples;final long sampledFrame=RenderPassProfile.frameId(),sampledScene=scene.generation();final boolean measured=RenderPassProfile.enabled();final long sampledEmitterGeneration=scene.emitterGeneration();
        com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(()->{try(var map=read.map(true,false)){if(!closed){var data=map.data().order(ByteOrder.nativeOrder());int count=Math.min(512,Math.max(0,data.getInt(0)));pageRequestCount+=count;for(int i=0;i<count;i++){int offset=(i+1)*16;pageRequests.add(new com.voxellight.world.SectionKey(data.getInt(offset),data.getInt(offset+4),data.getInt(offset+8)));}
                var active=new long[6];for(int bounce=0;bounce<6;bounce++)active[bounce]=Integer.toUnsignedLong(data.getInt((514+bounce)*16));
                var lightCount=new long[8];var lightVisible=new long[8];for(int i=0;i<8;i++){lightCount[i]=Integer.toUnsignedLong(data.getInt((528+i)*16));lightVisible[i]=Integer.toUnsignedLong(data.getInt((528+i)*16+4));}scene.proposalFeedback(sampledEmitterGeneration,lightCount,lightVisible);
                if(sampledAlive)queueCalibration.alive(sampledFrame,sampledWidth,sampledHeight,sampledSpp,sampledScene,active);
                var direct=new long[6][4];for(int b=0;b<6;b++)for(int c=0;c<4;c++)direct[b][c]=Integer.toUnsignedLong(data.getInt((b<4?536+b:538+b)*16+c*4));
                var runtime=new long[com.voxellight.debug.RtWorkMetrics.REALTIME_COLUMNS.length];for(int i=0;i<runtime.length;i++)runtime[i]=Integer.toUnsignedLong(data.getInt((i<16?1600*16+i*4:1605*16+(i-16)*4)));runtimeMetrics=Arrays.toString(runtime).replace(',','/');
                if(measured&&sampledAlive)RenderPassProfile.rayWorkload(sampledFrame,sampledWidth,sampledHeight,sampledSpp,sampledScene,active,Integer.toUnsignedLong(data.getInt(513*16)),Integer.toUnsignedLong(data.getInt(513*16+4)),Integer.toUnsignedLong(data.getInt(540*16)),Math.min(256,Integer.toUnsignedLong(data.getInt(541*16))),Integer.toUnsignedLong(data.getInt(541*16+4)),direct,runtime);
                if(measured&&sampledAlive)lastWorkload="frame:"+sampledFrame+"/"+sampledWidth+"x"+sampledHeight+"/"+sampledSpp+"spp/active:"+Arrays.toString(active).replace(',','/')+"/shadow:"+Integer.toUnsignedLong(data.getInt(513*16))+"/anyHit:"+Integer.toUnsignedLong(data.getInt(513*16+4));
            }}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("RT page feedback unavailable",error);}finally{read.close();feedbackPending=false;}});
    }
    public java.util.Set<com.voxellight.world.SectionKey> drainPageRequests(){var result=new java.util.LinkedHashSet<com.voxellight.world.SectionKey>();com.voxellight.world.SectionKey key;while((key=pageRequests.poll())!=null){if(result.size()<256)result.add(key);}return result;}
    public void copyGuide(CommandEncoder encoder,int plane,GpuTexture destination){
        if(!material||plane<0||plane>=8)throw new IllegalArgumentException("Invalid guide plane");
        long offset=((long)width*height*samples+4+(long)plane*width*height)*16;
        encoder.copyBufferToTexture(output.slice(offset,(long)width*height*16),0,0,width,height,destination,0,0,width,height,0,0);
    }
    public void copyGuideToBuffer(CommandEncoder encoder,int plane,com.mojang.blaze3d.buffers.GpuBufferSlice destination){
        if(!material||plane<0||plane>=8)throw new IllegalArgumentException("Invalid guide plane");
        long offset=((long)width*height*samples+4+(long)plane*width*height)*16;
        encoder.copyToBuffer(output.slice(offset,(long)width*height*16),destination);
    }
    public void copyBeautyToBuffer(CommandEncoder encoder,com.mojang.blaze3d.buffers.GpuBufferSlice destination){encoder.copyToBuffer(output.slice(0,(long)width*height*16),destination);}
    public void copyLightingDiagnostic(CommandEncoder encoder,com.mojang.blaze3d.buffers.GpuBuffer target){
        if(material&&output!=null)encoder.copyToBuffer(output.slice((long)width*height*samples*16,64),target.slice(32,64));
    }
    public void prepareScene(CommandEncoder encoder,List<RtGeometryStream.Section> changes,double x,double y,double z){
        if(closed)throw new IllegalStateException("Closed RT context");
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_scene")){scene.collect(changes);}
    }
    public void commitScene(CommandEncoder encoder,double x,double y,double z){
        if(closed)throw new IllegalStateException("Closed RT context");
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_scene_commit")){scene.commit(encoder,x,y,z);}
    }
    public com.voxellight.rt.RtBenchmarkState benchmarkState(boolean realtime,boolean frozen){
        if(closed||!material||width==0)return null;
        return new com.voxellight.rt.RtBenchmarkState(width,height,samples,realtime,frozen,queryAvailable,queueAvailable,ommAvailable,serAvailable,
            com.voxellight.rt.RtExecutionOptions.visibility()==com.voxellight.rt.RtExecutionOptions.Visibility.LEGACY?com.voxellight.rt.RtExecutionOptions.Visibility.LEGACY:queryEnabled?com.voxellight.rt.RtExecutionOptions.Visibility.QUERY:com.voxellight.rt.RtExecutionOptions.Visibility.TRACE,
            !iterative&&com.voxellight.rt.RtExecutionOptions.queue()==com.voxellight.rt.RtExecutionOptions.Queue.HYBRID&&queueAvailable?com.voxellight.rt.RtExecutionOptions.Queue.HYBRID:compact?com.voxellight.rt.RtExecutionOptions.Queue.COMPACT:com.voxellight.rt.RtExecutionOptions.Queue.FIXED,ommEnabled,serEnabled,com.voxellight.adapter.RtMaterialCoverage.opacityValid(),scene.hasOpaque(),scene.terrainSignature(),scene.generation(),scene.resident().size(),scene.sceneBytes(),com.voxellight.rt.RtExecutionOptions.direct(),com.voxellight.rt.RtExecutionOptions.sceneUpdate(),runtimeFlags()==0?com.voxellight.rt.RtExecutionOptions.Realtime.FULL:com.voxellight.rt.RtExecutionOptions.realtime(),shaderMode,shadowMode,com.voxellight.VoxelLightClient.probe().actualRtWorld(),iterative?com.voxellight.rt.RtExecutionOptions.Integrator.ITERATIVE:com.voxellight.rt.RtExecutionOptions.Integrator.WAVEFRONT,primarySplit?com.voxellight.rt.RtExecutionOptions.Primary.SPLIT:com.voxellight.rt.RtExecutionOptions.Primary.MONOLITHIC,com.voxellight.rt.RtExecutionOptions.cache(),com.voxellight.rt.RtExecutionOptions.sampling());
    }
    public String status() { return "rtPrimary="+(primarySplit?"SPLIT":"MONOLITHIC")+", primaryHitBytes="+(primarySplit?(long)width*height*samples*32:0)+", cacheTrainingRequestBytes="+(runtimeAllocated?98304:0)+", rtCache="+com.voxellight.rt.RtExecutionOptions.cache()+", rtSampling="+com.voxellight.rt.RtExecutionOptions.sampling()+", rtIntegrator="+(iterative?"ITERATIVE":"WAVEFRONT")+", rtShadow="+shadowMode+", rtShader="+shaderMode+", rtPrimaryStage="+(primarySplit?(runtimeFlags()==0?"material_primary_visibility_full + ":"material_primary_visibility_realtime + ")+primaryStage.replace("material_primary","material_primary_shade"):primaryStage)+", rtIndirectStage="+indirectStage+", rtResolveStage="+resolveStage+", rtRealtimePolicy="+(runtimeFlags()==0?"FULL":com.voxellight.rt.RtExecutionOptions.realtime())+", radianceCacheEpoch="+runtimeEpoch+", realtimeLightingGradual="+runtimeLighting.gradual()+", realtimeResetReason="+runtimeResetReason+", realtimeSceneResets="+runtimeSceneResets+", realtimeRawEvents="+runtimeRawEvents+", realtimeLightOnlyEvents="+runtimeLightOnlyEvents+", realtimeEventOverflows="+runtimeEventOverflows+", realtimeLightResets="+runtimeLightResets+", realtimeStateBytes="+(runtimeAllocated?feedback.size():0)+", realtimeCounters="+runtimeMetrics+", "+(material?"vulkanRt=material transport experimental, material=Material 3/LabPBR, mediumStack=8, cutout=any-hit, bounces=6, environment=shared HDR 256x128, environmentSampling=GPU solid-angle CDF, lightNee=sun/moon+environment+held, lightMis=power heuristic, cameraWater=initialized, ":transport?"vulkanRt=geometry transport test, material=grey diffuse, bounces=6, ":"vulkanRt=normal POC, ")+"reconstruction=external frame reconstruction, internalResolution="+width+"x"+height+", recursion=1, sppPerFrame="+samples+", scheduling="+(compactMask==0?"fixed batch":compactMask==62?"GPU compact continuation":"hybrid fixed/compact continuation")+", compactBounceMask="+compactMask+", queuePolicy="+com.voxellight.rt.RtExecutionOptions.queue()+", queueCalibration="+queueCalibration.status()+", rayWorkload="+lastWorkload+", rtMissPageRequests="+pageRequestCount+", optionalAdvertised="+optionalCapabilities+", optionalEnabled="+(queryEnabled?"ray_query/":"")+(ommEnabled?"OMM/":"")+(serEnabled?"SER":"")+", visibility="+(shadowMode==com.voxellight.rt.RtExecutionOptions.Shadow.FAST?"single TraceRay (transparent approximation)":com.voxellight.rt.RtExecutionOptions.visibility()==com.voxellight.rt.RtExecutionOptions.Visibility.LEGACY?"legacy":queryEnabled?"query":"trace")+", pathHotBytes=64, optionalProfile=pending RTX measurements, cameraUploadsPerFrame=1, outputCopiesPerFrame=1, runtimePtCompiler=0, continuationBytes="+(paths==null?0:paths.size()*(material?samples:1)+(pathMedia==null?0:pathMedia.size())+(pathAovs==null?0:pathAovs.size()))+", "+scene.status()+VulkanRtBuffer.memoryStatus(); }
    @Override public void close() {if(!closed){closed=true;RenderPassProfile.unobserve(timingObserver);scene.close();pipeline.close();if(primaryVisibility!=null)primaryVisibility.close();if(primaryShade!=null)primaryShade.close();if(cacheTrain!=null)cacheTrain.close();if(indirect!=null)indirect.close();if(resolve!=null)resolve.close();camera.close();if(visibilityTrace!=null)visibilityTrace.close();if(visibilityQuery!=null)visibilityQuery.close();if(pathAovs!=null)pathAovs.close();if(pathMedia!=null)pathMedia.close();if(feedback!=null)feedback.close();pageRequests.clear();if(pathBanks!=null){for(var bank:pathBanks)if(bank!=null)bank.close();pathBanks=null;}else if(paths!=null)paths.close();if(output!=null)output.close();}}
}
