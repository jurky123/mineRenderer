/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.google.gson.Gson;
import com.nvidia.rtxremix.port.cloud.PortClouds;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
/** Minecraft-side cloud geometry options; native graphics settings remain Runtime-owned. */
public final class PortPreferences {
    public static PortPreferences current=new PortPreferences();
    public PortClouds.Mode clouds=PortClouds.Mode.VANILLA;
    public float cloudSize=1,cloudHeight=0,edgeSmoothness=1.5f;
    public float samplingRate=0;
    public java.util.Map<String,String> samplingRestore=new java.util.LinkedHashMap<>();
    private static final Gson JSON=new Gson();
    private static Path path(){return FabricLoader.getInstance().getConfigDir().resolve("remix-port.json");}
    public static void load(){
        Path file=path();if(!Files.isRegularFile(file))return;
        try(var reader=Files.newBufferedReader(file)){var options=JSON.fromJson(reader,PortPreferences.class);options.validate();current=options;}
        catch(java.io.IOException e){throw new java.io.UncheckedIOException("Cannot read "+file,e);}
    }
    private void validate(){
        if(!PortSampling.valid(samplingRate)||samplingRestore==null)throw new IllegalArgumentException("Invalid sampling settings in "+path());
        if(clouds==null||!Float.isFinite(cloudSize)||cloudSize<.1f||cloudSize>2||!Float.isFinite(cloudHeight)||Math.abs(cloudHeight)>128||!Float.isFinite(edgeSmoothness)||edgeSmoothness<0||edgeSmoothness>1.99f)
            throw new IllegalArgumentException("Invalid cloud settings in "+path());
    }
    public static void setMode(PortClouds.Mode mode){current.clouds=mode;save();}
    public static void setCloud(String key,float value){switch(key){case "size"->current.cloudSize=value;case "height"->current.cloudHeight=value;case "smoothness"->current.edgeSmoothness=value;default->throw new IllegalArgumentException(key);}current.validate();save();}
    public static void save(){try{Files.createDirectories(path().getParent());Files.writeString(path(),JSON.toJson(current));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
}
