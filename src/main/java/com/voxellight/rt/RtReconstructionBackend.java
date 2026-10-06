package com.voxellight.rt;

/** Reconstruction selection is independent of transport; availability must be checked before activation. */
public enum RtReconstructionBackend {
    DLSS_RR, OPTIX_TEMPORAL_AOV, VULKAN_TEMPORAL_SPATIAL, NONE;
    public boolean requiresHistory() { return this != NONE; }
}
