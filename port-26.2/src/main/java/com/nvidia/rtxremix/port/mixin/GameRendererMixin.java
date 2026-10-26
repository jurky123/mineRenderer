/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortRenderer;
import com.nvidia.rtxremix.port.PortInput;
import com.nvidia.rtxremix.port.PortViewModel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void remix$hand(CallbackInfo ci){if(PortRenderer.api()!=null&&!PortViewModel.capturing())ci.cancel();}
    @Redirect(method="renderItemInHand",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V"))
    private void remix$prepareHand(FeatureRenderDispatcher vanilla,SubmitNodeStorage storage){
        if(PortViewModel.capturing())PortRenderer.viewModel().prepare(storage);else vanilla.renderAllFeatures(storage);
    }
    @Inject(method="render",at=@At("HEAD"))
    private void remix$input(CallbackInfo ci) { PortInput.follow(); }
    @Inject(method="renderLevel",at=@At("HEAD"))
    private void remix$begin(CallbackInfo ci) { PortRenderer.beginLevel(); }
    @Inject(method="renderLevel",at=@At("RETURN"))
    private void remix$render(DeltaTracker delta,CallbackInfo ci) { PortRenderer.finishLevel((GameRenderer)(Object)this); }
}
