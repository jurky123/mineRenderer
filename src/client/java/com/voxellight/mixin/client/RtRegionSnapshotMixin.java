package com.voxellight.mixin.client;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Rejects late compilation of a section snapshot invalidated by a subsequent block/light edit. */
@Mixin(RenderRegionCache.class)
abstract class RtRegionSnapshotMixin {
 @Inject(method="createRegion",at=@At("RETURN"))
 private void voxellight$rtSnapshot(ClientLevel level,long section,CallbackInfoReturnable<RenderSectionRegion> ci){
  com.voxellight.adapter.RtGeometryStream.snapshot(ci.getReturnValue(),SectionPos.x(section),SectionPos.y(section),SectionPos.z(section));
 }
}
