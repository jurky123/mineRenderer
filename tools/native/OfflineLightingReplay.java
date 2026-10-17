import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Replays saved lighting inputs without Minecraft, a window, or a renderer. */
public class OfflineLightingReplay {
 static VkDevice device;static VkPhysicalDevice physical;static VkQueue queue;static long pool;
 static void check(int code){if(code!=VK_SUCCESS)throw new IllegalStateException("Vulkan result "+code);}
 record Buffer(long handle,long memory,long size){}
 static Buffer buffer(long size,int usage,int properties){try(var s=MemoryStack.stackPush()){
  var out=s.mallocLong(1);check(vkCreateBuffer(device,VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE),null,out));long handle=out.get(0);
  var req=VkMemoryRequirements.calloc(s);vkGetBufferMemoryRequirements(device,handle,req);var mem=VkPhysicalDeviceMemoryProperties.calloc(s);vkGetPhysicalDeviceMemoryProperties(physical,mem);int type=-1;
  for(int i=0;i<mem.memoryTypeCount();i++)if((req.memoryTypeBits()&(1<<i))!=0&&(mem.memoryTypes(i).propertyFlags()&properties)==properties){type=i;break;}
  if(type<0)throw new IllegalStateException("Memory type unavailable");check(vkAllocateMemory(device,VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size()).memoryTypeIndex(type),null,out));long memory=out.get(0);check(vkBindBufferMemory(device,handle,memory,0));return new Buffer(handle,memory,size);
 }}
 static void free(Buffer b){vkDestroyBuffer(device,b.handle,null);vkFreeMemory(device,b.memory,null);}
 static VkCommandBuffer begin(){try(var s=MemoryStack.stackPush()){
  var p=s.mallocPointer(1);check(vkAllocateCommandBuffers(device,VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),p));var cmd=new VkCommandBuffer(p.get(0),device);check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)));return cmd;
 }}
 static void submit(VkCommandBuffer cmd){try(var s=MemoryStack.stackPush()){
  check(vkEndCommandBuffer(cmd));check(vkQueueSubmit(queue,VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd.address())),0));check(vkQueueWaitIdle(queue));vkFreeCommandBuffers(device,pool,cmd);
 }}
 static void barrier(VkCommandBuffer cmd,int srcStage,int srcAccess,int dstStage,int dstAccess){try(var s=MemoryStack.stackPush()){
  vkCmdPipelineBarrier(cmd,srcStage,dstStage,0,VkMemoryBarrier.calloc(1,s).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess),null,null);
 }}
 static Buffer upload(Path path)throws Exception{byte[] bytes=Files.readAllBytes(path);Buffer staging=buffer(bytes.length,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);try(var s=MemoryStack.stackPush()){
  var ptr=s.mallocPointer(1);check(vkMapMemory(device,staging.memory,0,bytes.length,0,ptr));MemoryUtil.memByteBuffer(ptr.get(0),bytes.length).put(bytes);vkUnmapMemory(device,staging.memory);
  Buffer result=buffer(bytes.length,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  var cmd=begin();vkCmdCopyBuffer(cmd,staging.handle,result.handle,VkBufferCopy.calloc(1,s).size(bytes.length));barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_UNIFORM_READ_BIT);submit(cmd);free(staging);return result;
 }}
 static byte[] read(Buffer source,long offset,int bytes){Buffer staging=buffer(bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);try(var s=MemoryStack.stackPush()){
  var cmd=begin();barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT);vkCmdCopyBuffer(cmd,source.handle,staging.handle,VkBufferCopy.calloc(1,s).srcOffset(offset).size(bytes));barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_HOST_READ_BIT);submit(cmd);
  var ptr=s.mallocPointer(1);check(vkMapMemory(device,staging.memory,0,bytes,0,ptr));byte[] result=new byte[bytes];MemoryUtil.memByteBuffer(ptr.get(0),bytes).get(result);vkUnmapMemory(device,staging.memory);free(staging);return result;
 }}
 public static void main(String[] args)throws Exception{
  Path captured=Path.of(args[0]),shaders=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
  VkInstance instance;try(var s=MemoryStack.stackPush()){
   var p=s.mallocPointer(1);check(vkCreateInstance(VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("Frozen lighting replay")).apiVersion(VK12.VK_API_VERSION_1_2)),null,p));instance=new VkInstance(p.get(0),VkInstanceCreateInfo.calloc(s).sType$Default());
   var count=s.ints(0);check(vkEnumeratePhysicalDevices(instance,count,null));var devices=s.mallocPointer(count.get(0));check(vkEnumeratePhysicalDevices(instance,count,devices));
   for(int i=0;i<count.get(0);i++){var candidate=new VkPhysicalDevice(devices.get(i),instance);var properties=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(candidate,properties);if(properties.deviceType()==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU){physical=candidate;break;}}
   if(physical==null)throw new IllegalStateException("No discrete GPU");var props=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(physical,props);float period=props.limits().timestampPeriod();String gpu=props.deviceNameString();
   vkGetPhysicalDeviceQueueFamilyProperties(physical,count,null);var queues=VkQueueFamilyProperties.calloc(count.get(0),s);vkGetPhysicalDeviceQueueFamilyProperties(physical,count,queues);int family=-1;
   for(int i=0;i<count.get(0);i++)if((queues.get(i).queueFlags()&VK_QUEUE_COMPUTE_BIT)!=0&&queues.get(i).timestampValidBits()==64){family=i;break;}if(family<0)throw new IllegalStateException("No 64-bit timestamp compute queue");
   var create=VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(VkDeviceQueueCreateInfo.calloc(1,s).sType$Default().queueFamilyIndex(family).pQueuePriorities(s.floats(1)));
   check(vkCreateDevice(physical,create,null,p));device=new VkDevice(p.get(0),physical,create);vkGetDeviceQueue(device,family,0,p);queue=new VkQueue(p.get(0),device);
   var out=s.mallocLong(1);check(vkCreateCommandPool(device,VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(family),null,out));pool=out.get(0);
   Map<Integer,Buffer> buffers=new LinkedHashMap<>();buffers.put(2,upload(captured.resolve("normals.bin")));buffers.put(3,upload(captured.resolve("camera.bin")));buffers.put(5,upload(captured.resolve("geometry.bin")));buffers.put(6,upload(captured.resolve("assets.bin")));buffers.put(7,upload(captured.resolve("feedback.bin")));
   var bindings=VkDescriptorSetLayoutBinding.calloc(buffers.size(),s);int bi=0;for(int binding:buffers.keySet())bindings.get(bi++).binding(binding).descriptorType(binding==3?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
   check(vkCreateDescriptorSetLayout(device,VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bindings),null,out));long setLayout=out.get(0);
   check(vkCreatePipelineLayout(device,VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout)),null,out));long layout=out.get(0);
   var sizes=VkDescriptorPoolSize.calloc(2,s);sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
   check(vkCreateDescriptorPool(device,VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(sizes),null,out));long descriptorPool=out.get(0);check(vkAllocateDescriptorSets(device,VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descriptorPool).pSetLayouts(s.longs(setLayout)),out));long set=out.get(0);
   for(var entry:buffers.entrySet()){int b=entry.getKey();Buffer buf=entry.getValue();var infos=VkDescriptorBufferInfo.calloc(1,s).buffer(buf.handle).offset(0).range(buf.size);var writes=VkWriteDescriptorSet.calloc(1,s).sType$Default().dstSet(set).dstBinding(b).descriptorType(b==3?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(infos);vkUpdateDescriptorSets(device,writes,null);}
   int trials=Integer.getInteger("replay.trials",100),stages=Integer.getInteger("replay.stages",4),groups=Integer.getInteger("replay.groups",32),tableBytes=Integer.getInteger("replay.tableBytes",0);
   if(trials<1||trials>10000||stages<1||stages>64||groups<1||groups>65535||tableBytes<0)throw new IllegalArgumentException("Invalid replay dimensions");
   check(vkCreateQueryPool(device,VkQueryPoolCreateInfo.calloc(s).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(trials*2),null,out));long queries=out.get(0);
   ByteBuffer camera=ByteBuffer.wrap(Files.readAllBytes(captured.resolve("camera.bin"))).order(ByteOrder.LITTLE_ENDIAN);long resultOffset=(1648L+4L*camera.getInt(80)*camera.getInt(84)*camera.getInt(92)+2048L*128)*16;
   StringBuilder csv=new StringBuilder("stage,trial,gpu_ms\n");
   StringBuilder windows=new StringBuilder("stage,batch,begin_utc_ms,end_utc_ms\n");
   for(int k=0;k<stages;k++){
    byte[] binary=Files.readAllBytes(shaders.resolve("replay"+k+".spv"));ByteBuffer code=MemoryUtil.memAlloc(binary.length).put(binary).flip();check(vkCreateShaderModule(device,VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code),null,out));long module=out.get(0);MemoryUtil.memFree(code);
    var ci=VkComputePipelineCreateInfo.calloc(1,s);ci.get(0).sType$Default().layout(layout).stage(VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main")));
    check(vkCreateComputePipelines(device,0,ci,null,out));long pipeline=out.get(0);
    for(int batch=0;batch<2;batch++){
     var cmd=begin();vkCmdResetQueryPool(cmd,queries,0,trials*2);vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,s.longs(set),null);
     for(int i=0;i<trials;i++){barrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,i*2);vkCmdDispatch(cmd,groups,1,1);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,i*2+1);}
     long batchBegin=System.currentTimeMillis();submit(cmd);long batchEnd=System.currentTimeMillis();
     windows.append(k).append(',').append(batch).append(',').append(batchBegin).append(',').append(batchEnd).append('\n');
     var times=MemoryUtil.memAllocLong(trials*2);check(vkGetQueryPoolResults(device,queries,0,trials*2,times,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT));if(batch==1)for(int i=0;i<trials;i++)csv.append(k).append(',').append(i).append(',').append((times.get(i*2+1)-times.get(i*2))*period/1e6).append('\n');MemoryUtil.memFree(times);
    }
    Files.write(output.resolve("result-"+k+".bin"),read(buffers.get(7),resultOffset,2048*16));
    if(tableBytes>0){long tableOffset=resultOffset+2048*16;if(tableOffset+tableBytes>buffers.get(7).size)throw new IllegalArgumentException("Candidate table exceeds feedback buffer");Files.write(output.resolve("stage-"+k+"-table.bin"),read(buffers.get(7),tableOffset,tableBytes));}
    vkDestroyPipeline(device,pipeline,null);vkDestroyShaderModule(device,module,null);
   }
   Files.write(output.resolve("actual-records.bin"),read(buffers.get(7),resultOffset-2048L*128*16,2048*128*16));
   Files.writeString(output.resolve("timings.csv"),csv);Files.writeString(output.resolve("phase-windows.csv"),windows);Files.writeString(output.resolve("device.txt"),gpu+"\ntimestampPeriod="+period+"\nDevice-local frozen inputs; "+trials+" warmup and "+trials+" measured compute dispatches per stage; groups="+groups+", stages="+stages+"; no rendering or Minecraft.\n");
   vkDestroyQueryPool(device,queries,null);vkDestroyDescriptorPool(device,descriptorPool,null);vkDestroyPipelineLayout(device,layout,null);vkDestroyDescriptorSetLayout(device,setLayout,null);for(Buffer b:buffers.values())free(b);vkDestroyCommandPool(device,pool,null);vkDestroyDevice(device,null);vkDestroyInstance(instance,null);
   System.out.println("Offline GPU replay completed: "+output);
  }
 }
}
