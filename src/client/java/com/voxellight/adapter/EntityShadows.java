package com.voxellight.adapter;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import org.slf4j.LoggerFactory;

/** Loaded entity selection/extraction; private vertex ownership lives in DynamicModelBuffer. */
final class EntityShadows implements AutoCloseable {
    private final DynamicModelBuffer buffer=new DynamicModelBuffer("entity");
    private boolean enabled=true;
    private int selected,candidates,overflow,failures;
    private long captureNanos;
    private boolean loggedFailure;
    void prepare() {
        long start = System.nanoTime();
        buffer.begin(); selected = candidates = overflow = failures = 0;
        if (!enabled) { captureNanos = 0; return; }
        var minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var selection = new DynamicCasterSelection<Entity>();
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isInvisible() || entity.isSpectator()) continue;
            var p = entity.getPosition(partial);
            selection.consider(entity, entity.getId(), p.distanceToSqr(camera.pos));
        }
        var entities = selection.selected(); selected = entities.size(); candidates = selection.candidates(); overflow = selection.overflow();
        var collection = new SubmitNodeCollection() {
            @Override public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type,
                    int light, int overlay, int tint, TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
                buffer.capture(model, state, pose, type, light, overlay, tint, sprite);
            }
            // Blob shadows, labels, flame, leash, custom geometry and item submits remain private and are discarded.
        };
        var collector = new SubmitNodeStorage() {
            @Override public SubmitNodeCollection order(int ignored) { return collection; }
        };
        var dispatcher = minecraft.getEntityRenderDispatcher();
        for (var entity : entities) {
            // A failing renderer cannot retain a partially submitted entity or disable terrain lighting.
            var before = buffer.checkpoint();
            try {
                var state = dispatcher.extractEntity(entity, partial);
                if (state.isInvisible) continue;
                dispatcher.submit(state, camera, state.x - camera.pos.x(), state.y - camera.pos.y(), state.z - camera.pos.z(), new PoseStack(), collector);
            } catch (RuntimeException error) {
                buffer.rollback(before); failures++;
                if (!loggedFailure) {
                    LoggerFactory.getLogger("VoxelLight").warn("Skipping unsupported entity shadow renderer; terrain shadows remain active", error);
                    loggedFailure = true;
                }
            }
        }
        buffer.finish();
        captureNanos = System.nanoTime() - start;
    }

    static <S> MeshData buildModel(ByteBufferBuilder scratch,Model<? super S> model,S state,PoseStack pose,
            int light,int overlay,int tint,TextureAtlasSprite sprite) {
        return DynamicModelBuffer.buildModel(scratch,model,state,pose,light,overlay,tint,sprite);
    }
    void upload(CommandEncoder encoder){buffer.upload(encoder);}
    int maxIndices(){return buffer.maxIndices();}
    void borrowIndices(com.mojang.blaze3d.buffers.GpuBuffer indices,com.mojang.blaze3d.IndexType type){buffer.borrowIndices(indices,type);}
    boolean hasModels(){return buffer.hasModels();}
    void draw(RenderPass pass){buffer.draw(pass);}
    void setEnabled(boolean enabled){this.enabled=enabled;}
    String status(){return ", entityShadows="+(enabled?"on":"off")+", entityCasters="+selected+"/"+candidates
            +", entityOverflow="+overflow+", entityModels="+buffer.modelCount()+", entitySkipped="+buffer.skipped()
            +", entityFailures="+failures+", entityUploadBytes="+buffer.bytes()+", entityCaptureNs="+captureNanos
            +", entityBufferBytes="+buffer.bufferBytes();}
    @Override public void close(){buffer.close();selected=candidates=overflow=failures=0;captureNanos=0;loggedFailure=false;}
}
