/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.nvidia.rtxremix.*;
import java.util.Arrays;

/** Official RemixLight.update ownership rule, with a stable hash and unchanged-input skip. */
public final class PortLight {
    private final long hash;
    private long handle;
    private float[] parameters;
    public PortLight(long hash){this.hash=hash;}
    public void publish(RemixAPI api,LightInfo info,float... values){
        if(handle==0 || !Arrays.equals(parameters,values)){
            info.hash=hash;
            long replacement=api.createLight(info),old=handle;
            handle=replacement;parameters=values.clone();
            if(old!=0 && old!=replacement)api.destroyLight(old);
        }
        api.drawLightInstance(handle);
    }
    public void close(RemixAPI api){if(handle!=0)api.destroyLight(handle);handle=0;parameters=null;}
}
