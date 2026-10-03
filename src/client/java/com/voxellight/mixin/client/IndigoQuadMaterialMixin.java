package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxellight.adapter.*;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.QuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(QuadViewImpl.class)
abstract class IndigoQuadMaterialMixin implements IndigoMaterialCarrier {
    @Unique private NativeTerrainAttributes.Attributes[] voxellight$material;
    public NativeTerrainAttributes.Attributes[] voxellight$getMaterial(){return voxellight$material;}
    public void voxellight$setMaterial(NativeTerrainAttributes.Attributes[] value){voxellight$material=value;}
    @WrapMethod(method="buffer(ILcom/mojang/blaze3d/vertex/VertexConsumer;)V")
    private void voxellight$emitMaterial(int overlay,VertexConsumer consumer,Operation<Void> original){
        var material=voxellight$material;voxellight$material=null;
        if(material==null){original.call(overlay,consumer);return;}
        NativeTerrainAttributes.withVertices(material,()->original.call(overlay,consumer));
        IndigoMaterials.recordEmission();
    }
}
