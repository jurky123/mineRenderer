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
    private record Status(boolean captureEnabled,int executables,int statistics,String reason){}
    private static final LinkedHashMap<String,Status> status=new LinkedHashMap<>();
    private static void report(String stage,Status value){status.put(stage,value);while(status.size()>64)status.remove(status.firstEntry().getKey());}
    static void unavailable(String stage,String reason){rows.entrySet().removeIf(entry->entry.getValue().stage.equals(stage));report(stage,new Status(false,0,0,reason));}
    private VulkanPipelineDiagnostics(){}
    static void capture(VulkanDevice device,long pipeline,String stage){
        rows.entrySet().removeIf(entry->entry.getValue().stage.equals(stage));int executableCount=0,statisticCount=0;
        try(var stack=MemoryStack.stackPush()){
            var count=stack.ints(0);var info=VkPipelineInfoKHR.calloc(stack).sType$Default().pipeline(pipeline);
            VulkanRtCapabilities.check(vkGetPipelineExecutablePropertiesKHR(device.vkDevice(),info,count,null));if(count.get(0)>256){report(stage,new Status(true,count.get(0),0,"driver executable count exceeds bounded diagnostic limit"));return;}
            executableCount=count.get(0);if(executableCount==0){report(stage,new Status(true,0,0,"driver returned no pipeline executables"));return;}
            try(var executables=VkPipelineExecutablePropertiesKHR.calloc(count.get(0))){
                for(var property:executables)property.sType$Default();
                VulkanRtCapabilities.check(vkGetPipelineExecutablePropertiesKHR(device.vkDevice(),info,count,executables));
                for(int i=0;i<count.get(0);i++){
                    var query=VkPipelineExecutableInfoKHR.calloc(stack).sType$Default().pipeline(pipeline).executableIndex(i);var size=stack.ints(0);
                    VulkanRtCapabilities.check(vkGetPipelineExecutableStatisticsKHR(device.vkDevice(),query,size,null));if(size.get(0)>256)continue;if(size.get(0)==0)continue;
                    try(var statistics=VkPipelineExecutableStatisticKHR.calloc(size.get(0))){
                        for(var statistic:statistics)statistic.sType$Default();
                        VulkanRtCapabilities.check(vkGetPipelineExecutableStatisticsKHR(device.vkDevice(),query,size,statistics));
                        for(int j=0;j<size.get(0);j++){
                            statisticCount++;var statistic=statistics.get(j);String value=switch(statistic.format()){
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
            report(stage,new Status(true,executableCount,statisticCount,statisticCount==0?"driver returned no bounded executable statistics":null));
        }catch(RuntimeException error){report(stage,new Status(true,executableCount,statisticCount,error.getClass().getSimpleName()+": "+error.getMessage()));org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("RT compiler statistics unavailable",error);}
    }
    public static void exportStatus(Path path)throws IOException{
        var report=new LinkedHashMap<String,Object>();report.put("stages",status);report.put("rows",rows.size());
        report.put("limits","Driver compiler statistics only. Runtime register spill and L1/L2 traffic require external GPU profiling; missing values are not zero.");
        Files.writeString(path,new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report));
    }
    private static String csv(String text){return "\""+text.replace("\"","\"\"")+"\"";}
    public static void export(Path path)throws IOException{try(var writer=Files.newBufferedWriter(path)){writer.write("stage,executable,name,description,value\n");for(var row:rows.values())writer.write(csv(row.stage)+","+csv(row.executable)+","+csv(row.name)+","+csv(row.description)+","+csv(row.value)+"\n");}}
}
