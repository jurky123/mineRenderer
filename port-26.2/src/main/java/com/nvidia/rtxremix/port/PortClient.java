/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

/** Minecraft 26.2 integration prototype; native API and interop remain upstream. */
public final class PortClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PortPreferences.load();
        if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-command-api-v2")
                &&net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-lifecycle-events-v1"))PortCommands.register();
        LoggerFactory.getLogger("Remix26Port").info(
                "Remix 26.2 integration prototype: input/lifecycle, LabPBR textures and world features; parity pending");
    }
}
