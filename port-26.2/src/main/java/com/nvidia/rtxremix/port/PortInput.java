/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.input.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Adapts the published renderer's input ownership policy to 26.2 callbacks. */
public final class PortInput {
    private static final RemixInputGate gate = new RemixInputGate();
    private static final boolean trace=Boolean.getBoolean("remix.port.inputTrace");
    private PortInput() {}
    public static boolean captured() { return gate.isCaptureSessionOpen(); }
    public static RemixInputGate.KeyDecision onKey(int key,int action,int mods) {
        if (PortRenderer.api() == null) return RemixInputGate.KeyDecision.PASS;
        long window=Minecraft.getInstance().getWindow().handle();
        boolean alt=(mods&4)!=0 || org.lwjgl.glfw.GLFW.glfwGetKey(window,342)==1 || org.lwjgl.glfw.GLFW.glfwGetKey(window,346)==1;
        boolean control=(mods&2)!=0 || org.lwjgl.glfw.GLFW.glfwGetKey(window,341)==1 || org.lwjgl.glfw.GLFW.glfwGetKey(window,345)==1;
        var decision=gate.onKey(key,action,alt,control);
        if(trace) org.slf4j.LoggerFactory.getLogger("Remix26Port").info("Input key={} action={} mods={} capture={} decision={}",key,action,mods,captured(),decision);
        return decision;
    }
    public static void toggle() {
        RemixAPI api=PortRenderer.api();
        if(api==null) return;
        if(captured()) { gate.close(api::setConfigVariable); release(); }
        else { gate.open(RemixInputGate.Menu.DEVELOPER,api::setConfigVariable); acquire(); }
        org.slf4j.LoggerFactory.getLogger("Remix26Port").info("Remix menu toggled: capture={}",captured());
    }
    public static void openGraphics(){
        var api=PortRenderer.api();if(api==null)return;
        gate.open(RemixInputGate.Menu.GRAPHICS_SETTINGS,api::setConfigVariable);acquire();
    }
    public static void follow() {
        RemixAPI api=PortRenderer.api();
        if(api==null) return;
        switch(gate.followVisibility(api::getConfigVariableResolved)) {
            case OPENED -> {acquire();org.slf4j.LoggerFactory.getLogger("Remix26Port").info("Native menu acquired Minecraft input");}
            case CLOSED -> {release();org.slf4j.LoggerFactory.getLogger("Remix26Port").info("Native menu released Minecraft input");}
            case OWN_WRITE_OVERDUE -> org.slf4j.LoggerFactory.getLogger("Remix26Port").warn("Native menu visibility write overridden");
            default -> {}
        }
    }
    private static void acquire() {
        Minecraft mc=Minecraft.getInstance();
        KeyMapping.releaseAll();
        ((RemixKeyboardAccess)mc.keyboardHandler).rtxremix$resetDebugKeyState();
        ((RemixMouseAccess)mc.mouseHandler).rtxremix$releaseActiveButton();
        mc.mouseHandler.releaseMouse();
    }
    private static void release() {
        Minecraft mc=Minecraft.getInstance();
        KeyMapping.releaseAll();
        if(mc.level!=null && mc.gui.screen()==null) mc.mouseHandler.grabMouse();
    }
    public static void close() {
        RemixAPI api=PortRenderer.api();
        if(api!=null) gate.close(api::setConfigVariable);
        KeyMapping.releaseAll();
    }
}
