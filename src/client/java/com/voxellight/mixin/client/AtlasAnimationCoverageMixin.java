package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.voxellight.adapter.RtMaterialCoverage;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;

/** Only the exact vanilla animation upload is trusted; arbitrary atlas render passes invalidate coverage. */
@Mixin(TextureAtlas.class)
abstract class AtlasAnimationCoverageMixin {
    @WrapMethod(method="uploadAnimationFrames")
    private void voxellight$animationCoverage(Operation<Void> original){
        var view=((TextureAtlas)(Object)this).getTextureView();
        if(view==null){original.call();return;}
        RtMaterialCoverage.animationPass(view.texture(),()->original.call());
    }
}
