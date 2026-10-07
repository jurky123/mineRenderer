package com.voxellight.mixin.client;

import com.voxellight.adapter.RenderPassProfile;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wall-clock submission/presentation cost; separate from GPU elapsed timestamps. */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {
    @Unique private long voxellight$frameStart;
    @Inject(method="renderFrame",at=@At("HEAD"))
    private void voxellight$begin(boolean tick,CallbackInfo ci){voxellight$frameStart=System.nanoTime();}
    @Inject(method="renderFrame",at=@At("RETURN"))
    private void voxellight$end(boolean tick,CallbackInfo ci){RenderPassProfile.cpu("client_render_frame_wall",System.nanoTime()-voxellight$frameStart);}
}
