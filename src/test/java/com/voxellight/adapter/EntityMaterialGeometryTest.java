package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityMaterialGeometryTest {
    private void quad(VertexConsumer target) {
        for(int vertex=0;vertex<4;vertex++)target.addVertex(vertex&1,vertex/2,2,0xff80ff40,.25f,.75f,0x00030002,0x00f00080,0,1,0);
    }
    @Test void actualNativeStreamPreservesPositionTintUvOverlayLightAndWorldNormalExactly() {
        try(var nativeScratch=new ByteBufferBuilder(4096,4096);var materialScratch=new ByteBufferBuilder(4096,4096)) {
            var nativeBuilder=new BufferBuilder(nativeScratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            var copy=new BufferBuilder(materialScratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            var tee=new MaterialVertexTee(nativeBuilder,copy);quad(tee);
            try(var actual=nativeBuilder.build();var captured=copy.build()) {
                assertNotNull(actual);assertNotNull(captured);
                // ENTITY has an unwritten padding byte at 35; compare all authored attributes.
                for(int vertex=0;vertex<4;vertex++)for(int offset=0;offset<35;offset++)
                    assertEquals(actual.vertexBuffer().get(vertex*36+offset),captured.vertexBuffer().get(vertex*36+offset));
                assertEquals(4,captured.drawState().vertexCount());
                int normal=DefaultVertexFormat.ENTITY.getElement("Normal").offset();
                assertEquals(127,captured.vertexBuffer().get(normal+1));
            }
            assertFalse(tee.failed());
        }
    }
    @Test void privateCapacityFailureLeavesEveryNativeVertexAndAttributeIntact() {
        try(var nativeScratch=new ByteBufferBuilder(4096,4096);var tiny=new ByteBufferBuilder(36,36)) {
            var actual=new BufferBuilder(nativeScratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            var tee=new MaterialVertexTee(actual,new BufferBuilder(tiny,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY));
            quad(tee);assertTrue(tee.failed());
            try(var mesh=actual.build()) {
                assertNotNull(mesh);assertEquals(4,mesh.drawState().vertexCount());assertEquals(4*36,mesh.vertexBuffer().remaining());
                assertEquals(2,mesh.vertexBuffer().getFloat(3*36+8));
                assertEquals(127,mesh.vertexBuffer().get(3*36+DefaultVertexFormat.ENTITY.getElement("Normal").offset()+1));
            }
        }
    }
    @Test void spriteWrappingIsAppliedToBothStreamsWithoutASecondModelEvaluation() {
        var id=net.minecraft.resources.Identifier.fromNamespaceAndPath("voxellight","test");
        var image=new com.mojang.blaze3d.platform.NativeImage(4,4,false);
        try(var contents=new net.minecraft.client.renderer.texture.SpriteContents(id,new net.minecraft.client.resources.metadata.animation.FrameSize(4,4),image);
            var nativeScratch=new ByteBufferBuilder(4096,4096);var privateScratch=new ByteBufferBuilder(4096,4096)) {
            var sprite=new net.minecraft.client.renderer.texture.TextureAtlasSprite(id,contents,16,16,4,8,0){};
            var actual=new BufferBuilder(nativeScratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            var captured=new BufferBuilder(privateScratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            quad(new MaterialVertexTee(sprite.wrap(actual),sprite.wrap(captured)));
            try(var a=actual.build();var b=captured.build()) {
                int uv=DefaultVertexFormat.ENTITY.getElement("UV0").offset();
                for(int vertex=0;vertex<4;vertex++) {
                    int offset=vertex*36+uv;
                    assertEquals(a.vertexBuffer().getFloat(offset),b.vertexBuffer().getFloat(offset));
                    assertEquals(a.vertexBuffer().getFloat(offset+4),b.vertexBuffer().getFloat(offset+4));
                    assertEquals(sprite.getU(.25f),b.vertexBuffer().getFloat(offset));
                    assertEquals(sprite.getV(.75f),b.vertexBuffer().getFloat(offset+4));
                }
            }
        }
    }
    @Test void unsupportedBlendedDecalAndEmissivePipelinesStayNative() {
        assertEquals(0,EntityMaterials.style(RenderPipelines.ENTITY_SOLID));
        assertEquals(1,EntityMaterials.style(RenderPipelines.ENTITY_CUTOUT));
        assertEquals(1,EntityMaterials.style(RenderPipelines.ENTITY_CUTOUT_CULL));
        assertEquals(2,EntityMaterials.style(RenderPipelines.ARMOR_CUTOUT_NO_CULL));
        assertEquals(3,EntityMaterials.style(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL), "Opaque trim is a native coverage exclusion, not a lighting material");
        assertEquals(-1,EntityMaterials.style(RenderPipelines.ENTITY_TRANSLUCENT));
        assertEquals(-1,EntityMaterials.style(RenderPipelines.ENTITY_CUTOUT_DISSOLVE));
    }
    @Test void privateFrameCapsAndNewFrameResetDoNotRetainOldAnimatedGeometry() {
        try(var materials=new EntityMaterials();var scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES)) {
            var builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            for(int i=0;i<1000;i++)quad(builder);
            try(var mesh=builder.build()) {
                int count=DynamicCasterSelection.FRAME_BYTES/(1000*4*36);
                for(int i=0;i<count;i++)assertTrue(materials.append(mesh,0,null));
                assertFalse(materials.append(mesh,0,null));assertEquals(count,materials.modelCount());
                assertTrue(materials.bytes()<=DynamicCasterSelection.FRAME_BYTES);
                materials.begin();assertEquals(0,materials.bytes());assertFalse(materials.hasModels());
            }
            scratch.clear();var small=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);quad(small);
            try(var mesh=small.build()) {
                for(int i=0;i<128;i++)assertTrue(materials.append(mesh,0,null));
                assertFalse(materials.append(mesh,0,null));
                assertFalse(materials.append(mesh,3,null));
                assertFalse(materials.hasModels(), "A dropped opaque trim mask must retain the entire native entity frame");
                materials.close();assertEquals(0,materials.modelCount());assertEquals(0,materials.bytes());
            }
        }
    }
}
