package com.voxellight.mixin.client;

import com.voxellight.adapter.IndigoMaterialCarrier;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MutableQuadViewImpl.class)
abstract class IndigoQuadResetMixin {
    @Inject(method="clear()Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;",at=@At("HEAD"))
    private void voxellight$clearMaterial(CallbackInfoReturnable<MutableQuadViewImpl> ci){((IndigoMaterialCarrier)this).voxellight$setMaterial(null);}
}
