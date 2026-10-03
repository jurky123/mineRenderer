package com.voxellight.mixin.client;

import com.voxellight.adapter.NativeCutoutInfo;
import com.voxellight.adapter.NativeTerrainAttributes;
import com.voxellight.world.CutoutAnimation;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class NativeCutoutMeshMixin implements NativeCutoutInfo {
    @Unique private boolean voxellight$animatedCutout=true;
    @Inject(method="<init>",at=@At("TAIL"))
    private void voxellight$classify(TranslucencyPointOfView pointOfView,SectionCompiler.Results results,CallbackInfo ci) {
        var cutout=results.renderedLayers.get(ChunkSectionLayer.CUTOUT);
        voxellight$animatedCutout=cutout!=null && (cutout.drawState().format()!=NativeTerrainAttributes.FORMAT || CutoutAnimation.needsRefresh(cutout.vertexBuffer(),cutout.drawState().format().getVertexSize()));
    }
    public boolean voxellight$animatedCutout(){return voxellight$animatedCutout;}
}
