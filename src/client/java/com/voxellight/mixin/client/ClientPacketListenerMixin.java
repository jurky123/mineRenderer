package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.voxellight.VoxelLightClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @WrapOperation(method = "readSectionList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"))
    private void voxellight$lightSectionRebuild(ClientLevel level, int x, int y, int z, Operation<Void> original) {
        VoxelLightClient.scene().lightSectionUpdate(() -> original.call(level, x, y, z));
    }
}
