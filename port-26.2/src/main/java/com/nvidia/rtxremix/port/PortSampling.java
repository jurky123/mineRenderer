/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;
import com.nvidia.rtxremix.RemixAPI;
import java.util.*;

/** Explicit, persisted lighting sample density; never overrides existing defaults until selected. */
public final class PortSampling {
    public static final float[] CHOICES={0,1/16f,1/8f,1/4f,1/2f,1};
    private static final List<String> KEYS=List.of("rtx.sparseRendering.samplingRate","rtx.sparseRendering.enableAdaptiveSamplingRate","rtx.sparseRendering.enableSparseRendering");
    private static RemixAPI appliedApi;
    private static float appliedRate=-1;
    private PortSampling(){}
    public static boolean valid(float value){for(float rate:CHOICES)if(value==rate)return true;return false;}
    public static void select(float rate){
        if(!valid(rate))throw new IllegalArgumentException("Sampling density must be auto, 1/16, 1/8, 1/4, 1/2 or 1");
        var api=PortRenderer.api();if(api==null)throw new IllegalStateException("Enter a Remix world first");
        var pref=PortPreferences.current;
        if(rate>0&&pref.samplingRate==0){pref.samplingRestore.clear();for(String key:KEYS)pref.samplingRestore.put(key,api.getConfigVariableResolved(key));pref.samplingRestore.put("rtx.graphicsPreset",api.getConfigVariableResolved("rtx.graphicsPreset"));}
        if(rate==0){for(String key:KEYS)if(pref.samplingRestore.containsKey(key))api.setConfigVariable(key,pref.samplingRestore.get(key));if(pref.samplingRestore.containsKey("rtx.graphicsPreset"))api.setConfigVariable("rtx.graphicsPreset",pref.samplingRestore.get("rtx.graphicsPreset"));pref.samplingRestore.clear();}
        pref.samplingRate=rate;PortPreferences.save();appliedRate=-1;apply(api);
    }
    public static void apply(RemixAPI api){
        float rate=PortPreferences.current.samplingRate;
        if(rate==0||appliedApi==api&&appliedRate==rate)return;
        // The native Custom transition migrates the entire resolved quality preset to
        // User Settings unchanged. Wait for that migration before changing only density.
        if(!api.getConfigVariableResolved("rtx.graphicsPreset").equals("4")){
            api.setConfigVariable("rtx.graphicsPreset","4");return;
        }
        // Resolution adaptation would otherwise change the selected per-internal-pixel budget.
        api.setConfigVariable(KEYS.get(0),Float.toString(rate));
        api.setConfigVariable(KEYS.get(1),"False");
        api.setConfigVariable(KEYS.get(2),rate<1?"True":"False");
        appliedApi=api;appliedRate=rate;
    }
    public static String label(float rate){return rate==0?"原画质设置":rate==1?"1（全像素）":rate==.5f?"1/2":rate==.25f?"1/4":rate==.125f?"1/8":"1/16";}
}
