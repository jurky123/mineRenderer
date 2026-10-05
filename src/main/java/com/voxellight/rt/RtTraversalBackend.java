package com.voxellight.rt;
/** Explicit tracer selection. The CUDA path is retained as a comparison backend. */
public enum RtTraversalBackend { CUDA_VOXEL_REFERENCE, OPTIX_RT, VULKAN_RT }
