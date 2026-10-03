package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.voxellight.VoxelLightClient;
import com.voxellight.world.SectionKey;
import com.voxellight.world.WorldSceneBridge;
import com.voxellight.world.CasterGeometryBudget;
import com.voxellight.world.ShadowVolume;
import com.voxellight.world.ShadowLight;
import com.voxellight.world.ShadowAnchor;
import com.voxellight.world.ShadowCascades;
import com.voxellight.world.CasterResidency;
import com.voxellight.world.ShadowMapCache;
import com.voxellight.world.CasterBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** Independent local terrain casters. No main-camera culling and no changes to vanilla's mesh/compile queues. */
public final class ShadowRenderer implements AutoCloseable {
    private static final int SETTINGS_BYTES = ShadowVolume.SETTINGS_BYTES;
    private static final RenderPipeline CASTER = casterPipeline();
    private static final RenderPipeline ENTITY = entityPipeline();
    private static final RenderPipeline COMPOSITE = compositePipeline("shadow");
    private static final RenderPipeline MASK = compositePipeline("shadow_mask");
    private static final RenderPipeline MAP = compositePipeline("shadow_map");

    private record Layer(GpuBuffer vertices, int indices, boolean cutout) { }
    private record Mesh(long geometryVersion, List<Layer> layers, long bytes, CasterBounds bounds, CasterBounds cutoutBounds) {
        void close() { layers.forEach(layer -> layer.vertices().close()); }
    }

    private record Submission(ShadowMapCache.Rect footprint, List<RenderPass.Draw<GpuBufferSlice[]>> draws) { }

    private final LinkedHashMap<SectionKey, Mesh> meshes = new LinkedHashMap<>();
    private static final class Cascade implements AutoCloseable {
        final int index;
        final ShadowMapCache cache;
        GpuTexture depth, attachment, dynamicDepth;
        GpuTextureView dynamicView;
        boolean dynamicInitialized, dynamicHadModels;
        GpuTextureView depthView, attachmentView;
        GpuBuffer settings;
        Cascade(int index) { this.index = index; cache = new ShadowMapCache(index); }
        @Override public void close() {
            cache.clear();
            if (dynamicView != null) { dynamicView.close(); dynamicView = null; }
            if (dynamicDepth != null) { dynamicDepth.close(); dynamicDepth = null; }
            dynamicInitialized = dynamicHadModels = false;
            if (depthView != null) { depthView.close(); depthView = null; }
            if (depth != null) { depth.close(); depth = null; }
            if (attachmentView != null) { attachmentView.close(); attachmentView = null; }
            if (attachment != null) { attachment.close(); attachment = null; }
            if (settings != null) { settings.close(); settings = null; }
        }
    }
    private final Cascade[] cascades = {new Cascade(0), new Cascade(1), new Cascade(2)};
    private record Deferred(WorldSceneBridge.GeometryToken token, long bytes) { }
    private final LinkedHashMap<SectionKey, Deferred> deferred = new LinkedHashMap<>();
    private long budgetEvictions;
    private GpuBuffer resolveSettings;
    private final ShadowAnchor anchor = new ShadowAnchor();
    private final ArtificialLights artificial = new ArtificialLights();
    private final DynamicCasterSystem dynamic = new DynamicCasterSystem();
    private final NativeShadowCasters nativeCasters = new NativeShadowCasters();
    private boolean cacheEnabled = true;
    private boolean worldSun = true;
    private int receiverDistance = (int)ShadowCascades.RADIUS;
    private long geometryRevision;
    long geometryRevision() { return geometryRevision; }
    private ShadowLight frameLight = ShadowLight.none();
    private SectionBufferBuilderPack builders;
    private long geometryBytes;
    private long buildNanos;
    private long uploadBytes;
    private long worldGeneration, resourceGeneration;
    private long replacementBatches, replacementFallbacks, peakBuildNanos;
    private int lastReplacementSections;
    private String replacementState = "none";
    private int expected;
    private int drawCalls;
    private String state = "off";

    /** Render current casters while missing sections rebuild; missing geometry cannot invalidate known occlusion. */
    public boolean prepare() {
        var minecraft = Minecraft.getInstance();
        var scene = VoxelLightClient.scene();
        uploadBytes = 0;
        drawCalls = 0;
        if (!scene.isEnabled()) {
            close();
            state = "scene tracking off; vanilla rendering retained";
            return false;
        }
        if (minecraft.level == null) {
            close();
            state = "no world; vanilla rendering retained";
            return false;
        }
        if (!minecraft.isSameThread()) throw new IllegalStateException("Caster extraction requires the client thread");

        var sky = minecraft.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        frameLight = worldSun
                ? sky.skybox == net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD
                    ? ShadowLight.world(sky.sunAngle, sky.moonAngle, sky.rainBrightness, sky.moonPhase.index()) : ShadowLight.none()
                : ShadowLight.fixed();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
        scene.updateCasterVolume(camera, frameLight);
        var bridge = scene.bridge();
        var sampledLight = frameLight;
        var stats = bridge.stats();
        if (worldGeneration != 0 && (worldGeneration != stats.worldGeneration() || resourceGeneration != stats.resourceGeneration())) close();
        frameLight = sampledLight;
        worldGeneration = stats.worldGeneration();
        resourceGeneration = stats.resourceGeneration();
        var keys = bridge.keys();
        expected = keys.size();
        var center = SectionKey.fromBlock((int)Math.floor(camera.x()), (int)Math.floor(camera.y()), (int)Math.floor(camera.z()));
        artificial.prepare(bridge, center);
        deferred.entrySet().removeIf(entry -> !bridge.isCurrent(entry.getValue().token()));
        var edited = new ArrayList<WorldSceneBridge.GeometryToken>();
        // Unloaded geometry is never eligible for replacement/reuse. Keep edited old buffers only inside this prepare call.
        for (var iterator = meshes.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            var token = bridge.geometryToken(entry.getKey());
            if (token == null) {
                retire(entry.getValue());
                iterator.remove();
            } else if (entry.getValue().geometryVersion() != token.version()) edited.add(token);
        }
        edited.sort(Comparator.comparing(WorldSceneBridge.GeometryToken::key, CasterResidency.priority(center)));
        boolean attemptedReplacement = replaceEdited(minecraft, bridge, edited);
        // Failed/large/unavailable batches cannot leave an obsolete caster visible this frame.
        for (var iterator = meshes.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (entry.getValue().geometryVersion() != bridge.geometryVersion(entry.getKey())) {
                retire(entry.getValue());
                iterator.remove();
            }
        }
        if (!attemptedReplacement) {
            var pending = new ArrayList<>(keys);
            pending.sort(CasterResidency.priority(center));
            // Initial streaming remains one section per frame, independently of CPU palette encode readiness.
            for (var key : pending) {
                if (meshes.containsKey(key) || !canBuild(minecraft, key)) continue;
                var token = bridge.geometryToken(key);
                if (token == null) continue;
                var blocked = deferred.get(key);
                if (blocked != null) {
                    if (blocked.bytes() > CasterGeometryBudget.SECTION_BYTES) continue;
                    makeRoom(key, center, blocked.bytes(), bridge);
                    if (geometryBytes + blocked.bytes() > CasterGeometryBudget.RESIDENT_BYTES) continue;
                }
                long start = System.nanoTime();
                try {
                    Mesh mesh = build(minecraft, token, 0, 0);
                    uploadBytes += mesh.bytes();
                    if (!bridge.isCurrent(token)) { mesh.close(); break; }
                    meshes.put(key, mesh);geometryRevision++;
                    deferred.remove(key);
                    invalidate(mesh.bounds());
                    geometryBytes += mesh.bytes();
                } catch (CasterGeometryBudget.Exceeded e) {
                    // Failed extraction has not allocated new GPU vertices. Keep valid near casters active.
                    deferred.put(key, new Deferred(token, e.nextBytes()));
                    if (e.nextBytes() <= CasterGeometryBudget.SECTION_BYTES) makeRoom(key, center, e.nextBytes(), bridge);
                } finally { recordBuildTime(start); }
                break;
            }
        }
        if (nativeCasters.prepare(minecraft, camera, frameLight, receiverDistance, meshes.keySet(), this::invalidate)) geometryRevision++;
        if (meshes.isEmpty() && nativeCasters.layers().isEmpty()) {
            state = "warming casters " + meshes.size() + "/" + expected + "; vanilla rendering retained";
            return false;
        }
        ensureResources();
        dynamic.prepare();
        state = meshes.size() == expected ? "terrain lighting active; " + (frameLight.strength() == 0 ? "local lights only" : worldSun ? "world sun/moon" : "fixed light")
                : "partial coverage; " + (deferred.isEmpty() ? "rebuilding " : "geometry budget limits ") + (expected - meshes.size()) + " caster sections";
        return true;
    }

    public void render(CommandEncoder encoder, RenderTarget target, GpuTextureView sceneColor, RenderProbe.Mode mode) {
        updateLighting(encoder, mode);
        try (var pass = encoder.createRenderPass(() -> "VoxelLight cascade lighting resolve", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(mode == RenderProbe.Mode.SHADOW_MAP ? MAP : mode == RenderProbe.Mode.SHADOW_MASK ? MASK : COMPOSITE);
            pass.bindTexture("SceneColor", sceneColor, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.bindTexture("SceneDepth", target.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            bindLighting(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private final Matrix4f inverseProjection = new Matrix4f();
    void captureProjection(Matrix4f projection) { inverseProjection.set(projection).invert(); }

    void updateLighting(CommandEncoder encoder, RenderProbe.Mode mode) {
        var minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var light = frameLight.direction();
        var key = anchor.update(camera.pos.x(), camera.pos.y(), camera.pos.z());
        dynamic.upload(encoder);
        artificial.upload(encoder, camera.pos.x(), camera.pos.y(), camera.pos.z(), frameLight, dynamic.hasModels());
        var viewToWorld = new Matrix4f(camera.viewRotationMatrix).invert();
        var matrices = new Matrix4f[ShadowCascades.COUNT];
        for(int i=0;i<matrices.length;i++) matrices[i]=ShadowCascades.anchored(camera.pos.x(),camera.pos.y(),camera.pos.z(),key,frameLight,i);
        try (var stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, ShadowCascades.RESOLVE_BYTES);
            for (int i = 0; i < ShadowCascades.COUNT; i++) data.putMat4f(matrices[i]);
            data.putMat4f(viewToWorld)
                    .putVec4(light.x, light.y, light.z, mode == RenderProbe.Mode.SHADOW_RANGES ? 2 : mode == RenderProbe.Mode.SHADOW_MASK ? 1 : 0)
                    .putVec4(receiverDistance - 8, receiverDistance, frameLight.strength(), worldSun ? 1 : 0)
                    .putVec4(ShadowCascades.range(0).blendStart(), ShadowCascades.range(0).blendEnd(),
                            ShadowCascades.range(1).blendStart(), ShadowCascades.range(1).blendEnd());
            data.putMat4f(inverseProjection);
            for (var matrix : matrices) data.putMat4f(ShadowCascades.normalMatrix(matrix));
            encoder.writeToBuffer(resolveSettings.slice(), data.get());
        }
        boolean updateMap = frameLight.strength() > 0 || mode == RenderProbe.Mode.SHADOW_MAP;
        for (var cascade : cascades) {
            if (!updateMap) { cascade.cache.suspend(); clearEntities(encoder, cascade); continue; }
            var matrix = matrices[cascade.index];
            try (var stack = MemoryStack.stackPush()) {
                var data = Std140Builder.onStack(stack, SETTINGS_BYTES).putMat4f(matrix).putMat4f(viewToWorld)
                        .putVec4(light.x, light.y, light.z, 0).putVec4(0, 0, 0, 0);
                encoder.writeToBuffer(cascade.settings.slice(), data.get());
            }
            renderCascade(encoder, minecraft, key, cascade);
            renderEntities(encoder, cascade);
        }
    }

    void bindLighting(RenderPass pass) {
        pass.bindTexture("ShadowMap", cascades[0].depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("MiddleShadowMap", cascades[1].depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("FarShadowMap", cascades[2].depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("EntityShadowMap", cascades[0].dynamicView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("MiddleEntityShadowMap", cascades[1].dynamicView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("FarEntityShadowMap", cascades[2].dynamicView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        artificial.bind(pass);
        pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
        pass.setUniform("ShadowResolveSettings", resolveSettings);
    }

    void bindVolumetric(RenderPass pass) {
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        String[] terrain = {"ShadowMap", "MiddleShadowMap", "FarShadowMap"};
        String[] animated = {"EntityShadowMap", "MiddleEntityShadowMap", "FarEntityShadowMap"};
        for (int i = 0; i < cascades.length; i++) {
            pass.bindTexture(terrain[i], cascades[i].depthView, nearest);
            pass.bindTexture(animated[i], cascades[i].dynamicView, nearest);
        }
        pass.setUniform("ShadowResolveSettings", resolveSettings);
    }

    void bindTransform(RenderPass pass) {
        pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
        pass.setUniform("ShadowResolveSettings", resolveSettings);
    }

    ShadowLight light() { return frameLight; }

    private void clearEntities(CommandEncoder encoder, Cascade cascade) {
        if (!cascade.dynamicInitialized || cascade.dynamicHadModels) {
            try (var pass = encoder.createRenderPass(() -> "VoxelLight empty entity shadow layer", cascade.attachmentView,
                    Optional.empty(), cascade.dynamicView, OptionalDouble.of(1))) { }
            cascade.dynamicInitialized = true; cascade.dynamicHadModels = false;
        }
    }

    private void renderEntities(CommandEncoder encoder, Cascade cascade) {
        if (!dynamic.hasModels()) { clearEntities(encoder, cascade); return; }
        dynamic.prepareIndices();
        try (var pass = encoder.createRenderPass(() -> "VoxelLight animated dynamic caster shadow layer", cascade.attachmentView,
                Optional.empty(), cascade.dynamicView, OptionalDouble.of(1))) {
            pass.setPipeline(ENTITY);
            pass.setUniform("ShadowSettings", cascade.settings);
            dynamic.draw(pass);
        }
        cascade.dynamicInitialized = true; cascade.dynamicHadModels = true;
    }

    private void renderCascade(CommandEncoder encoder, Minecraft minecraft, ShadowMapCache.Anchor key, Cascade cascade) {
        var update = cascade.cache.plan(key, frameLight, cacheEnabled, java.util.stream.Stream.concat(meshes.values().stream().map(Mesh::cutoutBounds).filter(java.util.Objects::nonNull),
                nativeCasters.layers().stream().filter(NativeShadowCasters.Layer::cutout).map(NativeShadowCasters.Layer::bounds)).toList());
        if (!update.regions().isEmpty()) {
            var atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
            var infos = new ArrayList<DynamicUniforms.ChunkSectionInfo>();
            var submissions = new ArrayList<Submission>();
            int maxIndices = 0;
            for (var entry : meshes.entrySet()) {
                var section = entry.getKey();
                var footprint = entry.getValue().bounds() == null ? null : cascade.cache.project(entry.getValue().bounds());
                if (footprint == null) continue;
                var draws = new ArrayList<RenderPass.Draw<GpuBufferSlice[]>>();
                for (var layer : entry.getValue().layers()) {
                    int uboIndex = infos.size();
                    // This private caster UBO uses ChunkVisibility as an alpha-cutout flag, not vanilla visibility.
                    infos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(), section.x() * 16, section.y() * 16, section.z() * 16,
                            layer.cutout() ? 1 : 0, atlas.getWidth(0), atlas.getHeight(0)));
                    maxIndices = Math.max(maxIndices, layer.indices());
                    draws.add(new RenderPass.Draw<>(0, layer.vertices(), null, null, 0, layer.indices(), 0,
                            (ubos, uploader) -> uploader.upload("ChunkSection", ubos[uboIndex])));
                }
                submissions.add(new Submission(footprint, draws));
            }
            for (var layer : nativeCasters.layers()) {
                var footprint = cascade.cache.project(layer.bounds());
                if (footprint == null) continue;
                var section = layer.key();
                int uboIndex = infos.size();
                infos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(), section.x() * 16, section.y() * 16, section.z() * 16,
                        layer.cutout() ? 1 : 0, atlas.getWidth(0), atlas.getHeight(0)));
                var buffers = layer.buffers();
                var draw = layer.draw();
                boolean custom = draw.hasCustomIndexBuffer();
                int firstIndex = custom ? Math.toIntExact(buffers.indexBufferOffset() / draw.indexType().bytes) : 0;
                int baseVertex = Math.toIntExact(buffers.vertexBufferOffset() / DefaultVertexFormat.BLOCK.getVertexSize());
                if (!custom) maxIndices = Math.max(maxIndices, draw.indexCount());
                var borrowed = new RenderPass.Draw<GpuBufferSlice[]>(0, buffers.vertexBuffer(), custom ? buffers.indexBuffer() : null,
                        custom ? draw.indexType() : null, firstIndex, draw.indexCount(), baseVertex,
                        (ubos, uploader) -> uploader.upload("ChunkSection", ubos[uboIndex]));
                submissions.add(new Submission(footprint, List.of(borrowed)));
            }
            var ubos = RenderSystem.getDynamicUniforms().writeChunkSections(infos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
            var autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            var indexBuffer = maxIndices == 0 ? null : autoIndices.getBuffer(maxIndices);
            for (var region : update.regions()) {
                var draws = new ArrayList<RenderPass.Draw<GpuBufferSlice[]>>();
                for (var submission : submissions) {
                    if (submission.footprint().intersects(region)) draws.addAll(submission.draws());
                }
                // Vulkan's viewport remains the full attachment; renderArea restricts both clear and raster scissor.
                var descriptor = RenderPassDescriptor.create(() -> "VoxelLight shadow tile update")
                        .withColorAttachment(cascade.attachmentView)
                        .withDepthAttachment(cascade.depthView, OptionalDouble.of(1))
                        .withRenderArea(new RenderPass.RenderArea(region.x(), region.y(), region.width(), region.height()));
                try (var pass = encoder.createRenderPass(descriptor)) {
                    pass.setPipeline(CASTER);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("ShadowSettings", cascade.settings);
                    pass.bindTexture("Sampler0", atlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    if (!draws.isEmpty()) pass.drawMultipleIndexed(draws, indexBuffer, autoIndices.type(), List.of("ChunkSection"), ubos);
                }
                drawCalls += draws.size();
                cascade.cache.rendered(region);
            }
        }
        cascade.cache.finishFrame(update);
    }

    public String status() {
        long renders = 0, reuses = 0, pages = 0, pageReuse = 0;
        int updated = 0, total = 0, regions = 0;
        var reasons = new ArrayList<String>();
        for (var c : cascades) {
            renders += c.cache.renders(); reuses += c.cache.reuses(); pages += c.cache.pageUpdates(); pageReuse += c.cache.pageReuses();
            updated += c.cache.updatedPages(); total += c.cache.tileCount(); regions += c.cache.regionCount(); reasons.add(c.cache.reason());
        }
        return "shadow=" + state + ", casters=" + meshes.size() + "/" + expected + ", casterDraws=" + drawCalls
                + ", geometryBytes=" + geometryBytes + ", shadowMapBytes=" + (resolveSettings == null ? 0 : ShadowCascades.mapBytes() + 24L * 1024 * 1024)
                + nativeCasters.status()
                + ", cascades=3, shadowDistance=" + receiverDistance
                + ", sun=" + (worldSun ? "world" : "fixed") + ", lightSource=" + frameLight.source()
                + ", lightAngleDeg=" + (float)Math.toDegrees(frameLight.angleRadians()) + ", lightStrength=" + frameLight.strength()
                + ", shadowCache=" + (cacheEnabled ? "on" : "off") + ", mapRenders=" + renders + ", mapReuses=" + reuses
                + ", updatedPages=" + updated + "/" + total + ", pageUpdates=" + pages + ", pageReuses=" + pageReuse
                + ", updateRegions=" + regions + ", mapReason=" + String.join(" | ", reasons)
                + ", mapSizes=2048/1024/1024, budgetDeferred=" + deferred.size() + ", budgetEvictions=" + budgetEvictions
                + ", lastBuildNs=" + buildNanos + ", peakBuildNs=" + peakBuildNanos + ", uploadBytes=" + uploadBytes
                + ", replacementBatches=" + replacementBatches + ", lastReplacementSections=" + lastReplacementSections
                + ", replacementFallbacks=" + replacementFallbacks + ", replacement=" + replacementState + artificial.status() + dynamic.status();
    }

    public void setShadowDistance(int blocks) {
        if (blocks < 12 || blocks > ShadowCascades.RADIUS) throw new IllegalArgumentException("Shadow distance must be 12..128 blocks");
        receiverDistance = blocks;
    }
    public void setEntityShadows(boolean value) { dynamic.setEntitiesEnabled(value); }
    public void setBlockEntityShadows(boolean value) { dynamic.setBlocksEnabled(value); }
    public void setFineShapes(boolean value) { artificial.setFineShapes(value); }
    public void setHeldLights(boolean value){artificial.setHeldEnabled(value);}
    public void setLocalLights(boolean enabled) { artificial.setEnabled(enabled); }
    public void setWorldSun(boolean enabled) { worldSun = enabled; for (var c : cascades) c.cache.clear(); }
    public void setCacheEnabled(boolean enabled) { cacheEnabled = enabled; for (var c : cascades) c.cache.clear(); }

    private void invalidate(CasterBounds bounds) { for (var c : cascades) c.cache.invalidate(bounds); }

    private void makeRoom(SectionKey key, SectionKey center, long bytes, WorldSceneBridge bridge) {
        var sizes = new LinkedHashMap<SectionKey, Long>();
        meshes.forEach((k, mesh) -> sizes.put(k, mesh.bytes()));
        for (var victim : CasterResidency.evictions(sizes, key, center, geometryBytes, bytes, CasterGeometryBudget.RESIDENT_BYTES)) {
            var old = meshes.remove(victim);
            var token = bridge.geometryToken(victim);
            if (token != null) deferred.put(victim, new Deferred(token, old.bytes()));
            retire(old); budgetEvictions++;
        }
    }

    private void retire(Mesh mesh) {
        geometryRevision++;
        invalidate(mesh.bounds());
        geometryBytes -= mesh.bytes();
        mesh.close();
    }

    private void recordBuildTime(long start) {
        buildNanos = System.nanoTime() - start;
        peakBuildNanos = Math.max(peakBuildNanos, buildNanos);
    }

    private boolean canBuild(Minecraft minecraft, SectionKey key) {
        var chunk = minecraft.level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
        if (chunk == null) return false;
        if (chunk.getSection(minecraft.level.getSectionIndexFromSectionY(key.y())).hasOnlyAir()) return true;
        for (int x = key.x() - 1; x <= key.x() + 1; x++) {
            for (int z = key.z() - 1; z <= key.z() + 1; z++) {
                if (minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) return false;
            }
        }
        return true;
    }

    /** Stage a complete small edit group, verify every token, then swap before any shadow draw. */
    private boolean replaceEdited(Minecraft minecraft, WorldSceneBridge bridge, List<WorldSceneBridge.GeometryToken> edited) {
        if (edited.isEmpty()) return false;
        lastReplacementSections = 0;
        if (edited.size() > CasterGeometryBudget.REPLACEMENT_SECTIONS) {
            replacementState = "fallback: more than 8 edited sections";
            replacementFallbacks++;
            return false;
        }
        if (edited.stream().anyMatch(token -> !canBuild(minecraft, token.key()))) {
            replacementState = "fallback: unloaded model neighbors";
            replacementFallbacks++;
            return false;
        }
        long retiring = edited.stream().mapToLong(token -> meshes.get(token.key()).bytes()).sum();
        long stagedBytes = 0;
        var staged = new LinkedHashMap<SectionKey, Mesh>();
        long start = System.nanoTime();
        try {
            for (var token : edited) {
                var mesh = build(minecraft, token, retiring, stagedBytes);
                staged.put(token.key(), mesh);
                stagedBytes += mesh.bytes();
                uploadBytes += mesh.bytes();
            }
            if (edited.stream().anyMatch(token -> !bridge.isCurrent(token))) {
                replacementState = "fallback: superseded edit";
                replacementFallbacks++;
                return true;
            }
            for (var iterator = staged.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                var key = entry.getKey();
                var mesh = entry.getValue();
                iterator.remove(); // Ownership transfers to meshes; finally closes only unpublished buffers.
                var old = meshes.put(key, mesh);geometryRevision++;
                invalidate(old.bounds());
                invalidate(mesh.bounds());
                geometryBytes += mesh.bytes() - old.bytes();
                old.close();
            }
            replacementBatches++;
            lastReplacementSections = edited.size();
            replacementState = "same-frame edit replacement";
            return true;
        } catch (CasterGeometryBudget.Exceeded e) {
            replacementState = "fallback: replacement geometry budget";
            replacementFallbacks++;
            return true;
        } finally {
            staged.values().forEach(Mesh::close);
            recordBuildTime(start);
        }
    }

    private Mesh build(Minecraft minecraft, WorldSceneBridge.GeometryToken token, long retiringBytes, long stagedBytes) {
        var key = token.key();
        var chunk = minecraft.level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
        if (chunk == null) throw new IllegalStateException("Caster chunk disappeared during client-thread extraction");
        if (chunk.getSection(minecraft.level.getSectionIndexFromSectionY(key.y())).hasOnlyAir()) {
            return new Mesh(token.version(), List.of(), 0, null, null);
        }
        if (builders == null) builders = new SectionBufferBuilderPack();
        var models = minecraft.getModelManager();
        var compiler = new SectionCompiler(false, true, models.getBlockStateModelSet(), models.getFluidStateModelSet(), minecraft.getBlockColors());
        var region = new RenderRegionCache().createRegion(minecraft.level, SectionPos.asLong(key.x(), key.y(), key.z()));
        var layers = new ArrayList<Layer>();
        SectionCompiler.Results results = null;
        try {
            results = compiler.compile(SectionPos.of(key.x(), key.y(), key.z()), region, VertexSorting.byDistance(8, 8, 8), builders);
            long bytes = 0;
            for (var layer : List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)) {
                var mesh = results.renderedLayers.get(layer);
                if (mesh != null) bytes += mesh.vertexBuffer().remaining();
            }
            CasterGeometryBudget.check(geometryBytes, retiringBytes, stagedBytes, bytes);
            CasterBounds bounds = null, cutoutBounds = null;
            for (var layer : List.of(ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT)) {
                var mesh = results.renderedLayers.get(layer);
                if (mesh != null) {
                    var layerBounds = CasterBounds.fromVertices(key, mesh.vertexBuffer(), DefaultVertexFormat.BLOCK.getVertexSize());
                    bounds = bounds == null ? layerBounds : bounds.union(layerBounds);
                    if (layer == ChunkSectionLayer.CUTOUT) cutoutBounds = layerBounds;
                    var vertices = RenderSystem.getDevice().createBuffer(() -> "VoxelLight caster vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                    layers.add(new Layer(vertices, mesh.drawState().indexCount(), layer == ChunkSectionLayer.CUTOUT));
                }
            }
            return new Mesh(token.version(), List.copyOf(layers), bytes, bounds, cutoutBounds);
        } catch (RuntimeException e) {
            layers.forEach(layer -> layer.vertices().close());
            throw e;
        } finally {
            if (results != null) results.release();
            builders.clearAll();
        }
    }

    private void ensureResources() {
        if (resolveSettings != null) return;
        var device = RenderSystem.getDevice();
        for (var pipeline : List.of(CASTER, ENTITY, COMPOSITE, MASK, MAP)) {
            if (!device.precompilePipeline(pipeline, RenderProbe.SHADERS).isValid()) throw new IllegalStateException("Cascade shadow shader compilation failed");
        }
        for (var c : cascades) {
            int size = ShadowCascades.range(c.index).mapSize();
            c.depth = device.createTexture("VoxelLight cascade " + c.index + " depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.D32_FLOAT, size, size, 1, 1);
            c.depthView = device.createTextureView(c.depth);
            c.dynamicDepth = device.createTexture("VoxelLight cascade " + c.index + " entity depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.D32_FLOAT, size, size, 1, 1);
            c.dynamicView = device.createTextureView(c.dynamicDepth);
            c.attachment = device.createTexture("VoxelLight cascade " + c.index + " attachment", GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.R8_UNORM, size, size, 1, 1);
            c.attachmentView = device.createTextureView(c.attachment);
            c.settings = device.createBuffer(() -> "VoxelLight cascade caster settings", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SETTINGS_BYTES);
        }
        resolveSettings = device.createBuffer(() -> "VoxelLight cascade resolve settings", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, ShadowCascades.RESOLVE_BYTES);
    }

    @Override
    public void close() {
        geometryRevision++;
        meshes.values().forEach(Mesh::close);
        meshes.clear();
        nativeCasters.clear();
        for (var c : cascades) c.close();
        deferred.clear(); budgetEvictions = 0;
        anchor.clear();
        artificial.close();
        dynamic.close();
        frameLight = ShadowLight.none();
        geometryBytes = 0;
        worldGeneration = 0; resourceGeneration = 0;
        replacementBatches = 0; replacementFallbacks = 0; peakBuildNanos = 0;
        lastReplacementSections = 0; replacementState = "none";
        expected = 0;
        drawCalls = 0;
        buildNanos = 0;
        uploadBytes = 0;
        if (resolveSettings != null) { resolveSettings.close(); resolveSettings = null; }
    }

    private static RenderPipeline casterPipeline() {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/shadow_caster"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "shadow_caster"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", "shadow_caster"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Globals", UniformType.UNIFORM_BUFFER)
                        .withUniform("ChunkSection", UniformType.UNIFORM_BUFFER).withUniform("ShadowSettings", UniformType.UNIFORM_BUFFER)
                        .withSampler("Sampler0").build())
                .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withCull(false)
                .build();
    }

    private static RenderPipeline entityPipeline() {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/shadow_entity"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "shadow_entity"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", "shadow_caster"))
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("ShadowSettings", UniformType.UNIFORM_BUFFER).withSampler("Sampler0").build())
                .withVertexBinding(0, DefaultVertexFormat.BLOCK).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)).withCull(false).build();
    }

    private static RenderPipeline compositePipeline(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("voxellight", "pipeline/" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight", "probe"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight", name.equals("shadow_map") ? name : "shadow"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneColor").withSampler("SceneDepth").withSampler("ShadowMap")
                        .withSampler("MiddleShadowMap").withSampler("FarShadowMap")
                        .withSampler("EntityShadowMap").withSampler("MiddleEntityShadowMap").withSampler("FarEntityShadowMap").withSampler("VoxelOpacity").withSampler("ShapeBounds")
                        .withUniform("LocalLightSettings", UniformType.UNIFORM_BUFFER)
                        .withUniform("Projection", UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings", UniformType.UNIFORM_BUFFER).build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(Optional.empty())
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }
}
