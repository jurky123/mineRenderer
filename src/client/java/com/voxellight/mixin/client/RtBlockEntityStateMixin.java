package com.voxellight.mixin.client;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockEntityRenderDispatcher.class)
abstract class RtBlockEntityStateMixin {
 @Inject(method="tryExtractRenderState",at=@At("RETURN"))
 private void voxellight$rtIdentity(BlockEntity block,float partialTick,ModelFeatureRenderer.CrumblingOverlay overlay,boolean visible,CallbackInfoReturnable<BlockEntityRenderState> ci){
  if(com.voxellight.adapter.RtGeometryStream.enabled())com.voxellight.adapter.RtDynamicStream.associateBlock(ci.getReturnValue(),block);
 }
}
