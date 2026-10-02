package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import com.voxellight.world.WorldSceneBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void voxellight$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        VoxelLightClient.scene().blockChanged((ClientLevel) (Object) this, pos.getX(), pos.getY(), pos.getZ());
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("TAIL"))
    private void voxellight$sectionChanged(int x, int y, int z, CallbackInfo ci) {
        VoxelLightClient.scene().sectionChanged((ClientLevel) (Object) this, x, y, z, WorldSceneBridge.GEOMETRY);
    }

    @Inject(method = "setSectionRangeDirty", at = @At("TAIL"))
    private void voxellight$rangeChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        VoxelLightClient.scene().rangeChanged((ClientLevel) (Object) this, minX, minY, minZ, maxX, maxY, maxZ);
    }
}
