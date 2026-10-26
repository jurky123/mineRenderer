/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.port.PortEntities;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ModelPart.Cube.class)
public abstract class ModelCubeMixin {
    @Inject(method="compile",at=@At("HEAD"))
    private void remix$capture(PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color,CallbackInfo ci){PortEntities.capture((ModelPart.Cube)(Object)this,pose,color);}
    @Inject(method="compile",at=@At("RETURN"))
    private void remix$end(CallbackInfo ci){PortEntities.endCube();}
}
