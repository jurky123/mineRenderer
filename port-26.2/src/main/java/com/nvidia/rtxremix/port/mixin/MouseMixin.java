/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortInput;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import net.minecraft.client.*;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(MouseHandler.class)
public abstract class MouseMixin implements RemixMouseAccess {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private MouseButtonInfo activeButton;
    @Shadow private int fakeRightMouse;
    @Shadow private double xpos,ypos,accumulatedDX,accumulatedDY;
    @Shadow private boolean ignoreFirstMove,isLeftPressed,isMiddlePressed,isRightPressed;
    @Shadow private void onButton(long window,MouseButtonInfo info,int action) {}
    @Unique private boolean remix$release;
    public void rtxremix$releaseActiveButton() {
        accumulatedDX=accumulatedDY=0;
        if(activeButton!=null) {
            fakeRightMouse=0;
            remix$release=true;
            try { onButton(minecraft.getWindow().handle(),activeButton,0); }
            finally { remix$release=false; }
        }
        isLeftPressed=isMiddlePressed=isRightPressed=false;
        ignoreFirstMove=true;
    }
    @Inject(method="onMove",at=@At("HEAD"),cancellable=true)
    private void remix$move(long window,double x,double y,CallbackInfo ci) {
        if(PortInput.captured()) { xpos=x;ypos=y;accumulatedDX=accumulatedDY=0;ignoreFirstMove=true;ci.cancel(); }
    }
    @Inject(method="onButton",at=@At("HEAD"),cancellable=true)
    private void remix$button(CallbackInfo ci) { if(PortInput.captured()&&!remix$release) ci.cancel(); }
    @Inject(method={"onScroll","grabMouse","handleAccumulatedMovement"},at=@At("HEAD"),cancellable=true)
    private void remix$block(CallbackInfo ci) { if(PortInput.captured()) ci.cancel(); }
    @Inject(method={"xpos","ypos","getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D","getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D"},at=@At("HEAD"),cancellable=true)
    private void remix$hide(CallbackInfoReturnable<Double> ci) { if(PortInput.captured()) ci.setReturnValue(-Double.MAX_VALUE); }
}
