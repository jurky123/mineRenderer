package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voxellight.VoxelLightClient;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ModelFeatureRenderer.class)
abstract class ModelFeatureRendererMixin {
    @WrapOperation(method="prepareModel",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void voxellight$captureActualModel(Model<?> model,PoseStack pose,VertexConsumer consumer,int light,int overlay,int tint,
            Operation<Void> original,@Local(argsOnly=true) ModelFeatureRenderer.Submit<?> submit) {
        var probe=VoxelLightClient.probe();
        var tee=probe.entityMaterialConsumer(submit,consumer);
        var rt=com.voxellight.adapter.RtDynamicStream.consumer(submit,tee);
        boolean success=false;
        try {original.call(model,pose,rt,light,overlay,tint);success=true;}
        finally {com.voxellight.adapter.RtDynamicStream.finish(rt,success);probe.finishEntityMaterial(tee,success);}
    }
}
