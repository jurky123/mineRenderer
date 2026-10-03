package com.voxellight.adapter;

/** Metadata belongs to the reusable quad, not a thread's last emitted material. */
public interface IndigoMaterialCarrier {
    NativeTerrainAttributes.Attributes[] voxellight$getMaterial();
    void voxellight$setMaterial(NativeTerrainAttributes.Attributes[] value);
}
