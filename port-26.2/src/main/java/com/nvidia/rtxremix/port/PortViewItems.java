/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.port.texture.PortTextures;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import java.nio.*;
import java.util.*;

/** Render-thread owner of first-person baked-item meshes and persistent motion history. */
public final class PortViewItems {
    record Geometry(List<BakedQuad> quads,List<Integer> tints){}
    private record Captured(int hand,int part,Geometry geometry,float[] transform){}
    private static final class Retained {
        final Geometry geometry;final PortRenderer.Asset asset;float[] transform;
        Retained(Geometry geometry,PortRenderer.Asset asset,float[] transform){this.geometry=geometry;this.asset=asset;this.transform=transform;}
    }
    private static final RetainedEntityPartRegistry<Object,Retained> registry=new RetainedEntityPartRegistry<>();
    private static final int[] occurrences=new int[ItemDisplayContext.values().length];
    private static final List<Captured> captured=new ArrayList<>();
    private static boolean suppressed;
    public static int liveItems;
    public static long meshCreates,instanceUpdates;
    private PortViewItems(){}
    static void begin(){captured.clear();Arrays.fill(occurrences,0);suppressed=false;}
    public static boolean suppressed(){return suppressed;}
    public static void enter(ItemFeatureRenderer.Submit node){
        if(!PortViewModel.capturing()||node.quads().isEmpty())return;
        FeatureCapture.flushPending();
        int hand=node.displayContext().ordinal(),part=occurrences[hand]++;
        var geometry=new Geometry(List.copyOf(node.quads()),Arrays.stream(node.tintLayers()).boxed().toList());
        var matrix=PortViewModel.cameraToWorld().mul(PortViewModel.transform(node.pose().pose()));
        captured.add(new Captured(hand,part,geometry,PortRenderer.matrix3x4(matrix)));
        suppressed=true;
    }
    public static void leave(){if(suppressed){FeatureCapture.flushPending();suppressed=false;}}
    public static void publish(){
        registry.beginFrame();
        for(Captured item:captured){
            Retained retained=registry.retain(PortViewModel.OWNER,InstanceCategoryBit.VIEW_MODEL,item.hand(),item.part(),old->old.geometry.equals(item.geometry()),()->{
                var asset=PortRenderer.createAsset(build(item.geometry()),item.transform(),0x26280000L^item.geometry().hashCode(),InstanceCategoryBit.VIEW_MODEL,0);
                meshCreates++;return new Retained(item.geometry(),asset,item.transform().clone());
            },old->PortRenderer.removeAsset(old.asset));
            if(!Arrays.equals(retained.transform,item.transform())){
                PortRenderer.updateInstance(retained.asset.instance(),retained.asset.mesh(),item.transform(),InstanceCategoryBit.VIEW_MODEL);
                retained.transform=item.transform().clone();instanceUpdates++;
            }
        }
        registry.endFrame(old->PortRenderer.removeAsset(old.asset));liveItems=registry.size();captured.clear();
    }
    private static List<MeshBatch> build(Geometry geometry){
        Map<PortTextures.Key,VertexBytes> groups=new LinkedHashMap<>();
        for(BakedQuad quad:geometry.quads()){
            var definition=FeatureCapture.definition(quad.materialInfo().itemRenderType());if(definition==null)continue;
            float u=0,v=0;for(int i=0;i<4;i++){u+=UVPair.unpackU(quad.packedUV(i));v+=UVPair.unpackV(quad.packedUV(i));}
            var region=PortTextures.region(definition.texture(),u/4,v/4);
            var pixels=region==null?PortTextures.standalone(definition.texture()):region.pixels();
            var out=groups.computeIfAbsent(PortTextures.key(pixels,definition.kind()),k->new VertexBytes()).append(4*64);
            int tint=quad.materialInfo().tintIndex();int color=quad.materialInfo().isTinted()&&tint>=0&&tint<geometry.tints().size()?geometry.tints().get(tint):-1;
            var direction=quad.direction();
            for(int i=0;i<4;i++){
                var p=quad.position(i);float qu=UVPair.unpackU(quad.packedUV(i)),qv=UVPair.unpackV(quad.packedUV(i));
                if(region!=null){qu=(qu-region.u0())/(region.u1()-region.u0());qv=(qv-region.v0())/(region.v1()-region.v0());}
                out.putFloat(p.x()).putFloat(p.y()).putFloat(p.z()).putFloat(direction.getStepX()).putFloat(direction.getStepY()).putFloat(direction.getStepZ()).putFloat(qu).putFloat(qv).putInt(color);
                for(int padding=0;padding<7;padding++)out.putInt(0);
            }
        }
        var batches=new ArrayList<MeshBatch>();for(var entry:groups.entrySet())batches.add(new MeshBatch(entry.getKey(),entry.getValue().copy(),true));return batches;
    }
    public static void clear(){
        if(PortRenderer.api()!=null)registry.clear(old->PortRenderer.removeAsset(old.asset));
        begin();liveItems=0;
    }
}
