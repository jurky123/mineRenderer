package com.voxellight.adapter;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.voxellight.world.MaterialEncoding;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3f;

/** Consume native model geometry, but do not copy QuadInstance's baked lighting colors. */
final class MaterialQuads {
    private MaterialQuads() { }
    static Vector3f normal(BakedQuad quad) {
        var edge = new Vector3f(quad.position1()).sub(quad.position0());
        var n = edge.cross(new Vector3f(quad.position2()).sub(quad.position0()));
        if (n.lengthSquared() < 1e-12f) n = new Vector3f(quad.position2()).sub(quad.position0())
                .cross(new Vector3f(quad.position3()).sub(quad.position0()));
        if (n.lengthSquared() < 1e-12f) return new Vector3f(quad.direction().getUnitVec3f());
        n.normalize();
        if (n.dot(quad.direction().getUnitVec3f()) < 0) n.negate();
        return n;
    }
    static void put(BufferBuilder builder, float x, float y, float z, BakedQuad quad, QuadInstance lighting, int tint, int blockEmission) {
        var info = quad.materialInfo();
        int flags = (info.layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT ? MaterialEncoding.CUTOUT : 0)
                | (info.isTinted() ? MaterialEncoding.TINTED : 0) | (!info.shade() ? MaterialEncoding.UNSHADED : 0)
                | (info.sprite() != null && info.sprite().contents().isAnimated() ? MaterialEncoding.ANIMATED : 0);
        int metadata = MaterialEncoding.packQuadEmissionFlags(Math.clamp(info.lightEmission(), 0, 15), flags);
        var n = normal(quad);
        for (int i = 0; i < 4; i++) {
            var p = quad.position(i); long uv = quad.packedUV(i);
            // ENTITY's UV1 is private metadata here; preserve UV2 for future block/sky compatibility lighting.
            builder.addVertex(x+p.x(), y+p.y(), z+p.z()).setColor(tint).setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                    .setUv1(blockEmission, metadata).setUv2(lighting.getLightCoords(i) & 65535, lighting.getLightCoords(i) >>> 16)
                    .setNormal(n.x, n.y, n.z);
        }
    }
}
