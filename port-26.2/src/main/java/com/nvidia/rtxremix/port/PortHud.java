/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/** Use the Runtime HUD's present count, including generated frames. */
public final class PortHud {
    private static final Pattern HUD_OPTION=Pattern.compile("(?m)^\\s*\\ufeff?dxvk\\.hud\\s*=");
    private PortHud() {}

    /** Install the native FPS default before Runtime startup; preserve explicit user choices. */
    public static void prepare(Path gameDirectory) {
        Path config=gameDirectory.resolve("dxvk.conf");
        try {
            if(Files.exists(config)&&HUD_OPTION.matcher(Files.readString(config)).find()) return;
            Files.writeString(config,"\n# Runtime FPS includes generated frames. Set dxvk.hud = 0 to hide.\n"
                    +"dxvk.hud = fps\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException e) {
            org.slf4j.LoggerFactory.getLogger("Remix26Port").warn("Cannot configure the Remix FPS HUD: {}",config,e);
        }
    }
}
