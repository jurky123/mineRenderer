package com.voxellight.adapter;

/** Classification owned by the same native mesh, so recompiles/reloads cannot inherit stale flags. */
public interface NativeCutoutInfo {
    boolean voxellight$animatedCutout();
}
