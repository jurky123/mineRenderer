package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.voxellight.world.LocalLightVolume;
import com.voxellight.world.LightMaterials;
import net.minecraft.world.item.BlockItem;
import net.minecraft.resources.Identifier;
import com.voxellight.world.OccluderShapes;
import net.minecraft.world.level.block.state.BlockState;
import com.voxellight.world.SectionKey;
import com.voxellight.world.ShadowLight;
import com.voxellight.world.WorldSceneBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.*;

/** Main-thread bounded emission/opacity extraction, independent of vanilla light-packet rebuilds. */
final class ArtificialLights implements AutoCloseable {
    private record Entry(WorldSceneBridge.GeometryToken token, LocalLightVolume.Section section) { }
    private final Map<SectionKey, Entry> sections = new LinkedHashMap<>();
    private final LocalLightVolume volume = new LocalLightVolume();
    private final OccluderShapes shapes = new OccluderShapes();
    private final Map<BlockState, Integer> stateShapes = new HashMap<>();
    private GpuTexture shapeTexture;
    private GpuTextureView shapeView;
    private int uploadedRows, uploadRegions;
    private long shapeUploadBytes;
    private boolean fineShapes = true;
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBuffer settings;
    private SectionKey center;
    private boolean dirty = true, enabled = true;
    private long worldGeneration, resourceGeneration, lastFrame;
    private long uploads, uploadBytes, extractionNanos, atlasNanos;
    private int active, tracked;
    private boolean heldEnabled=true;
    private int heldCount;
    private LightMaterials materials=LightMaterials.defaults();
    private String materialState="bundled";
    private record Held(double x,double y,double z,int emission,LightMaterials.Color color) { }
    private void loadMaterials() {
        materials=LightMaterials.defaults();materialState="bundled";
        try(var reader=Minecraft.getInstance().getResourceManager().getResource(Identifier.fromNamespaceAndPath("voxellight","light_materials.json")).orElseThrow().openAsReader()) {
            materials=LightMaterials.read(reader);materialState="resource pack";
        } catch(Exception e) {
            materialState="invalid resource; bundled fallback";
            org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("Invalid light materials; using bundled profiles",e);
        }
    }
    private Held heldLight() {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(!enabled || !heldEnabled || player==null || player.isSpectator() || !player.isAlive())return null;
        int emission=0;LightMaterials.Color color=LightMaterials.FALLBACK;
        for(var stack:List.of(player.getMainHandItem(),player.getOffhandItem())) {
            if(stack.getItem() instanceof BlockItem item) {
                int value=item.getBlock().defaultBlockState().getLightEmission();
                if(value>emission) {emission=value;color=materials.color(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());}
            }
        }
        if(emission==0)return null;
        var eye=player.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        // Virtual source near the body, not attached to the camera in third person.
        return new Held(eye.x,eye.y-.25,eye.z,emission,color);
    }

    float[] rtVirtualLight(){var held=heldLight();if(held==null)return new float[8];float intensity=held.emission()/15f*.7f;return new float[]{(float)held.x(),(float)held.y(),(float)held.z(),1,held.color().red()*intensity,held.color().green()*intensity,held.color().blue()*intensity,0};}
    void prepare(WorldSceneBridge bridge, SectionKey camera) {
        long start = System.nanoTime();
        var stats = bridge.stats();
        if (worldGeneration != stats.worldGeneration() || resourceGeneration != stats.resourceGeneration()) {close();loadMaterials();}
        worldGeneration = stats.worldGeneration(); resourceGeneration = stats.resourceGeneration();
        // Directional casters use a larger window; local voxel lights retain their 80^3/125-section cap.
        var keys = bridge.keys().stream().filter(key -> Math.abs(key.x() - camera.x()) <= 2
                && Math.abs(key.y() - camera.y()) <= 2 && Math.abs(key.z() - camera.z()) <= 2).toList();
        var desired = new HashSet<>(keys);
        tracked = keys.size();
        var edited = new ArrayList<SectionKey>();
        for (var it = sections.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (!desired.contains(entry.getKey()) || !bridge.isCurrent(entry.getValue().token())) {
                if (desired.contains(entry.getKey()) && bridge.geometryToken(entry.getKey()) != null) edited.add(entry.getKey());
                it.remove(); dirty = true;
            }
        }
        if (enabled) {
            var pending = new ArrayList<>(keys);
            pending.sort(Comparator.comparingInt(key -> Math.abs(key.x() - camera.x()) + Math.abs(key.y() - camera.y()) + Math.abs(key.z() - camera.z())));
            int limit = edited.isEmpty() ? 1 : 8;
            int copied = 0;
            // Resident edits first, then warm new cells. Large edit groups become unknown, never stale.
            var order = new LinkedHashSet<>(edited); order.addAll(pending);
            for (var key : order) {
                if (sections.containsKey(key)) continue;
                var token = bridge.geometryToken(key);
                if (token == null) continue;
                var section = extract(key);
                if (section == null || !bridge.isCurrent(token)) continue;
                sections.put(key, new Entry(token, section)); dirty = true;
                if (++copied >= limit) break;
            }
        }
        if (!camera.equals(center)) { center = camera; dirty = true; }
        extractionNanos = System.nanoTime() - start;
    }

    private LocalLightVolume.Section extract(SectionKey key) {
        var level = Minecraft.getInstance().level;
        var chunk = level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
        if (chunk == null) return null;
        var section = chunk.getSection(level.getSectionIndexFromSectionY(key.y()));
        var opacity = new int[4096];
        var clusters = new LocalLightVolume.Emitter[64];
        if (!section.hasOnlyAir()) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var state = section.getBlockState(x, y, z);
                opacity[SectionKey.blockIndex(x, y, z)] = shapeWord(state);
                int emission = state.getLightEmission();
                if (emission == 0) continue;
                int cell = (y >> 2) * 16 + (z >> 2) * 4 + (x >> 2);
                if (clusters[cell] != null && clusters[cell].emission() >= emission) continue;
                var color=materials.color(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                clusters[cell] = new LocalLightVolume.Emitter(key.x() * 16 + x, key.y() * 16 + y, key.z() * 16 + z, emission, color.red(),color.green(),color.blue());
            }
        }
        return new LocalLightVolume.Section(key, opacity, Arrays.stream(clusters).filter(Objects::nonNull).toList());
    }

    private int shapeWord(BlockState state) {
        if (state.isSolidRender()) return OccluderShapes.FULL;
        if (!state.canOcclude()) return OccluderShapes.EMPTY;
        var cached = stateShapes.get(state);
        if (cached != null) return cached;
        var boxes = new ArrayList<OccluderShapes.Box>();
        state.getOcclusionShape().forAllBoxes((x0, y0, z0, x1, y1, z1) ->
                boxes.add(new OccluderShapes.Box((float)x0, (float)y0, (float)z0, (float)x1, (float)y1, (float)z1)));
        int word = shapes.register(boxes);
        if (stateShapes.size() < 4096) stateShapes.put(state, word);
        return word;
    }

    void upload(CommandEncoder encoder, double cx, double cy, double cz, ShadowLight light, boolean entityModels) {
        if (texture == null) {
            var device = RenderSystem.getDevice();
            texture = device.createTexture("VoxelLight occluder shape atlas", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.R32_UINT, LocalLightVolume.WIDTH, LocalLightVolume.HEIGHT, 1, 1);
            view = device.createTextureView(texture);
            shapeTexture = device.createTexture("VoxelLight shape bounds", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA32_FLOAT, OccluderShapes.WIDTH, OccluderShapes.MAX_SHAPES, 1, 1);
            shapeView = device.createTextureView(shapeTexture);
            settings = device.createBuffer(() -> "VoxelLight local light settings", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, LocalLightVolume.SETTINGS_BYTES);
            dirty = true;
        }
        uploadBytes = 0; shapeUploadBytes = 0; uploadRegions = 0;
        if (uploadedRows < shapes.rowCount()) {
            int rows = shapes.rowCount() - uploadedRows;
            var data = MemoryUtil.memAlloc(rows * OccluderShapes.WIDTH * 16);
            try {
                shapes.writeRows(data, uploadedRows, shapes.rowCount()); data.flip();
                encoder.writeToTexture(shapeTexture, data, 0, 0, 0, uploadedRows, OccluderShapes.WIDTH, rows);
            } finally { MemoryUtil.memFree(data); }
            shapeUploadBytes = rows * OccluderShapes.WIDTH * 16L; uploadedRows = shapes.rowCount();
        }
        if (dirty) {
            long start = System.nanoTime();
            var regions = volume.rebuild(sections.values().stream().map(Entry::section).toList(), center);
            for (var region : regions) {
                var data = MemoryUtil.memAlloc(region.bytes());
                try {
                    volume.writeRegion(data, region); data.flip();
                    encoder.writeToTexture(texture, data, 0, 0, region.x(), region.y(), region.width(), region.height());
                } finally { MemoryUtil.memFree(data); }
                uploadBytes += region.bytes(); uploads++; uploadRegions++;
            }
            dirty = false; atlasNanos = System.nanoTime() - start;
        }
        long now = System.nanoTime();
        float seconds = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000_000f; lastFrame = now;
        var held=heldLight();
        var lights = enabled ? volume.select(cx, cy, cz, seconds,LocalLightVolume.MAX_LIGHTS-(held==null?0:1)) : List.<LocalLightVolume.Active>of();
        heldCount=held==null?0:1;active = lights.size()+heldCount;
        try (var stack = MemoryStack.stackPush()) {
            var data = Std140Builder.onStack(stack, LocalLightVolume.SETTINGS_BYTES)
                    .putVec4((float)(volume.originX() - cx), (float)(volume.originY() - cy), (float)(volume.originZ() - cz), active)
                    .putVec4(LocalLightVolume.SIZE, LocalLightVolume.SIZE, LocalLightVolume.SIZE, enabled ? 1 : 0)
                    .putVec4(light.source() == ShadowLight.Source.MOON ? light.strength() : 0, fineShapes ? 1 : 0, entityModels ? 1 : 0, held==null?0:lights.size()+1);
            for (int i = 0; i < LocalLightVolume.MAX_LIGHTS; i++) {
                if (i < lights.size()) {
                    var e = lights.get(i).emitter();
                    data.putVec4((float)(e.x() + 0.5 - cx), (float)(e.y() + 0.5 - cy), (float)(e.z() + 0.5 - cz), e.radius());
                } else if(held!=null && i==lights.size())data.putVec4((float)(held.x()-cx),(float)(held.y()-cy),(float)(held.z()-cz),Math.min(12,held.emission()));
                else data.putVec4(0, 0, 0, 0);
            }
            for (int i = 0; i < LocalLightVolume.MAX_LIGHTS; i++) {
                if (i < lights.size()) {
                    var a = lights.get(i); var e = a.emitter();
                    data.putVec4(e.red(), e.green(), e.blue(), e.emission() / 15f * a.weight());
                } else if(held!=null && i==lights.size())data.putVec4(held.color().red(),held.color().green(),held.color().blue(),held.emission()/15f);
                else data.putVec4(0, 0, 0, 0);
            }
            encoder.writeToBuffer(settings.slice(), data.get());
        }
    }

    void bind(RenderPass pass) {
        pass.setUniform("LocalLightSettings", settings);
        pass.bindTexture("ShapeBounds", shapeView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
        pass.bindTexture("VoxelOpacity", view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }
    void setHeldEnabled(boolean value){heldEnabled=value;}
    void setFineShapes(boolean value) { fineShapes = value; }
    void setEnabled(boolean value) { enabled = value; lastFrame = 0; }
    String status() { return ", localLights=" + (enabled ? "on" : "off") + ", activeLights=" + active + "/16, lightCandidates=" + volume.candidates()
            + ", heldLights="+(heldEnabled?"on":"off")+", activeHeld="+heldCount+", lightMaterials="+materials.size()+" ("+materialState+")"
            + ", lightSections=" + sections.size() + "/" + tracked + ", voxelBytes=" + (texture == null ? 0 : LocalLightVolume.ATLAS_BYTES + OccluderShapes.TEXTURE_BYTES)
            + ", lightOcclusion=" + (fineShapes ? "shapes" : "full") + ", shapeRows=" + shapes.rowCount() + ", shapeFallbacks=" + shapes.complexFallbacks() + ", shapeOverflows=" + shapes.paletteOverflows() + ", uploadRegions=" + uploadRegions + ", shapeUploadBytes=" + shapeUploadBytes + ", voxelUploads=" + uploads + ", voxelUploadBytes=" + uploadBytes + ", lightExtractNs=" + extractionNanos + ", lastVoxelPrepareNs=" + atlasNanos; }
    @Override public void close() {
        sections.clear(); volume.clear(); shapes.clear(); stateShapes.clear(); uploadedRows = 0; uploadRegions = 0; shapeUploadBytes = 0; center = null; dirty = true; worldGeneration = 0; resourceGeneration = 0; lastFrame = 0;
        active = 0; tracked = 0;heldCount=0; uploads = 0; uploadBytes = 0; extractionNanos = 0; atlasNanos = 0;
        if (shapeView != null) { shapeView.close(); shapeView = null; }
        if (shapeTexture != null) { shapeTexture.close(); shapeTexture = null; }
        if (view != null) { view.close(); view = null; }
        if (texture != null) { texture.close(); texture = null; }
        if (settings != null) { settings.close(); settings = null; }
    }
}
