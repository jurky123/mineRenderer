/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;
import com.nvidia.rtxremix.port.texture.PortTextures;
import com.nvidia.rtxremix.InstanceCategoryBit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4fc;
import java.util.*;
import java.nio.*;

/** Official owner/part retention and closest-first budget, adapted to 26.2's feature queue. */
public final class PortEntities {
    record Layer(Class<?> modelType,Object type){}
    private record Geometry(ModelPart.Cube cube,Identifier texture,TextureAtlasSprite sprite,int color,PortTextures.Kind kind,Object version){}
    private record Captured(Object owner,int layer,int part,Geometry geometry,float[] transform,int categories){}
    private static final class Shared {final long mesh;int references;Shared(long mesh){this.mesh=mesh;}}
    private static final class Retained {final Geometry geometry;final Shared shared;final long instance;float[] transform;int categories;Retained(Geometry g,Shared s,long i){geometry=g;shared=s;instance=i;}}
    private static final IdentityHashMap<Object,Object> owners=new IdentityHashMap<>();
    private static final IdentityHashMap<Object,Object> submitOwners=new IdentityHashMap<>();
    private static Object submitting;
    private static final Object NO_OWNER=new Object();
    private static final ArrayDeque<Object> submittingStack=new ArrayDeque<>();
    public static void beginSubmit(EntityRenderState state){submittingStack.push(submitting==null?NO_OWNER:submitting);submitting=owners.get(state);}
    public static void endSubmit(){var previous=submittingStack.pop();submitting=previous==NO_OWNER?null:previous;}
    public static void bindSubmit(Object submit){Object value=PortViewModel.capturing()?PortViewModel.OWNER:submitting;if(value!=null)submitOwners.put(submit,value);}
    public static void enterFeature(Object submit){owner=submitOwners.get(submit);}
    public static void leaveFeature(){owner=null;}
    public static int categories(){return PortViewModel.capturing()?InstanceCategoryBit.VIEW_MODEL:owner==Minecraft.getInstance().player&&Minecraft.getInstance().options.getCameraType().isFirstPerson()?InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL|InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY:0;}
    private static final Set<Entity> visible=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Layer,Integer> layers=new HashMap<>();
    private static final IdentityHashMap<ModelPart.Cube,Integer> parts=new IdentityHashMap<>();
    private static final List<Captured> captured=new ArrayList<>();
    private static final Map<Geometry,Shared> shared=new HashMap<>();
    private static final RetainedEntityPartRegistry<Object,Retained> registry=new RetainedEntityPartRegistry<>();
    private static final ClosestKHeap<Entity> heap=new ClosestKHeap<>(256);
    private static ModelFeatureRenderer.Submit<?> model;
    private static Object owner;
    private static boolean suppressed;
    public static int offscreen,skipped,liveParts,viewModelParts,shadowParts;
    public static long meshCreates,instanceUpdates,reused;
    private PortEntities(){}
    public static void beginExtraction(){owners.clear();visible.clear();offscreen=skipped=0;}
    public static void bind(EntityRenderState state,Entity entity){owners.put(state,entity);visible.add(entity);}
    public static void addOffscreen(Camera camera,DeltaTracker delta,LevelRenderState state){
        var mc=Minecraft.getInstance();if(mc.level==null)return;
        if(mc.player!=null&&mc.options.getCameraType().isFirstPerson()&&!mc.player.isSpectator()&&!visible.contains(mc.player)){
            var self=mc.getEntityRenderDispatcher().extractEntity(mc.player,delta.getGameTimeDeltaPartialTick(false));
            state.entityRenderStates.add(self);
        }
        int budget=256-state.entityRenderStates.size();if(budget<=0)return;
        heap.reset(budget);double range=Math.min(112,mc.options.getEffectiveRenderDistance()*16),limit=range*range;
        for(Entity entity:mc.level.entitiesForRendering()){
            if(visible.contains(entity)||entity.isRemoved()||entity.isInvisible()||entity==camera.entity())continue;
            double distance=entity.position().distanceToSqr(camera.position());
            if(distance<=limit)heap.offer(entity,distance);
        }
        skipped=heap.overflowCount();
        for(int i=0;i<heap.size();i++){
            Entity entity=heap.get(i);
            var renderState=mc.getEntityRenderDispatcher().extractEntity(entity,delta.getGameTimeDeltaPartialTick(!mc.level.tickRateManager().isEntityFrozen(entity)));
            state.entityRenderStates.add(renderState);offscreen++;
        }
        heap.clear();
    }
    public static void begin(){captured.clear();submitOwners.clear();submittingStack.clear();submitting=null;model=null;owner=null;suppressed=false;}
    public static void enter(ModelFeatureRenderer.Submit<?> submit){
        model=submit;owner=PortViewModel.capturing()?PortViewModel.OWNER:submitOwners.getOrDefault(submit,owners.get(submit.state()));
        if(owner==null && submit.state() instanceof BlockEntityRenderState block){var world=Minecraft.getInstance().level;if(world!=null)owner=world.getBlockEntity(block.blockPos);}
        if(submit.sheetedDecalPose()!=null)owner=null;
    }
    public static void leave(){model=null;owner=null;}
    public static boolean suppressed(){return suppressed||PortViewItems.suppressed();}
    public static void capture(ModelPart.Cube cube,PoseStack.Pose pose,int color){
        FeatureCapture.flushPending();
        if(owner==null||model==null)return;
        var definition=FeatureCapture.definition(model.renderType());if(definition==null)return;
        // 26.2 creates temporary Model.Simple wrappers for arms; cube identity owns geometry.
        int layer=layers.computeIfAbsent(new Layer(model.model().getClass(),model.renderType()),k->layers.size());
        int part=parts.computeIfAbsent(cube,k->parts.size());
        Matrix4fc matrix=PortViewModel.capturing()?PortViewModel.cameraToWorld().mul(PortViewModel.transform(pose.pose())):pose.pose();
        var camera=PortViewModel.capturing()?net.minecraft.world.phys.Vec3.ZERO:Minecraft.getInstance().gameRenderer.mainCamera().position();
        float[] transform={matrix.m00(),matrix.m10(),matrix.m20(),(float)(matrix.m30()+camera.x),matrix.m01(),matrix.m11(),matrix.m21(),(float)(matrix.m31()+camera.y),matrix.m02(),matrix.m12(),matrix.m22(),(float)(matrix.m32()+camera.z)};
        int categories=categories();
        captured.add(new Captured(owner,layer,part,new Geometry(cube,definition.texture(),model.sprite(),color,definition.kind(),PortTextures.version(definition.texture())),transform,categories));
        suppressed=true;
    }
    public static void endCube(){FeatureCapture.flushPending();suppressed=false;}
    public static void publish(){
        var api=PortRenderer.api();registry.beginFrame();viewModelParts=shadowParts=0;
        for(Captured item:captured){
            if((item.categories()&InstanceCategoryBit.VIEW_MODEL)!=0)viewModelParts++;
            if((item.categories()&InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL)!=0)shadowParts++;
            if((item.categories()&InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL)!=0){var offset=com.nvidia.rtxremix.port.light.PortItemLights.shadowOffset();item.transform()[3]+=offset.x;item.transform()[7]+=offset.y;item.transform()[11]+=offset.z;}
            Retained entry=registry.retain(item.owner(),0,item.layer(),item.part(),old->old.geometry.equals(item.geometry()),()->{
                Geometry key=item.geometry();Shared mesh=shared.get(key);long instance;
                if(mesh==null){var asset=PortRenderer.createAsset(build(key),item.transform(),0x26240000L^key.hashCode(),item.categories(),0);mesh=new Shared(asset.mesh());shared.put(key,mesh);instance=asset.instance();meshCreates++;}
                else instance=PortRenderer.addInstance(mesh.mesh,item.transform(),item.categories());
                mesh.references++;var value=new Retained(key,mesh,instance);value.transform=item.transform().clone();value.categories=item.categories();return value;
            },PortEntities::retire);
            if(!Arrays.equals(entry.transform,item.transform())||entry.categories!=item.categories()){PortRenderer.updateInstance(entry.instance,entry.shared.mesh,item.transform(),item.categories());entry.transform=item.transform().clone();entry.categories=item.categories();instanceUpdates++;}else reused++;
        }
        registry.endFrame(PortEntities::retire);liveParts=registry.size();captured.clear();
    }
    private static List<MeshBatch> build(Geometry key){
        Map<PortTextures.Key,VertexBytes> groups=new LinkedHashMap<>();
        for(var polygon:key.cube().polygons){
            var vertices=polygon.vertices();float cu=0,cv=0;
            for(var v:vertices){cu+=key.sprite()==null?v.u():key.sprite().getU(v.u());cv+=key.sprite()==null?v.v():key.sprite().getV(v.v());}
            var region=PortTextures.region(key.texture(),cu/vertices.length,cv/vertices.length);
            var pixels=region==null?PortTextures.standalone(key.texture()):region.pixels();
            var output=groups.computeIfAbsent(PortTextures.key(pixels,key.kind()),k->new VertexBytes()).append(vertices.length*64);
            for(var v:vertices){
                float u=key.sprite()==null?v.u():key.sprite().getU(v.u()),t=key.sprite()==null?v.v():key.sprite().getV(v.v());
                if(region!=null){u=(u-region.u0())/(region.u1()-region.u0());t=(t-region.v0())/(region.v1()-region.v0());}
                output.putFloat(v.worldX()).putFloat(v.worldY()).putFloat(v.worldZ()).putFloat(polygon.normal().x()).putFloat(polygon.normal().y()).putFloat(polygon.normal().z()).putFloat(u).putFloat(t).putInt(key.color());for(int pad=0;pad<7;pad++)output.putInt(0);
            }
        }
        List<MeshBatch> result=new ArrayList<>();for(var entry:groups.entrySet())result.add(new MeshBatch(entry.getKey(),entry.getValue().copy(),true));return result;
    }
    private static void retire(Retained value){var api=PortRenderer.api();api.sceneInstanceRemove(value.instance);if(--value.shared.references==0){shared.remove(value.geometry);api.destroyMesh(value.shared.mesh);}}
    public static void clear(){if(PortRenderer.api()!=null)registry.clear(PortEntities::retire);owners.clear();submitOwners.clear();submittingStack.clear();submitting=null;visible.clear();captured.clear();layers.clear();parts.clear();model=null;owner=null;suppressed=false;liveParts=0;}
}
