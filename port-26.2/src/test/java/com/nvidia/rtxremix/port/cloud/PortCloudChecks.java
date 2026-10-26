/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port.cloud;
import java.util.Arrays;
import java.util.ArrayList;
public final class PortCloudChecks {
    public static void run(){
        boolean[] mask=new boolean[9];mask[4]=true;
        var voxel=VanillaCloudMeshGenerator.generate(mask,3,3,0,0,3,3,12,12,4,1.5f);
        assert voxel.vertexCount()>0&&voxel.triangleCount()>0;
        for(int index:voxel.indices())assert index>=0&&index<voxel.vertexCount();
        for(float value:voxel.vertices())assert Float.isFinite(value);
        var a=CloudMeshGenerator.generate(1234);var b=CloudMeshGenerator.generate(1234);
        assert Arrays.equals(a.vertices(),b.vertices())&&Arrays.equals(a.indices(),b.indices()):"Official procedural seed remains deterministic";
        CloudMeshGenerator.releaseWorkspace();
        var first=new ArrayList<VanillaCloudPlacement.Placement>();var second=new ArrayList<VanillaCloudPlacement.Placement>();
        VanillaCloudPlacement.forEachVisible(0,0,0,256,256,c->first.add(c.snapshot()));
        VanillaCloudPlacement.forEachVisible(0,0,40,256,256,c->second.add(c.snapshot()));
        assert !first.isEmpty()&&first.size()<256;
        var p=first.getFirst();var moved=second.stream().filter(c->c.textureKey()==p.textureKey()).findFirst().orElseThrow();
        assert Math.abs((p.x()-moved.x())-1.2)<1e-6:"Official vanilla wind speed";
        System.out.println("PASS: official voxel/procedural clouds, deterministic geometry, bounded placement and wind");
    }
}
