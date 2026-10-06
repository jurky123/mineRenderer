package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.io.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static com.voxellight.rt.vulkan.VulkanRtCapabilities.check;

/** Prebuilt stages, recursion depth one. Descriptor sets are immutable for each queued dispatch. */
public final class VulkanRtPipeline implements AutoCloseable, Destroyable {
    private final VulkanDevice device;
    private long layout, setLayout, pipeline, cache;
    private VulkanRtBuffer sbt;
    private VulkanSbt packing;
    private Path cacheFile;
    private boolean closed;
    private final boolean transport,material;
    private final int bindingCount;
    VulkanRtPipeline(VulkanDevice device) { this(device,null); }
    VulkanRtPipeline(VulkanDevice device,String raygen) {
        transport=raygen!=null;material=transport&&raygen.startsWith("material_");bindingCount=material?18:transport?5:4;
        this.device=device;
        try(var stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
            var root=VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(properties.address());
            VK11.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(),root);
            packing=VulkanSbt.layout(properties.shaderGroupHandleSize(),properties.shaderGroupHandleAlignment(),properties.shaderGroupBaseAlignment(),properties.maxShaderGroupStride(),material?2:1,material?7:1);
            var bindings=VkDescriptorSetLayoutBinding.calloc(bindingCount,stack);
            int stages=VK_SHADER_STAGE_RAYGEN_BIT_KHR|VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR|VK_SHADER_STAGE_MISS_BIT_KHR|VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
            int[] types={KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR};
            for(int i=0;i<bindingCount;i++)bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(stages);
            var out=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device.vkDevice(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,out));setLayout=out.get(0);
            var layoutInfo=VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout));
            if(material)layoutInfo.pPushConstantRanges(VkPushConstantRange.calloc(1,stack).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR).offset(0).size(4));
            check(vkCreatePipelineLayout(device.vkDevice(),layoutInfo,null,out));layout=out.get(0);
            String prefix=material?"material_":transport?"transport_":"";
            byte[][] binaries=material?new byte[][]{load(raygen),load(prefix+"sky"),load(prefix+"closest_hit"),load("material_cutout"),load("material_visibility_miss"),load("material_shadow_cutout")}:new byte[][]{load(transport?raygen:"primary"),load(prefix+"sky"),load(prefix+"closest_hit")};
            cacheFile=cachePath(root.properties(),binaries);
            var cacheCreate=VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            ByteBuffer initial=null;
            try {if(Files.isRegularFile(cacheFile) && Files.size(cacheFile)<=16*1024*1024) {byte[] bytes=Files.readAllBytes(cacheFile);initial=MemoryUtil.memAlloc(bytes.length).put(bytes).flip();cacheCreate.pInitialData(initial);}}
            catch(IOException ignored) {}
            int result=vkCreatePipelineCache(device.vkDevice(),cacheCreate,null,out);
            if(initial!=null)MemoryUtil.memFree(initial);
            if(result!=VK_SUCCESS) {cacheCreate.pInitialData(null);check(vkCreatePipelineCache(device.vkDevice(),cacheCreate,null,out));}
            cache=out.get(0);
            long[] modules=new long[binaries.length];
            try {
                var stagesInfo=VkPipelineShaderStageCreateInfo.calloc(binaries.length,stack);
                int[] stageTypes={VK_SHADER_STAGE_RAYGEN_BIT_KHR,VK_SHADER_STAGE_MISS_BIT_KHR,VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,VK_SHADER_STAGE_ANY_HIT_BIT_KHR,VK_SHADER_STAGE_MISS_BIT_KHR,VK_SHADER_STAGE_ANY_HIT_BIT_KHR};
                for(int i=0;i<binaries.length;i++) {
                    var code=MemoryUtil.memAlloc(binaries[i].length).put(binaries[i]).flip();
                    try {check(vkCreateShaderModule(device.vkDevice(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out));modules[i]=out.get(0);}
                    finally {MemoryUtil.memFree(code);}
                    // Slang emits "main" unless -fvk-use-entrypoint-name is set.
                    stagesInfo.get(i).sType$Default().stage(stageTypes[i]).module(modules[i]).pName(stack.UTF8("main"));
                }
                int groupCount=material?10:3;
                var groups=VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount,stack);
                for(int i=0;i<groupCount;i++)groups.get(i).sType$Default().generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
                groups.get(0).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0);
                groups.get(1).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1);
                if(material){
                    groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(4);
                    for(int i=3;i<10;i++)groups.get(i).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR);
                    for(int i=3;i<6;i++)groups.get(i).closestHitShader(2);
                    groups.get(4).anyHitShader(3);
                    groups.get(8).anyHitShader(5);groups.get(9).anyHitShader(5);
                }else groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR).closestHitShader(2);
                boolean statistics=device.vkDevice().getCapabilities().VK_KHR_pipeline_executable_properties&&VulkanRtCapabilities.executableStatistics(device.vkDevice().getPhysicalDevice());
                var create=VkRayTracingPipelineCreateInfoKHR.calloc(1,stack);create.get(0).sType$Default().flags(material&&com.voxellight.rt.RtExecutionOptions.omm()&&device.vkDevice().getCapabilities().VK_EXT_opacity_micromap&&VulkanRtCapabilities.micromap(device.vkDevice().getPhysicalDevice())?org.lwjgl.vulkan.EXTOpacityMicromap.VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT:0).pStages(stagesInfo).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
                if(statistics)create.get(0).flags(create.get(0).flags()|KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR);
                check(vkCreateRayTracingPipelinesKHR(device.vkDevice(),0,cache,create,null,out));pipeline=out.get(0);
                if(statistics)VulkanPipelineDiagnostics.capture(device,pipeline,raygen==null?"primary":raygen);
                sbt=new VulkanRtBuffer(device,packing.bytes()+properties.shaderGroupBaseAlignment(),VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR);
                var handles=stack.malloc(properties.shaderGroupHandleSize()*groupCount);check(vkGetRayTracingShaderGroupHandlesKHR(device.vkDevice(),pipeline,0,groupCount,handles));
                var data=ByteBuffer.allocateDirect((int)sbt.size());long base=VulkanSbt.align(sbt.address(),properties.shaderGroupBaseAlignment())-sbt.address();
                long[] offsets=material?new long[]{packing.raygenOffset(),packing.missOffset(),packing.missOffset()+packing.stride(),packing.hitOffset(),packing.hitOffset()+packing.stride(),packing.hitOffset()+2*packing.stride(),packing.hitOffset()+3*packing.stride(),packing.hitOffset()+4*packing.stride(),packing.hitOffset()+5*packing.stride(),packing.hitOffset()+6*packing.stride()}:new long[]{packing.raygenOffset(),packing.missOffset(),packing.hitOffset()};
                for(int i=0;i<groupCount;i++)for(int b=0;b<properties.shaderGroupHandleSize();b++)data.put((int)(base+offsets[i]+b),handles.get(i*properties.shaderGroupHandleSize()+b));
                device.createCommandEncoder().writeToBuffer(sbt.slice(),data);
                sbtBase=sbt.address()+base;
                saveCache();
            } finally {for(long module:modules)if(module!=0)vkDestroyShaderModule(device.vkDevice(),module,null);}
        } catch(RuntimeException error) {close();throw error;}
    }
    private static final class DescriptorSlot {final long pool,set;boolean busy;DescriptorSlot(long pool,long set){this.pool=pool;this.set=set;}}
    private final java.util.ArrayList<DescriptorSlot> descriptorSlots=new java.util.ArrayList<>();
    private final java.util.ArrayList<DescriptorSlot> recordedDescriptors=new java.util.ArrayList<>();
    private DescriptorSlot acquireDescriptor(MemoryStack stack){
        for(var slot:descriptorSlots)if(!slot.busy){slot.busy=true;recordedDescriptors.add(slot);return slot;}
        if(descriptorSlots.size()>=32)throw new IllegalStateException("RT descriptor ring exhausted by in-flight submissions");
        var sizes=VkDescriptorPoolSize.calloc(3,stack);
        sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(material?2:1);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(bindingCount-(material?3:2));sizes.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
        var out=stack.mallocLong(1);check(vkCreateDescriptorPool(device.vkDevice(),VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,out));long pool=out.get(0);
        try{check(vkAllocateDescriptorSets(device.vkDevice(),VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)),out));}
        catch(RuntimeException error){vkDestroyDescriptorPool(device.vkDevice(),pool,null);throw error;}
        var slot=new DescriptorSlot(pool,out.get(0));slot.busy=true;descriptorSlots.add(slot);recordedDescriptors.add(slot);return slot;
    }
    /** Called after trace commands execute, so the fence covers all users of these descriptor sets. */
    public void retireDescriptors(){
        if(recordedDescriptors.isEmpty())return;var submitted=java.util.List.copyOf(recordedDescriptors);recordedDescriptors.clear();
        com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(()->{for(var slot:submitted)slot.busy=false;});
    }
    private long sbtBase;
    public void trace(VkCommandBuffer command,long tlas,VulkanRtBuffer output,VulkanRtBuffer normals,VulkanRtBuffer camera,int width,int height) {
        trace(command,tlas,output,normals,camera,null,width,height);
    }
    public void trace(VkCommandBuffer command,long tlas,VulkanRtBuffer output,VulkanRtBuffer normals,VulkanRtBuffer camera,VulkanRtBuffer paths,int width,int height) {
        trace(command,tlas,output,normals,camera,paths,null,null,width,height);
    }
    public void trace(VkCommandBuffer command,long tlas,VulkanRtBuffer output,VulkanRtBuffer normals,VulkanRtBuffer camera,VulkanRtBuffer paths,VulkanRtBuffer geometry,VulkanRtBuffer assets,int width,int height) {
        bind(command,tlas,output,normals,camera,paths,geometry,assets,null,null);dispatch(command,width,height,1);
    }
    public void bind(VkCommandBuffer command,long tlas,VulkanRtBuffer output,VulkanRtBuffer normals,VulkanRtBuffer camera,VulkanRtBuffer paths,VulkanRtBuffer geometry,VulkanRtBuffer assets,VulkanRtBuffer feedback,VulkanRtBuffer[] banks){bind(command,tlas,output,normals,camera,paths,geometry,assets,feedback,banks,null,null,tlas);}
    public void bind(VkCommandBuffer command,long tlas,VulkanRtBuffer output,VulkanRtBuffer normals,VulkanRtBuffer camera,VulkanRtBuffer paths,VulkanRtBuffer geometry,VulkanRtBuffer assets,VulkanRtBuffer feedback,VulkanRtBuffer[] banks,VulkanRtBuffer media,VulkanRtBuffer aovs,long opaqueTlas){
        if(material&&(geometry==null||assets==null||feedback==null||media==null||aovs==null))throw new IllegalArgumentException("Material pipeline requires geometry and atlas buffers");
        if(transport!=(paths!=null))throw new IllegalArgumentException("Continuation buffer does not match pipeline ABI");
        try(var stack=MemoryStack.stackPush()) {
            long set=acquireDescriptor(stack).set;
            var writes=VkWriteDescriptorSet.calloc(bindingCount,stack);
            var acceleration=VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(tlas));
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).pNext(acceleration.address());
            VulkanRtBuffer[] buffers=new VulkanRtBuffer[bindingCount-(material?2:1)];VulkanRtBuffer[] base={output,normals,camera,paths,geometry,assets,feedback};System.arraycopy(base,0,buffers,0,Math.min(base.length,buffers.length));
            if(material){for(int lane=1;lane<8;lane++)buffers[lane+6]=lane<banks.length?banks[lane]:banks[0];buffers[14]=media;buffers[15]=aovs;}
            for(int i=0;i<buffers.length;i++) {
                var buffer=VkDescriptorBufferInfo.calloc(1,stack).buffer(buffers[i].vkBuffer()).offset(0).range(buffers[i].size());
                bufferWrite(writes.get(i+1),set,i+1,i==2?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,buffer);
            }
            if(material){var opaque=VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(opaqueTlas));writes.get(17).sType$Default().dstSet(set).dstBinding(17).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).pNext(opaque.address());}
            vkUpdateDescriptorSets(device.vkDevice(),writes,null);
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,pipeline);
            vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,layout,0,stack.longs(set),null);
        }
    }
    public void dispatch(VkCommandBuffer command,int width,int height,int lanes){
        try(var stack=MemoryStack.stackPush()){
            if(material)vkCmdPushConstants(command,layout,VK_SHADER_STAGE_RAYGEN_BIT_KHR,0,stack.ints(0));
            var raygen=region(stack,packing.raygenOffset());var miss=region(stack,packing.missOffset());var hit=region(stack,packing.hitOffset());
            vkCmdTraceRaysKHR(command,raygen,miss,hit,VkStridedDeviceAddressRegionKHR.calloc(stack),width,height,lanes);
        }
    }
    public void dispatchIndirect(VkCommandBuffer command,long address,int bounce){
        try(var stack=MemoryStack.stackPush()){
            vkCmdPushConstants(command,layout,VK_SHADER_STAGE_RAYGEN_BIT_KHR,0,stack.ints(bounce));
            vkCmdTraceRaysIndirectKHR(command,region(stack,packing.raygenOffset()),region(stack,packing.missOffset()),region(stack,packing.hitOffset()),VkStridedDeviceAddressRegionKHR.calloc(stack),address);
        }
    }
    static void bufferWrite(VkWriteDescriptorSet write,long set,int binding,int type,VkDescriptorBufferInfo.Buffer info) {
        write.sType$Default().dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(info.remaining()).pBufferInfo(info);
    }
    private VkStridedDeviceAddressRegionKHR region(MemoryStack stack,long offset) {return VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtBase+offset).stride(packing.stride()).size(packing.stride()*(material?(offset==packing.missOffset()?2:offset==packing.hitOffset()?7:1):1));}
    private static byte[] load(String stage) {
        try(var stream=VulkanRtPipeline.class.getResourceAsStream("/assets/voxellight/rt/vulkan/"+stage+".spv")) {
            if(stream==null)throw new IllegalStateException("Missing prebuilt RT stage "+stage);byte[] bytes=stream.readAllBytes();
            if(bytes.length<20||bytes.length%4!=0||ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt()!=0x07230203)throw new IllegalStateException("Invalid RT SPIR-V "+stage);return bytes;
        } catch(IOException error) {throw new UncheckedIOException(error);}
    }
    private static Path cachePath(VkPhysicalDeviceProperties properties,byte[][] binaries) {
        try {var digest=MessageDigest.getInstance("SHA-256");digest.update(properties.pipelineCacheUUID().duplicate());for(byte[] binary:binaries)digest.update(binary);
            return Path.of(System.getProperty("user.home"),".cache","voxellight","vulkan",properties.vendorID()+"-"+properties.deviceID()+"-"+properties.driverVersion()+"-"+java.util.HexFormat.of().formatHex(digest.digest())+".cache");
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void saveCache() {
        try(var stack=MemoryStack.stackPush()) {var size=stack.mallocPointer(1);check(vkGetPipelineCacheData(device.vkDevice(),cache,size,null));if(size.get(0)>16*1024*1024)return;
            var data=MemoryUtil.memAlloc((int)size.get(0));
            try {check(vkGetPipelineCacheData(device.vkDevice(),cache,size,data));byte[] bytes=new byte[(int)size.get(0)];data.get(bytes);Files.createDirectories(cacheFile.getParent());Path temporary=Files.createTempFile(cacheFile.getParent(),"rt-",".cache");try{Files.write(temporary,bytes);Files.move(temporary,cacheFile,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
            finally {MemoryUtil.memFree(data);}
        } catch(IOException|RuntimeException error) {org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("Vulkan RT cache unavailable",error);}
    }
    @Override public void close() {if(!closed){closed=true;device.createCommandEncoder().queueForDestroy(this);if(sbt!=null)sbt.close();}}
    @Override public void destroy() {var vk=device.vkDevice();for(var slot:descriptorSlots)vkDestroyDescriptorPool(vk,slot.pool,null);descriptorSlots.clear();recordedDescriptors.clear();if(pipeline!=0)vkDestroyPipeline(vk,pipeline,null);if(cache!=0)vkDestroyPipelineCache(vk,cache,null);if(layout!=0)vkDestroyPipelineLayout(vk,layout,null);if(setLayout!=0)vkDestroyDescriptorSetLayout(vk,setLayout,null);}
}
