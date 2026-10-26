/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.port;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import java.util.List;

/** Official 0.1 eye-relative scale and FOV correction on 26.2's real hand submission. */
public final class PortViewModel implements AutoCloseable {
    public interface GameAccess { void remix$renderHand(CameraRenderState camera,float partialTick,Matrix4fc viewRotation); }
    static final Object OWNER=new Object();
    public static final float SCALE=.1f;
    private static boolean capturing;
    private static final Matrix4f correction=new Matrix4f();
    private static float fovScale;
    private final RenderBuffers buffers;
    private final FeatureRenderDispatcher dispatcher;
    public static int vertices;
    public PortViewModel() {
        var mc=Minecraft.getInstance();buffers=new RenderBuffers(1);
        dispatcher=new FeatureRenderDispatcher(buffers,mc.getModelManager(),mc.getAtlasManager(),mc.font,mc.gameRenderer.gameRenderState());
    }
    public static boolean capturing(){return capturing;}
    public List<MeshBatch> capture(GameRenderer renderer,CameraRenderState camera,float partialTick) {
        FeatureCapture.begin();PortViewItems.begin();capturing=true;vertices=0;
        float worldTan=(float)(1/camera.projectionMatrix.m11());
        float handTan=(float)Math.tan(Math.toRadians(camera.hudFov)*.5);
        fovScale=worldTan/handTan;
        // The early capture runs inside the world's rotated model-view scope.
        // Vanilla's hand method supplies its own camera rotation from an identity base.
        var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix().identity();
        try {
            ((GameAccess)renderer).remix$renderHand(camera,partialTick,camera.viewRotationMatrix);
            var batches=FeatureCapture.finish();vertices=FeatureCapture.lastVertices;return batches;
        } finally {modelView.popMatrix();capturing=false;FeatureCapture.clear();buffers.endFrame();}
    }
    /** Called after vanilla pushes the camera rotation, tilt, hurt and bob stack. */
    public static void handModelView(){setViewCorrection(correction,RenderSystem.getModelViewStack(),fovScale);}
    static void setViewCorrection(Matrix4f destination,Matrix4fc modelView,float scale){destination.identity().scale(scale*SCALE,scale*SCALE,SCALE).mul(modelView);}
    public static void transform(Vector3f vertex){correction.transformPosition(vertex);}
    public static void transformNormal(Vector3f normal){if(normal.lengthSquared()!=0)correction.transformDirection(normal).normalize();}
    public static Matrix4f transform(Matrix4fc matrix){return correction.mul(matrix,new Matrix4f());}
    public void prepare(SubmitNodeStorage storage) {
        try(var prepared=dispatcher.prepareFrame(storage)){FeatureCapture.flushPending();}
    }
    /** Camera axes match vanilla local +X right, +Y up and -Z forward. */
    public static Matrix4f cameraToWorld() {
        var camera=Minecraft.getInstance().gameRenderer.mainCamera();var p=camera.position();
        return new Matrix4f().translation((float)p.x,(float)p.y,(float)p.z).rotate(camera.rotation());
    }
    @Override public void close(){dispatcher.close();buffers.close();}
}
