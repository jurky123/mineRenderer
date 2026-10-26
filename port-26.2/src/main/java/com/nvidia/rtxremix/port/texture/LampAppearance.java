/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.texture;

import net.minecraft.resources.Identifier;
import java.util.EnumMap;

/** Render-thread lamp appearance. Pack emission supplies hue, never an extra mask gain. */
public final class LampAppearance {
    public enum Lamp {
        GLOWSTONE(1,.72f,.38f), LANTERN(1,.65f,.24f), SOUL_LANTERN(.12f,.72f,1),
        SEA_LANTERN(.63f,.9f,1), SHROOMLIGHT(1,.48f,.2f),
        OCHRE_FROGLIGHT(1,.8f,.45f), VERDANT_FROGLIGHT(.7f,1,.65f),
        PEARLESCENT_FROGLIGHT(.95f,.65f,1), REDSTONE_LAMP(1,.56f,.25f);
        private final float[] fallback;
        Lamp(float r,float g,float b){fallback=new float[]{r,g,b};}
    }
    private static final EnumMap<Lamp,float[]> tints=new EnumMap<>(Lamp.class);
    private LampAppearance(){}
    public static Lamp sprite(Identifier id){
        if(!id.getNamespace().equals("minecraft")||!id.getPath().startsWith("block/"))return null;
        String p=id.getPath().substring(6);
        for(Lamp lamp:Lamp.values()){
            String name=lamp.name().toLowerCase(java.util.Locale.ROOT);
            if(p.equals(name)||p.equals(name+"_on")||p.startsWith(name+"_")&&name.endsWith("froglight"))return lamp;
        }
        return null;
    }
    public static Lamp rule(Identifier id){
        if(!id.getNamespace().equals("rtxremix")||!id.getPath().startsWith("port_lamps/"))return null;
        try{return Lamp.valueOf(id.getPath().substring(11).toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException ignored){return null;}
    }
    public static float surfaceIntensity(Identifier id){return sprite(id)==null?100:35;}
    /** Called once at native material creation, not from chunk workers. RGB is emission-weighted already. */
    public static void observe(Identifier id,byte[] emission){
        Lamp lamp=sprite(id);if(lamp==null||emission==null)return;
        double[] sum=new double[3];
        for(int i=0;i<emission.length;i+=4)for(int c=0;c<3;c++){
            double s=(emission[i+c]&255)/255.0;sum[c]+=s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);
        }
        double max=Math.max(sum[0],Math.max(sum[1],sum[2]));
        if(max>0)tints.put(lamp,new float[]{(float)(sum[0]/max),(float)(sum[1]/max),(float)(sum[2]/max)});
    }
    public static float[] tint(Lamp lamp){return tints.getOrDefault(lamp,lamp.fallback).clone();}
    public static void clear(){tints.clear();}
}
