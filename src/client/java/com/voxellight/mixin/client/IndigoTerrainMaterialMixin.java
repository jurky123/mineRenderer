package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.voxellight.adapter.*;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AltModelBlockRendererImpl.class)
abstract class IndigoTerrainMaterialMixin {
    @Shadow private BlockColors blockColors;
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow private BlockState blockState;
    @WrapMethod(method="transform")
    private boolean voxellight$unlitMaterial(MutableQuadView quad,Operation<Boolean> original){
        var carrier=(IndigoMaterialCarrier)quad;
        carrier.voxellight$setMaterial(null);
        var source=quad.tintIndex()<0?null:blockColors.getTintSource(blockState,quad.tintIndex());
        int tint=source==null?-1:source.colorInWorld(blockState,level,pos);
        var material=IndigoMaterials.capture(quad,tint,blockState.getLightEmission());
        boolean accepted=original.call(quad);
        if(accepted)carrier.voxellight$setMaterial(material);
        return accepted;
    }
}
