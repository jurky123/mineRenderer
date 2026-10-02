package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialQuadsTest {
    private BakedQuad slope() {
        return new BakedQuad(new Vector3f(0,0,0),new Vector3f(1,1,0),new Vector3f(1,1,1),new Vector3f(0,0,1),
                0,0,0,0,Direction.UP,new BakedQuad.MaterialInfo(null,ChunkSectionLayer.CUTOUT,null,0,true,5));
    }
    @Test void slopedFaceUsesItsGeometryRatherThanNominalCardinalDirection() {
        var n=MaterialQuads.normal(slope());
        assertEquals(-Math.sqrt(.5),n.x,1e-6);assertEquals(Math.sqrt(.5),n.y,1e-6);assertEquals(0,n.z,1e-6);
    }
    @Test void nativePackingPreservesUnlitTintAndIndependentEmissionAndNormal() {
        try(var scratch=new ByteBufferBuilder(4096,4096)) {
            var builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            var lighting=new QuadInstance();lighting.setColor(0,0xff010101);lighting.setColor(1,0xff010101);
            MaterialQuads.put(builder,2,3,4,slope(),lighting,0xff80ff40,14);
            try(var mesh=builder.build()) {
                assertNotNull(mesh);assertEquals(4,mesh.drawState().vertexCount());
                var format=DefaultVertexFormat.ENTITY;var data=mesh.vertexBuffer();
                int color=format.getElement("Color").offset(),metadata=format.getElement("UV1").offset(),normal=format.getElement("Normal").offset();
                assertEquals(128,Byte.toUnsignedInt(data.get(color)));assertEquals(255,Byte.toUnsignedInt(data.get(color+1)));
                assertEquals(64,Byte.toUnsignedInt(data.get(color+2)));assertEquals(14,data.getShort(metadata));
                assertEquals(5,data.getShort(metadata+2)&15);assertEquals(3,data.getShort(metadata+2)>>>4);
                assertTrue(data.get(normal)<0);assertTrue(data.get(normal+1)>0);
                assertEquals(2,data.getFloat(0));assertEquals(3,data.getFloat(4));assertEquals(4,data.getFloat(8));
            }
        }
    }
    @Test void nativeReportedBudgetFailureDoesNotDisableTheEntireDiagnostic() {
        var wrapped = new net.minecraft.ReportedException(new net.minecraft.CrashReport("Tessellation",
                new IllegalArgumentException("Maximum capacity of ByteBufferBuilder (1048576) exceeded")));
        assertTrue(MaterialSurfaceStore.capacityExceeded(wrapped));
        assertFalse(MaterialSurfaceStore.capacityExceeded(new IllegalArgumentException("Invalid block model")));
    }
}
