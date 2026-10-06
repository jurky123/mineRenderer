package com.voxellight.rt.vulkan;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.lwjgl.vulkan.KHRPipelineExecutableProperties.*;

/** Driver-published compiler statistics; absent metrics stay absent, never estimated from SPIR-V. */
public final class VulkanPipelineDiagnostics {
    private record Row(String stage,String executable,String name,String description,String value){}
    private static final LinkedHashMap<String,Row> rows=new LinkedHashMap<>();
    private VulkanPipelineDiagnostics(){}
    static void capture(VulkanDevice device,long pipeline,String stage){
        try(var stack=MemoryStack.stackPush()){
            var count=stack.ints(0);var info=VkPipelineInfoKHR.calloc(stack).sType$Default().pipeline(pipeline);
            VulkanRtCapabilities.check(vkGetPipelineExecutablePropertiesKHR(device.vkDevice(),info,count,null));if(count.get(0)>256)return;
            try(var executables=VkPipelineExecutablePropertiesKHR.calloc(count.get(0))){
                for(var property:executables)property.sType$Default();
                VulkanRtCapabilities.check(vkGetPipelineExecutablePropertiesKHR(device.vkDevice(),info,count,executables));
                for(int i=0;i<count.get(0);i++){
                    var query=VkPipelineExecutableInfoKHR.calloc(stack).sType$Default().pipeline(pipeline).executableIndex(i);var size=stack.ints(0);
                    VulkanRtCapabilities.check(vkGetPipelineExecutableStatisticsKHR(device.vkDevice(),query,size,null));if(size.get(0)>256)continue;
                    try(var statistics=VkPipelineExecutableStatisticKHR.calloc(size.get(0))){
                        for(var statistic:statistics)statistic.sType$Default();
                        VulkanRtCapabilities.check(vkGetPipelineExecutableStatisticsKHR(device.vkDevice(),query,size,statistics));
                        for(int j=0;j<size.get(0);j++){
                            var statistic=statistics.get(j);String value=switch(statistic.format()){
                                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR->Boolean.toString(statistic.value().b32());
                                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR->Long.toString(statistic.value().i64());
                                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR->Long.toUnsignedString(statistic.value().u64());
                                case VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_FLOAT64_KHR->Double.toString(statistic.value().f64());
                                default->"unavailable";
                            };
                            String executable=executables.get(i).nameString(),name=statistic.nameString();
                            rows.put(stage+"/"+executable+"/"+name,new Row(stage,executable,name,statistic.descriptionString(),value));
                            while(rows.size()>2048)rows.remove(rows.firstEntry().getKey());
                        }
                    }
                }
            }
        }catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("RT compiler statistics unavailable",error);}
    }
    private static String csv(String text){return "\""+text.replace("\"","\"\"")+"\"";}
    public static void export(Path path)throws IOException{try(var writer=Files.newBufferedWriter(path)){writer.write("stage,executable,name,description,value\n");for(var row:rows.values())writer.write(csv(row.stage)+","+csv(row.executable)+","+csv(row.name)+","+csv(row.description)+","+csv(row.value)+"\n");}}
}
