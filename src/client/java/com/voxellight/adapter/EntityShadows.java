package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Immediate native model capture into private, camera-relative vertices. Never touches vanilla submit queues. */
final class EntityShadows implements AutoCloseable {
    private record Draw(int baseVertex, int indices, PreparedRenderType.Texture texture) { }
    private final List<Draw> draws = new ArrayList<>();
    private ByteBuffer frame;
    private ByteBufferBuilder scratch;
    private GpuBuffer vertices;
    private GpuBuffer indices; // Borrowed native sequential buffer; never owned/closed here.
    private IndexType indexType;
    private boolean enabled = true;
    private int selected, candidates, overflow, skipped, failures, modelAttempts, bytes, maxIndices;
    private long captureNanos;
    private boolean loggedFailure;

    void prepare() {
        long start = System.nanoTime();
        draws.clear(); selected = candidates = overflow = skipped = failures = modelAttempts = bytes = maxIndices = 0;
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
        if (!entities.isEmpty() && frame == null) {
            frame = MemoryUtil.memAlloc(DynamicCasterSelection.FRAME_BYTES);
            scratch = new ByteBufferBuilder(4096, DynamicCasterSelection.MODEL_BYTES);
        }
        if (frame != null) frame.clear();
        var collection = new SubmitNodeCollection() {
            @Override public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type,
                    int light, int overlay, int tint, TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling) {
                capture(model, state, pose, type, light, overlay, tint, sprite);
            }
            // Blob shadows, labels, flame, leash, custom geometry and item submits remain private and are discarded.
        };
        var collector = new SubmitNodeStorage() {
            @Override public SubmitNodeCollection order(int ignored) { return collection; }
        };
        var dispatcher = minecraft.getEntityRenderDispatcher();
        for (var entity : entities) {
            // A failing renderer cannot retain a partially submitted entity or disable terrain lighting.
            int before = draws.size(), position = frame.position();
            try {
                var state = dispatcher.extractEntity(entity, partial);
                if (state.isInvisible) continue;
                dispatcher.submit(state, camera, state.x - camera.pos.x(), state.y - camera.pos.y(), state.z - camera.pos.z(), new PoseStack(), collector);
            } catch (RuntimeException error) {
                draws.subList(before, draws.size()).clear(); frame.position(position); failures++;
                if (!loggedFailure) {
                    LoggerFactory.getLogger("VoxelLight").warn("Skipping unsupported entity shadow renderer; terrain shadows remain active", error);
                    loggedFailure = true;
                }
            }
        }
        bytes = frame == null ? 0 : frame.position();
        maxIndices = draws.stream().mapToInt(Draw::indices).max().orElse(0);
        captureNanos = System.nanoTime() - start;
    }

    private <S> void capture(Model<? super S> model, S state, PoseStack pose, RenderType type,
            int light, int overlay, int tint, TextureAtlasSprite sprite) {
        if (type.isOutline() || (type.hasBlending() && !(model instanceof PlayerModel)) || type.primitiveTopology() != PrimitiveTopology.QUADS) { skipped++; return; }
        if (++modelAttempts > DynamicCasterSelection.MAX_MODELS || frame.remaining() < 4 * DefaultVertexFormat.BLOCK.getVertexSize()) { skipped++; return; }
        var texture = type.prepare().textures().stream().filter(binding -> binding.name().equals("Sampler0")).findFirst();
        if (texture.isEmpty()) { skipped++; return; }
        scratch.clear();
        try {
            try (var mesh = buildModel(scratch, model, state, pose, light, overlay, tint, sprite)) {
                if (mesh == null) return;
                var data = mesh.vertexBuffer();
                if (data.remaining() > frame.remaining() || mesh.drawState().vertexCount() % 4 != 0) { skipped++; return; }
                int base = frame.position() / DefaultVertexFormat.BLOCK.getVertexSize();
                frame.put(data);
                draws.add(new Draw(base, mesh.drawState().indexCount(), texture.get()));
            }
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Maximum capacity of ByteBufferBuilder")) skipped++;
            else throw error;
        } finally { scratch.clear(); }
    }

    /** Same native model-to-private-BLOCK conversion used by the live capture and CPU contract tests. */
    static <S> MeshData buildModel(ByteBufferBuilder scratch, Model<? super S> model, S state, PoseStack pose,
            int light, int overlay, int tint, TextureAtlasSprite sprite) {
        var builder = new BufferBuilder(scratch, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        VertexConsumer consumer = sprite == null ? builder : sprite.wrap(builder);
        model.setupAnim(state);
        model.renderToBuffer(pose, consumer, light, overlay, tint);
        return builder.build();
    }

    void upload(CommandEncoder encoder) {
        if (draws.isEmpty()) return;
        if (vertices == null) vertices = RenderSystem.getDevice().createBuffer(() -> "VoxelLight dynamic entity vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, DynamicCasterSelection.FRAME_BYTES);
        encoder.writeToBuffer(vertices.slice(0, bytes), frame.duplicate().flip());
        // Native sequential-buffer growth uploads data. Complete it before opening any render pass.
        var sequence = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        indices = sequence.getBuffer(maxIndices); indexType = sequence.type();
    }
    boolean hasModels() { return !draws.isEmpty(); }
    void draw(RenderPass pass) {
        if (draws.isEmpty()) return;
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, indexType);
        for (var draw : draws) {
            pass.bindTexture("Sampler0", draw.texture().textureView(), draw.texture().sampler());
            pass.drawIndexed(draw.indices(), 1, 0, draw.baseVertex(), 0);
        }
    }
    void setEnabled(boolean enabled) { this.enabled = enabled; }
    String status() {
        return ", entityShadows=" + (enabled ? "on" : "off") + ", entityCasters=" + selected + "/" + candidates
                + ", entityOverflow=" + overflow + ", entityModels=" + draws.size() + ", entitySkipped=" + skipped
                + ", entityFailures=" + failures + ", entityUploadBytes=" + bytes + ", entityCaptureNs=" + captureNanos
                + ", entityBufferBytes=" + (vertices == null ? 0 : DynamicCasterSelection.FRAME_BYTES);
    }
    @Override public void close() {
        draws.clear(); indices = null; indexType = null;
        if (vertices != null) { vertices.close(); vertices = null; }
        if (scratch != null) { scratch.close(); scratch = null; }
        if (frame != null) { MemoryUtil.memFree(frame); frame = null; }
        selected = candidates = overflow = skipped = failures = modelAttempts = bytes = maxIndices = 0;
        captureNanos = 0; loggedFailure = false;
    }
}
