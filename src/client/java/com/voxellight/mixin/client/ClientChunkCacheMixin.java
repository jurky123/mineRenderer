package com.voxellight.mixin.client;

import com.voxellight.VoxelLightClient;
import com.voxellight.world.WorldSceneBridge;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void voxellight$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        VoxelLightClient.scene().sectionChanged(level, pos.x(), pos.y(), pos.z(), WorldSceneBridge.LIGHT);
    }
}
