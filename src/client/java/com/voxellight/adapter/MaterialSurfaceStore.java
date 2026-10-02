package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;

/** Owns bounded, unlit terrain meshes and token/residency state; owns no render targets. */
final class MaterialSurfaceStore implements AutoCloseable {
    record Mesh(WorldSceneBridge.GeometryToken token, GpuBuffer vertices, int indices, int bytes) {
        void close() { if (vertices != null) vertices.close(); }
    }
    private record Blocked(WorldSceneBridge.GeometryToken token, int bytes) { }
    private final LinkedHashMap<SectionKey, Mesh> meshes = new LinkedHashMap<>();
    private final Map<SectionKey, Blocked> blocked = new HashMap<>();
    private long geometryBytes, buildNs, uploadBytes;
    private int expected;
    void prepare(Minecraft minecraft, WorldSceneBridge bridge, SectionKey center) {
        var desired = bridge.keys().stream().filter(k -> Math.abs(k.x()-center.x())<=2 && Math.abs(k.y()-center.y())<=2 && Math.abs(k.z()-center.z())<=2)
                .sorted(CasterResidency.priority(center)).toList();
        var allowed = new HashSet<>(desired); expected = desired.size(); uploadBytes = 0; buildNs = 0;
        for (var it = meshes.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            if (!allowed.contains(e.getKey()) || !bridge.isCurrent(e.getValue().token())) { geometryBytes -= e.getValue().bytes(); e.getValue().close(); it.remove(); }
        }
        blocked.entrySet().removeIf(e -> !allowed.contains(e.getKey()) || !bridge.isCurrent(e.getValue().token()));
        for (var key : desired) {
            if (meshes.containsKey(key) || !loaded(minecraft, key)) continue;
            var token = bridge.geometryToken(key); if (token == null) continue;
            var deferred = blocked.get(key);
            if (deferred != null) {
                if (deferred.bytes() > MaterialEncoding.SECTION_LIMIT) continue;
                makeRoom(key,center,deferred.bytes(),bridge);
                if (geometryBytes + deferred.bytes() > MaterialEncoding.RESIDENT_LIMIT) continue;
            }
            long start = System.nanoTime();
            try {
                var mesh = build(minecraft, key, token);
                if (!bridge.isCurrent(token)) { mesh.close(); break; }
                meshes.put(key, mesh); blocked.remove(key); geometryBytes += mesh.bytes(); uploadBytes = mesh.bytes();
            } catch (BudgetExceeded e) { blocked.put(key, new Blocked(token, e.bytes)); if(e.bytes <= MaterialEncoding.SECTION_LIMIT) makeRoom(key,center,e.bytes,bridge); }
            finally { buildNs = System.nanoTime()-start; }
            break; // One material section per frame, with immediate stale-mesh retirement.
        }
    }
    private void makeRoom(SectionKey key,SectionKey center,int bytes,WorldSceneBridge bridge) {
        var sizes=new LinkedHashMap<SectionKey,Long>(); meshes.forEach((k,v)->sizes.put(k,(long)v.bytes()));
        for(var victim:CasterResidency.evictions(sizes,key,center,geometryBytes,bytes,MaterialEncoding.RESIDENT_LIMIT)) {
            var old=meshes.remove(victim);var token=bridge.geometryToken(victim);
            if(token!=null)blocked.put(victim,new Blocked(token,old.bytes()));
            geometryBytes-=old.bytes();old.close();
        }
    }
    private boolean loaded(Minecraft minecraft, SectionKey key) {
        for (int x=key.x()-1;x<=key.x()+1;x++) for (int z=key.z()-1;z<=key.z()+1;z++)
            if (minecraft.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)==null) return false;
        return true;
    }
    private static final class BudgetExceeded extends RuntimeException {
        final int bytes;
        BudgetExceeded(int bytes) { this.bytes = bytes; }
    }
    private Mesh build(Minecraft minecraft, SectionKey key, WorldSceneBridge.GeometryToken token) {
        var region = new RenderRegionCache().createRegion(minecraft.level, SectionPos.asLong(key.x(),key.y(),key.z()));
        if (region == null) return new Mesh(token,null,0,0);
        var renderer = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
        var models = minecraft.getModelManager().getBlockStateModelSet();
        try (var memory = new ByteBufferBuilder(4096, MaterialEncoding.SECTION_LIMIT)) {
            var builder = new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            for (int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                var pos = new BlockPos(key.x()*16+x,key.y()*16+y,key.z()*16+z);
                var state = region.getBlockState(pos);
                if (state.getRenderShape()!=RenderShape.MODEL) continue;
                renderer.tesselateBlock((qx,qy,qz,quad,instance) -> {
                    if (quad.materialInfo().layer()==ChunkSectionLayer.TRANSLUCENT) return;
                    var source = quad.materialInfo().tintIndex()<0 ? null : minecraft.getBlockColors().getTintSource(state,quad.materialInfo().tintIndex());
                    int tint = source==null ? -1 : source.colorInWorld(state,region,pos);
                    MaterialQuads.put(builder,qx,qy,qz,quad,instance,tint,Math.clamp(state.getLightEmission(),0,15));
                }, x,y,z,region,pos,state,models.get(state),state.getSeed(pos));
            }
            try (var mesh = builder.build()) {
                if (mesh==null) return new Mesh(token,null,0,0);
                int bytes = mesh.vertexBuffer().remaining();
                if (geometryBytes+bytes > MaterialEncoding.RESIDENT_LIMIT) throw new BudgetExceeded(bytes);
                var vertices = RenderSystem.getDevice().createBuffer(() -> "VoxelLight unlit material section", GpuBuffer.USAGE_VERTEX,mesh.vertexBuffer());
                return new Mesh(token,vertices,mesh.drawState().indexCount(),bytes);
            }
        } catch (RuntimeException e) {
            if (capacityExceeded(e)) throw new BudgetExceeded(MaterialEncoding.SECTION_LIMIT+1);
            throw e;
        }
    }
    static boolean capacityExceeded(Throwable error) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof IllegalArgumentException && error.getMessage() != null
                    && error.getMessage().startsWith("Maximum capacity of ByteBufferBuilder")) return true;
        }
        return false;
    }
    Set<Map.Entry<SectionKey, Mesh>> entries() { return Collections.unmodifiableMap(meshes).entrySet(); }
    String status() {
        return ", materialSections=" + meshes.size() + "/" + expected + ", materialDeferred=" + blocked.size()
                + ", materialGeometryBytes=" + geometryBytes + ", materialBuildNs=" + buildNs + ", materialUploadBytes=" + uploadBytes;
    }
    @Override public void close() {
        meshes.values().forEach(Mesh::close); meshes.clear(); blocked.clear();
        geometryBytes = buildNs = uploadBytes = 0; expected = 0;
    }
}
