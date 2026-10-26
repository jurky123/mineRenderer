/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortInput;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin implements RemixKeyboardAccess {
    @Shadow private long debugCrashKeyTime, debugCrashKeyReportedTime, debugCrashKeyReportedCount;
    @Shadow private boolean usedDebugKeyAsModifier;
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void remix$key(long window,int action,KeyEvent event,CallbackInfo ci) {
        switch(PortInput.onKey(event.key(),action,event.modifiers())) {
            case BLOCK -> ci.cancel();
            case TOGGLE_MENU -> { ci.cancel(); PortInput.toggle(); }
            default -> {}
        }
    }
    @Inject(method={"charTyped","preeditCallback"},at=@At("HEAD"),cancellable=true)
    private void remix$text(CallbackInfo ci) { if(PortInput.captured()) ci.cancel(); }
    public void rtxremix$resetDebugKeyState() {
        debugCrashKeyTime=debugCrashKeyReportedTime=debugCrashKeyReportedCount=-1;
        usedDebugKeyAsModifier=false;
    }
}
