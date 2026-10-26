/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.nvidia.rtxremix.port.cloud.PortClouds;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

/** Client-side authoring commands; no /rtx command is sent to a remote server. */
public final class PortCommands {
    private PortCommands(){}
    private static PortSceneConfig scene()throws CommandSyntaxException{var value=PortRenderer.sceneConfig();if(value==null)throw new SimpleCommandExceptionType(Component.literal("Enter a Remix world first")).create();return value;}
    public static void register(){
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(literal("rtx")
            .then(literal("graphics").executes(context->{Minecraft.getInstance().schedule(PortInput::openGraphics);return 1;}))
            .then(literal("sampling").executes(context->{Minecraft.getInstance().schedule(()->{var mc=Minecraft.getInstance();mc.gui.setScreen(new PortRenderSettingsScreen(mc.gui.screen()));});return 1;})
                .then(literal("auto").executes(context->{PortSampling.select(0);return 1;}))
                .then(argument("value",FloatArgumentType.floatArg(1/16f,1)).executes(context->{float value=FloatArgumentType.getFloat(context,"value");if(!PortSampling.valid(value))throw new SimpleCommandExceptionType(Component.literal("Choose 0.0625, 0.125, 0.25, 0.5 or 1")).create();PortSampling.select(value);return 1;})))
            .then(literal("clouds")
                .then(literal("vanilla").executes(context->{PortPreferences.setMode(PortClouds.Mode.VANILLA);return 1;}))
                .then(literal("procedural").executes(context->{PortPreferences.setMode(PortClouds.Mode.PROCEDURAL);return 1;}))
                .then(literal("off").executes(context->{PortPreferences.setMode(PortClouds.Mode.OFF);return 1;}))
                .then(literal("size").then(argument("value",FloatArgumentType.floatArg(.1f,2)).executes(context->{PortPreferences.setCloud("size",FloatArgumentType.getFloat(context,"value"));return 1;})))
                .then(literal("height").then(argument("value",FloatArgumentType.floatArg(-128,128)).executes(context->{PortPreferences.setCloud("height",FloatArgumentType.getFloat(context,"value"));return 1;})))
                .then(literal("smoothness").then(argument("value",FloatArgumentType.floatArg(0,1.99f)).executes(context->{PortPreferences.setCloud("smoothness",FloatArgumentType.getFloat(context,"value"));return 1;}))))
            .then(literal("status").executes(context->{context.getSource().sendFeedback(Component.literal(scene().describe()));return 1;}))
            .then(literal("conf").then(literal("reload").executes(context->{boolean result=scene().reload();context.getSource().sendFeedback(Component.literal("Remix profiles reloaded: "+result));return result?1:0;})))
            .then(literal("zone")
                .then(literal("define").then(argument("definition",StringArgumentType.greedyString()).executes(context->{
                    try{scene().define(StringArgumentType.getString(context,"definition"),context.getSource().getPosition());}
                    catch(IllegalArgumentException e){throw new SimpleCommandExceptionType(Component.literal(e.getMessage())).create();}
                    context.getSource().sendFeedback(Component.literal("Remix zone defined"));return 1;})))
                .then(literal("remove").then(argument("name",StringArgumentType.word()).executes(context->{scene().remove(StringArgumentType.getString(context,"name"));return 1;})))
                .then(literal("rescan").executes(context->{scene().rescan();return 1;})))));
        ServerChunkEvents.CHUNK_LOAD.register((world,chunk,newlyGenerated)->{
            var current=PortRenderer.sceneConfig();
            if(current!=null&&world.getServer()==Minecraft.getInstance().getSingleplayerServer())current.scan(chunk);
        });
    }
}
