package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicModelBufferTest {
    private MeshData quads(ByteBufferBuilder scratch,int count) {
        var builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);
        for(int quad=0;quad<count;quad++)for(int vertex=0;vertex<4;vertex++)
            builder.addVertex(vertex&1,vertex/2,quad).setColor(-1).setUv(0,0).setUv2(0,0);
        return builder.build();
    }
    @Test void rendererFailureRollsBackOnlyItsPartialSubmissionsAndNewFramesClearOldModels() {
        try(var buffer=new DynamicModelBuffer("test");var scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES);
            var mesh=quads(scratch,1)) {
            buffer.begin();buffer.owner("entity",10,20,30);assertTrue(buffer.append(mesh,null));
            var checkpoint=buffer.checkpoint();
            assertTrue(buffer.append(mesh,null));assertTrue(buffer.append(mesh,null));
            buffer.rollback(checkpoint);buffer.finish();
            assertEquals(1,buffer.modelCount());assertEquals(4*28,buffer.bytes());
            assertTrue(buffer.append(mesh,null));buffer.finish();
            assertEquals(2,buffer.modelCount());assertEquals(8*28,buffer.bytes());
            assertSame(buffer.rtModels(),buffer.rtModels());assertEquals(1,buffer.rtModels().get(1).feature());assertEquals("entity",buffer.rtModels().get(1).owner());assertEquals(20,buffer.rtModels().get(1).y());assertFalse(buffer.rtModels().get(1).transientGeometry());
            buffer.begin();buffer.finish();assertTrue(buffer.rtModels().isEmpty());assertFalse(buffer.hasModels());assertEquals(0,buffer.bytes());
        }
    }
    @Test void frameAndModelCountCapsAreEnforcedByTheLiveAppendPath() {
        try(var buffer=new DynamicModelBuffer("test");var scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES);
            var mesh=quads(scratch,1000)) {
            buffer.begin();
            int admitted=DynamicCasterSelection.FRAME_BYTES/(1000*4*28);
            for(int i=0;i<admitted;i++)assertTrue(buffer.append(mesh,null));
            assertFalse(buffer.append(mesh,null));buffer.finish();
            assertEquals(admitted,buffer.modelCount());assertTrue(buffer.bytes()<=DynamicCasterSelection.FRAME_BYTES);
            assertEquals(1,buffer.skipped());
        }
        try(var buffer=new DynamicModelBuffer("test");var scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES);
            var mesh=quads(scratch,1)) {
            buffer.begin();
            for(int i=0;i<DynamicCasterSelection.MAX_MODELS;i++)assertTrue(buffer.append(mesh,null));
            assertFalse(buffer.append(mesh,null));assertEquals(DynamicCasterSelection.MAX_MODELS,buffer.modelCount());
        }
    }
}
