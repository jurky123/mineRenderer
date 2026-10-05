package com.voxellight.rt.vulkan;

/** Device-independent SBT packing. Region starts require base alignment, records handle alignment. */
public record VulkanSbt(long raygenOffset, long missOffset, long hitOffset, long stride, long bytes) {
    public static VulkanSbt layout(int handleSize, int handleAlignment, int baseAlignment, int maxStride) {
        long stride = align(handleSize, handleAlignment);
        if (handleSize <= 0 || stride > maxStride) throw new IllegalArgumentException("Unsupported SBT handle stride");
        long miss = align(stride, baseAlignment), hit = align(miss + stride, baseAlignment);
        return new VulkanSbt(0, miss, hit, stride, Math.addExact(hit, stride));
    }
    public static long align(long value, int alignment) {
        if (value < 0 || alignment <= 0 || (alignment & (alignment-1)) != 0) throw new IllegalArgumentException("Invalid alignment");
        return Math.addExact(value, alignment-1) & -(long)alignment;
    }
}
