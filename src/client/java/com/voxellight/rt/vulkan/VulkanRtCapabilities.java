package com.voxellight.rt.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Advertised physical features are distinct from features enabled on Minecraft's device. */
public record VulkanRtCapabilities(boolean supported, Set<String> extensions, String reason) {
    public static final Set<String> REQUIRED = Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline", "VK_KHR_deferred_host_operations");
    public static final Set<String> OPTIONAL = Set.of("VK_KHR_ray_query", "VK_EXT_opacity_micromap", "VK_NV_ray_tracing_invocation_reorder", "VK_KHR_ray_tracing_position_fetch", "VK_KHR_pipeline_executable_properties");
    public VulkanRtCapabilities { extensions = Set.copyOf(extensions); }
    public static VulkanRtCapabilities query(VkPhysicalDevice physical) {
        try (var stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            check(vkEnumerateDeviceExtensionProperties(physical, (String)null, count, null));
            Set<String> extensions = new HashSet<>();
            // Extension arrays can exceed the entire LWJGL thread stack on NVIDIA drivers.
            try(var properties = allocateExtensions(count.get(0))) {
                check(vkEnumerateDeviceExtensionProperties(physical, (String)null, count, properties));
                properties.limit(count.get(0));
                for (var property : properties) extensions.add(property.extensionNameString());
            }
            var missing = new TreeSet<>(REQUIRED); missing.removeAll(extensions);
            if (!missing.isEmpty()) return new VulkanRtCapabilities(false, extensions, "missing " + missing);
            var address = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            var accel = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            var pipeline = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            address.pNext(accel.address()); accel.pNext(pipeline.address());
            var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(address.address());
            VK11.vkGetPhysicalDeviceFeatures2(physical, features);
            boolean supported = address.bufferDeviceAddress() && accel.accelerationStructure() && pipeline.rayTracingPipeline();
            return new VulkanRtCapabilities(supported, extensions, supported ? "supported" : "required RT feature unavailable");
        }
    }
    public static boolean indirectTracing(VkPhysicalDevice physical){try(var stack=MemoryStack.stackPush()){
        var pipeline=VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
        VK11.vkGetPhysicalDeviceFeatures2(physical,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(pipeline.address()));
        return pipeline.rayTracingPipelineTraceRaysIndirect();
    }}
    public static boolean executableStatistics(VkPhysicalDevice physical){try(var stack=MemoryStack.stackPush()){var feature=VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceFeatures2(physical,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feature.address()));return feature.pipelineExecutableInfo();}}
    public static boolean rayQuery(VkPhysicalDevice physical){try(var stack=MemoryStack.stackPush()){
        var feature=VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceFeatures2(physical,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feature.address()));return feature.rayQuery();
    }}
    public static boolean micromap(VkPhysicalDevice physical){try(var stack=MemoryStack.stackPush()){
        var feature=VkPhysicalDeviceOpacityMicromapFeaturesEXT.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceFeatures2(physical,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feature.address()));return feature.micromap();
    }}
    public static boolean reorder(VkPhysicalDevice physical){try(var stack=MemoryStack.stackPush()){
        var feature=VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceFeatures2(physical,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feature.address()));
        var property=VkPhysicalDeviceRayTracingInvocationReorderPropertiesNV.calloc(stack).sType$Default();VK11.vkGetPhysicalDeviceProperties2(physical,VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(property.address()));
        return feature.rayTracingInvocationReorder()&&property.rayTracingInvocationReorderReorderingHint()==NVRayTracingInvocationReorder.VK_RAY_TRACING_INVOCATION_REORDER_MODE_REORDER_NV;
    }}
    static VkExtensionProperties.Buffer allocateExtensions(int count) { return VkExtensionProperties.calloc(count); }
    public static void check(int result) { if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan RT result=" + result); }
}
