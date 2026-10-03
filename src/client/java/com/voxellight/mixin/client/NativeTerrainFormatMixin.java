package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.voxellight.adapter.NativeTerrainAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DefaultVertexFormat.class)
abstract class NativeTerrainFormatMixin {
    @ModifyExpressionValue(method="<clinit>",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexFormat$Builder;build()Lcom/mojang/blaze3d/vertex/VertexFormat;",ordinal=0))
    private static VertexFormat voxellight$materialAttributes(VertexFormat original){return NativeTerrainAttributes.FORMAT;}
}
