package com.voxellight.mixin.client;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.voxellight.adapter.GpuBackendAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(GpuDevice.class)
public interface GpuDeviceMixin extends GpuBackendAccess {
    @Accessor("backend") GpuDeviceBackend voxellight$backend();
}
