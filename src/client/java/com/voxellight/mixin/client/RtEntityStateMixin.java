package com.voxellight.mixin.client;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Retains entity identity across native render-state extraction and offscreen RT reuse. */
@Mixin(EntityRenderer.class)
abstract class RtEntityStateMixin {
 @Inject(method="createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",at=@At("RETURN"))
 private void voxellight$rtIdentity(Entity entity,float partialTick,CallbackInfoReturnable<EntityRenderState> ci){
  if(com.voxellight.adapter.RtGeometryStream.enabled())com.voxellight.adapter.RtDynamicStream.associate(ci.getReturnValue(),entity);
 }
}
