package com.voxellight.adapter;

import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Mixin bridge to the native section maintenance needed when its frame graph is omitted. */
public interface RtWorldMaintenance {
    void voxellight$maintainWorld(CameraRenderState camera);
}
