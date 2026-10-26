/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortEntities;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityDispatcherMixin {
    @Inject(method="submit",at=@At("HEAD"))
    private void remix$submit(EntityRenderState state,net.minecraft.client.renderer.state.level.CameraRenderState camera,double x,double y,double z,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.SubmitNodeCollector storage,CallbackInfo ci){PortEntities.beginSubmit(state);}
    @Inject(method="submit",at=@At("RETURN"))
    private void remix$submitted(CallbackInfo ci){PortEntities.endSubmit();}
    @Inject(method="extractEntity",at=@At("RETURN"))
    private void remix$identity(Entity entity,float delta,CallbackInfoReturnable<EntityRenderState> ci){PortEntities.bind(ci.getReturnValue(),entity);}
}
