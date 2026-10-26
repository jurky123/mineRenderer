/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small Minecraft settings page; native Graphics Settings still owns DLSS/RR/FG. */
public final class PortRenderSettingsScreen extends Screen {
    private final Screen parent;
    public PortRenderSettingsScreen(Screen parent){super(Component.literal("RTX Remix 渲染"));this.parent=parent;}
    @Override protected void init(){
        int y=Math.max(40,height/2-65);
        var samples=addRenderableWidget(Button.builder(Component.literal("光照每像素采样："+PortSampling.label(PortPreferences.current.samplingRate)),b->{
            int index=0;for(int i=0;i<PortSampling.CHOICES.length;i++)if(PortSampling.CHOICES[i]==PortPreferences.current.samplingRate)index=i;
            PortSampling.select(PortSampling.CHOICES[(index+1)%PortSampling.CHOICES.length]);b.setMessage(Component.literal("光照每像素采样："+PortSampling.label(PortPreferences.current.samplingRate)));
        }).bounds(width/2-140,y,280,20).build());
        samples.active=PortRenderer.api()!=null;
        samples.setTooltip(Tooltip.create(Component.literal("越高越清晰、越稳定，GPU 开销也越高。低于 1 为稀疏采样，需开启光线重建。")));
        var nativeSettings=addRenderableWidget(Button.builder(Component.literal("DLSS / 光线重建 / 帧生成…"),b->{minecraft.gui.setScreen(null);PortInput.openGraphics();}).bounds(width/2-140,y+91,280,20).build());
        nativeSettings.active=PortRenderer.api()!=null;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),b->onClose()).bounds(width/2-100,height-28,200,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partial){
        super.extractRenderState(g,mouseX,mouseY,partial);
        int y=Math.max(40,height/2-65);
        g.centeredText(font,title,width/2,14,0xffffffff);
        g.centeredText(font,"1 表示每个内部渲染像素每帧采样；较低值跳过部分像素。",width/2,y+28,0xffc0c0c0);
        g.centeredText(font,"当前运行库支持最高 1；2 / 4 SPP 需要原生渲染器支持。",width/2,y+41,0xffc0c0c0);
        g.centeredText(font,"手动采样切换至自定义画质，保留其他当前选项。",width/2,y+54,0xffc0c0c0);
        if(PortRenderer.api()==null)g.centeredText(font,"进入世界后可调整采样",width/2,29,0xffffdd88);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
