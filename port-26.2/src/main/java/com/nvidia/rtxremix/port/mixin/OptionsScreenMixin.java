/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.mixin;
import com.nvidia.rtxremix.port.PortRenderSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    @Unique private Button remix$settings;
    protected OptionsScreenMixin(Component title){super(title);}
    @Inject(method="init",at=@At("TAIL"))
    private void remix$button(CallbackInfo ci){remix$settings=addRenderableWidget(Button.builder(Component.literal("Remix 渲染"),b->minecraft.gui.setScreen(new PortRenderSettingsScreen((Screen)(Object)this))).bounds(width-110,4,100,20).build());}
    @Inject(method="repositionElements",at=@At("TAIL"))
    private void remix$position(CallbackInfo ci){if(remix$settings!=null)remix$settings.setX(width-110);}
}
