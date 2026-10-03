package com.voxellight.adapter;

import com.voxellight.world.BorrowedCasterVolume;
import com.voxellight.world.CasterBounds;
import com.voxellight.world.SectionKey;
import com.voxellight.world.ShadowLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Frame-local borrowed allocations from all compiled sections in light space, never camera visibility.
 * Does not compile, upload, close or persist native GPU allocations across prepares. */
final class NativeShadowCasters {
    record Layer(SectionKey key, CasterBounds bounds, boolean cutout,
                 SectionMesh.SectionDraw draw, SectionRenderDispatcher.RenderSectionBufferSlice buffers) { }
    private record State(SectionMesh mesh, int layers) { }
    private static final ChunkSectionLayer[] OPAQUE = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT};
    private Map<SectionKey, State> previous = Map.of();
    private final List<Layer> layers = new ArrayList<>();
    private int sections, pending;
    private long selectionNs;

    boolean prepare(Minecraft minecraft, Vec3 camera, ShadowLight light, int radius,
                    Set<SectionKey> independent, Consumer<CasterBounds> invalidate) {
        long start = System.nanoTime();
        layers.clear(); sections = pending = 0;
        var current = new HashMap<SectionKey, State>();
        var view = minecraft.levelRenderer.viewArea();
        var dispatcher = minecraft.levelRenderer.sectionRenderDispatcher();
        if (view != null && dispatcher != null && light.source() != ShadowLight.Source.NONE) {
            var direction = light.direction();
            double reach = radius + BorrowedCasterVolume.SECTION_RADIUS;
            int minX = (int)Math.floor((camera.x - reach + Math.min(0, direction.x * BorrowedCasterVolume.EXTRUSION)) / 16);
            int maxX = (int)Math.floor((camera.x + reach + Math.max(0, direction.x * BorrowedCasterVolume.EXTRUSION)) / 16);
            int minZ = (int)Math.floor((camera.z - reach + Math.min(0, direction.z * BorrowedCasterVolume.EXTRUSION)) / 16);
            int maxZ = (int)Math.floor((camera.z + reach + Math.max(0, direction.z * BorrowedCasterVolume.EXTRUSION)) / 16);
            int minY = Math.max(Math.max(view.minSectionY(), minecraft.level.getMinSectionY()), (int)Math.floor((camera.y - reach + Math.min(0, direction.y * BorrowedCasterVolume.EXTRUSION)) / 16));
            int maxY = Math.min(Math.min(view.maxSectionY(), minecraft.level.getMaxSectionY()), (int)Math.floor((camera.y + reach + Math.max(0, direction.y * BorrowedCasterVolume.EXTRUSION)) / 16));
            var pos = new BlockPos.MutableBlockPos();
            dispatcher.lock();
            try {
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                    // No chunk generation; unloaded/stale view-area slots cannot cast shadows.
                    var chunk = minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (int y = minY; y <= maxY; y++) {
                        if (chunk.getSection(minecraft.level.getSectionIndexFromSectionY(y)).hasOnlyAir()) continue;
                        if (!BorrowedCasterVolume.intersects(x * 16.0 + 8 - camera.x, y * 16.0 + 8 - camera.y,
                                z * 16.0 + 8 - camera.z, direction, radius)) continue;
                        var key = new SectionKey(x, y, z);
                        if (independent.contains(key)) continue;
                        pos.set(x * 16, y * 16, z * 16);
                        var section = view.getRenderSectionAt(pos);
                        if (section == null || !section.getRenderOrigin().equals(pos)) { pending++; continue; }
                        var mesh = section.getSectionMesh();
                        if (mesh == CompiledSectionMesh.UNCOMPILED) { pending++; continue; }
                        int ready = 0;
                        for (var layer : OPAQUE) {
                            var draw = mesh.getSectionDraw(layer);
                            if (draw == null || draw.indexCount() == 0) continue;
                            var buffers = dispatcher.getRenderSectionSlice(mesh, layer);
                            if (buffers == null || draw.hasCustomIndexBuffer() && buffers.indexBuffer() == null) { pending++; continue; }
                            layers.add(new Layer(key, bounds(key), layer == ChunkSectionLayer.CUTOUT, draw, buffers));
                            ready |= layer == ChunkSectionLayer.SOLID ? 1 : 2;
                        }
                        if (ready != 0) { current.put(key, new State(mesh, ready)); sections++; }
                    }
                }
            } finally { dispatcher.unlock(); }
        }
        boolean changed = false;
        for (var entry : previous.entrySet()) if (!entry.getValue().equals(current.get(entry.getKey()))) {
            invalidate.accept(bounds(entry.getKey())); changed = true;
        }
        for (var entry : current.entrySet()) if (!entry.getValue().equals(previous.get(entry.getKey()))) {
            invalidate.accept(bounds(entry.getKey())); changed = true;
        }
        previous = current;
        selectionNs = System.nanoTime() - start;
        return changed;
    }

    // Include a one-block model overhang; unusually large modded models remain a documented limit.
    private static CasterBounds bounds(SectionKey key) {
        return new CasterBounds(key.x() * 16.0 - 1, key.y() * 16.0 - 1, key.z() * 16.0 - 1,
                key.x() * 16.0 + 17, key.y() * 16.0 + 17, key.z() * 16.0 + 17);
    }
    List<Layer> layers() { return layers; }
    String status() { return ", nativeShadowSections=" + sections + ", nativeShadowPending=" + pending
            + ", nativeShadowLayers=" + layers.size() + ", nativeShadowDuplicateBytes=0, nativeShadowSelectionNs=" + selectionNs; }
    void clear() { previous = Map.of(); layers.clear(); sections = pending = 0; }
}
