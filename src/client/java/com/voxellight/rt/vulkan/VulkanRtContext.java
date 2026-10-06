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
    public final VulkanRtScene scene;
    private VulkanRtBuffer output,paths,camera,feedback,pathMedia;
    private final java.util.concurrent.ConcurrentLinkedQueue<com.voxellight.world.SectionKey> pageRequests=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean feedbackPending;
    private long pageRequestCount;
    private final boolean transport,material;
    private final VulkanRtPipeline indirect,resolve;
    private VulkanRtBuffer[] pathBanks;
    private final long maxStorageRange;
    private final boolean indirectTracing;
    private boolean compact;
    private String lastWorkload="unobserved";
    private static final int FEEDBACK_HEADER=544;
    public long maxPixels(int spp){return Math.min(maxStorageRange/(material?spp*288:spp*64),1024L*1024*1024/(spp*(material?432:64)));}
    private int frame;
    private int width,height,samples=1;
    private boolean closed,reconstructionGuides=true;
    public void reconstructionGuides(boolean value){reconstructionGuides=value;}
    private final String optionalCapabilities;
    public VulkanRtContext(VulkanDevice device) {this(device,false);}
    public VulkanRtContext(VulkanDevice device,boolean transport) {this(device,transport,false);}
    public VulkanRtContext(VulkanDevice device,boolean transport,boolean material) {
        if(material&&!transport)throw new IllegalArgumentException("Material transport requires continuations");
        this.transport=transport;this.material=material;
        this.device=device;indirectTracing=material&&VulkanRtCapabilities.indirectTracing(device.vkDevice().getPhysicalDevice());
        var capabilities=VulkanRtCapabilities.query(device.vkDevice().getPhysicalDevice());
        if(!capabilities.supported())throw new IllegalStateException(capabilities.reason());
        try(var stack=MemoryStack.stackPush()){var properties=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(),properties);maxStorageRange=Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());if(material&&maxStorageRange<128L*1024*1024)throw new IllegalStateException("Stable material geometry requires 128 MiB storage-buffer range");if(material&&properties.limits().maxPerStageDescriptorStorageBuffers()<14)throw new IllegalStateException("Material batch requires 14 storage-buffer bindings");}
        var optional=new java.util.TreeSet<>(capabilities.extensions());optional.retainAll(VulkanRtCapabilities.OPTIONAL);optionalCapabilities=optional.toString();
        org.slf4j.LoggerFactory.getLogger("VoxelLight").info("Vulkan RT mandatory features enabled; optional advertised (not enabled): {}",optional);
        if(!device.vkDevice().getCapabilities().VK_KHR_ray_tracing_pipeline || !device.vkDevice().getCapabilities().VK_KHR_acceleration_structure)
            throw new IllegalStateException("RT extensions were not enabled on Minecraft Vulkan device");
        try(var stack=MemoryStack.stackPush()) {
            var acceleration=VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(),VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration.address()));
            scene=new VulkanRtScene(device,acceleration.minAccelerationStructureScratchOffsetAlignment(),material);
        }
        try {pipeline=new VulkanRtPipeline(device,material?"material_primary":transport?"transport_primary":null);}
        catch(RuntimeException error){scene.close();throw error;}
        VulkanRtPipeline continuation=null;
        try {if(transport)continuation=new VulkanRtPipeline(device,material?"material_indirect":"transport_indirect");}
        catch(RuntimeException error){pipeline.close();scene.close();throw error;}
        indirect=continuation;
        VulkanRtPipeline sampleResolve=null;
        try {sampleResolve=transport?new VulkanRtPipeline(device,material?"material_resolve":"transport_resolve"):null;camera=new VulkanRtBuffer(device,96,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);}
        catch(RuntimeException error){if(camera!=null)camera.close();if(sampleResolve!=null)sampleResolve.close();if(indirect!=null)indirect.close();pipeline.close();scene.close();throw error;}
        resolve=sampleResolve;
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
        if(this.width!=width||this.height!=height||samples!=spp) {
            compact=material&&indirectTracing&&(long)width*height*spp>=65536&&(FEEDBACK_HEADER+(long)width*height*spp*2)*16<=maxStorageRange;
            if(material){if(feedback!=null)feedback.close();feedback=new VulkanRtBuffer(device,(FEEDBACK_HEADER+(compact?(long)width*height*spp*2:0))*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);}
            if(output!=null)output.close();output=new VulkanRtBuffer(device,(long)width*height*spp*16+(material?64+(long)width*height*112:0),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);this.width=width;this.height=height;samples=spp;
            if(pathBanks!=null){for(var bank:pathBanks)bank.close();pathBanks=null;paths=null;}else if(paths!=null){paths.close();paths=null;}
            if(material){if(pathMedia!=null)pathMedia.close();pathMedia=new VulkanRtBuffer(device,(long)width*height*spp*288,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);pathBanks=new VulkanRtBuffer[spp];for(int lane=0;lane<spp;lane++)pathBanks[lane]=new VulkanRtBuffer(device,(long)width*height*144,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);paths=pathBanks[0];}
            else if(transport)paths=new VulkanRtBuffer(device,(long)width*height*spp*64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
        {
            var data=ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());inverseClip.get(0,data);
            data.position(64).putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((material&&reconstructionGuides?1:0)+(RenderPassProfile.enabled()?2:0)+(compact?4:0)).putInt(width).putInt(height).putInt(transport?frame++:0).putInt(spp).flip();
            encoder.writeToBuffer(camera.slice(),data);
            if(material){
                encoder.writeToBuffer(feedback.slice(0,16),ByteBuffer.allocateDirect(16));
                var counters=ByteBuffer.allocateDirect((FEEDBACK_HEADER-513)*16).order(ByteOrder.nativeOrder());
                for(int bounce=1;bounce<6;bounce++){int offset=(520+bounce-513)*16;counters.putInt(offset,1).putInt(offset+4,1).putInt(offset+8,1);}
                encoder.writeToBuffer(feedback.slice(513*16,(FEEDBACK_HEADER-513)*16),counters);
            }
            var nativeEncoder=device.createCommandEncoder();
            try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_batch");var stack=MemoryStack.stackPush()) {
                var command=nativeEncoder.allocateAndBeginTransientCommandBuffer();
                VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_UNIFORM_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                pipeline.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia);
                try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_primary")){pipeline.dispatch(command,width,height,spp);}
                if(transport){
                    indirect.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia);
                    for(int bounce=1;bounce<6;bounce++){
                        VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
                        try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_bounce_"+bounce)){if(compact)indirect.dispatchIndirect(command,feedback.address()+(520L+bounce)*16,bounce);else indirect.dispatch(command,width,height,spp);}
                    }
                    VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
                    resolve.bind(command,scene.tlas(),output,scene.normals(),camera,paths,scene.geometry(),assets,feedback,pathBanks,pathMedia);try(var pass=RenderPassProfile.beginNative(command,"vulkan_rt_sample_resolve")){resolve.dispatch(command,width,height,1);}
                }
                VulkanRtScene.barrier(command,stack,VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);
                VulkanRtCapabilities.check(vkEndCommandBuffer(command));nativeEncoder.execute(command);
                pipeline.retireDescriptors();if(indirect!=null)indirect.retireDescriptors();if(resolve!=null)resolve.retireDescriptors();
            }
            encoder.copyBufferToTexture(output.slice(),0,0,width,height,destination,0,0,width,height,0,0);
            if(material&&!feedbackPending&&((frame-1)%8)==0)readPageFeedback(encoder);
            return true;
        }
    }
    private void readPageFeedback(CommandEncoder encoder){
        feedbackPending=true;var read=com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(()->"VoxelLight RT page requests",com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST|com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ,FEEDBACK_HEADER*16);
        encoder.copyToBuffer(feedback.slice(0,FEEDBACK_HEADER*16),read.slice());
        final int sampledWidth=width,sampledHeight=height,sampledSpp=samples;final long sampledFrame=RenderPassProfile.frameId(),sampledScene=scene.generation();final boolean measured=RenderPassProfile.enabled();
        com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(()->{try(var map=read.map(true,false)){if(!closed){var data=map.data().order(ByteOrder.nativeOrder());int count=Math.min(512,Math.max(0,data.getInt(0)));pageRequestCount+=count;for(int i=0;i<count;i++){int offset=(i+1)*16;pageRequests.add(new com.voxellight.world.SectionKey(data.getInt(offset),data.getInt(offset+4),data.getInt(offset+8)));}
                var active=new long[6];for(int bounce=0;bounce<6;bounce++)active[bounce]=Integer.toUnsignedLong(data.getInt((514+bounce)*16));
                if(measured)RenderPassProfile.rayWorkload(sampledFrame,sampledWidth,sampledHeight,sampledSpp,sampledScene,active,Integer.toUnsignedLong(data.getInt(513*16)),Integer.toUnsignedLong(data.getInt(513*16+4)));
                lastWorkload="frame:"+sampledFrame+"/"+sampledWidth+"x"+sampledHeight+"/"+sampledSpp+"spp/active:"+Arrays.toString(active).replace(',','/')+"/shadow:"+Integer.toUnsignedLong(data.getInt(513*16))+"/anyHit:"+Integer.toUnsignedLong(data.getInt(513*16+4));
            }}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("RT page feedback unavailable",error);}finally{read.close();feedbackPending=false;}});
    }
    public java.util.Set<com.voxellight.world.SectionKey> drainPageRequests(){var result=new java.util.LinkedHashSet<com.voxellight.world.SectionKey>();com.voxellight.world.SectionKey key;while((key=pageRequests.poll())!=null){if(result.size()<256)result.add(key);}return result;}
    public void copyGuide(CommandEncoder encoder,int plane,GpuTexture destination){
        if(!material||plane<0||plane>=7)throw new IllegalArgumentException("Invalid guide plane");
        long offset=((long)width*height*samples+4+(long)plane*width*height)*16;
        encoder.copyBufferToTexture(output.slice(offset,(long)width*height*16),0,0,width,height,destination,0,0,width,height,0,0);
    }
    public void copyGuideToBuffer(CommandEncoder encoder,int plane,com.mojang.blaze3d.buffers.GpuBufferSlice destination){
        if(!material||plane<0||plane>=7)throw new IllegalArgumentException("Invalid guide plane");
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
    public String status() { return (material?"vulkanRt=material transport experimental, material=Material 3/LabPBR, mediumStack=8, cutout=any-hit, bounces=6, environment=shared HDR 256x128, environmentSampling=GPU solid-angle CDF, lightNee=sun/moon+environment+held, lightMis=power heuristic, cameraWater=initialized, ":transport?"vulkanRt=geometry transport test, material=grey diffuse, bounces=6, ":"vulkanRt=normal POC, ")+"reconstruction=external frame reconstruction, internalResolution="+width+"x"+height+", recursion=1, sppPerFrame="+samples+", scheduling="+(compact?"GPU compact continuation":"fixed batch")+", rayWorkload="+lastWorkload+", rtMissPageRequests="+pageRequestCount+", optionalAdvertised="+optionalCapabilities+", optionalEnabled=[], optionalProfile=pending RTX measurements, cameraUploadsPerFrame=1, outputCopiesPerFrame=1, runtimePtCompiler=0, continuationBytes="+(paths==null?0:paths.size()*(material?samples:1)+(pathMedia==null?0:pathMedia.size()))+", "+scene.status()+VulkanRtBuffer.memoryStatus(); }
    @Override public void close() {if(!closed){closed=true;scene.close();pipeline.close();if(indirect!=null)indirect.close();if(resolve!=null)resolve.close();camera.close();if(pathMedia!=null)pathMedia.close();if(feedback!=null)feedback.close();pageRequests.clear();if(pathBanks!=null){for(var bank:pathBanks)if(bank!=null)bank.close();pathBanks=null;}else if(paths!=null)paths.close();if(output!=null)output.close();}}
}
