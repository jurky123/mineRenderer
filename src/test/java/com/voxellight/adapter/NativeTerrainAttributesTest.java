package com.voxellight.adapter;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTerrainAttributesTest {
    private BakedQuad slope(){return new BakedQuad(new Vector3f(0,0,0),new Vector3f(1,1,0),new Vector3f(1,1,1),new Vector3f(0,0,1),
            0,0,0,0,Direction.UP,new BakedQuad.MaterialInfo(null,ChunkSectionLayer.CUTOUT,null,0,true,5));}
    @Test void inlineLayoutPreservesEveryNativeOffsetAndAddsOnlyEightBytes(){
        var nativeFormat=DefaultVertexFormat.BLOCK;var extended=NativeTerrainAttributes.FORMAT;
        for(String field:new String[]{"Position","Color","UV0","UV2"}){
            assertEquals(nativeFormat.getElement(field).offset(),extended.getElement(field).offset());
            assertEquals(nativeFormat.getElement(field).format(),extended.getElement(field).format());
        }
        assertEquals(36,extended.getVertexSize());assertEquals(28,extended.getElement("UV1").offset());assertEquals(32,extended.getElement("Normal").offset());
    }
    @Test void unlitTintAndIndependentEmissionSurviveSignedShortShaderPacking(){
        var a=NativeTerrainAttributes.attributes(slope(),0xff80ff40,14,false);
        int low=(short)(a.tintMetadata()&65535),high=(short)(a.tintMetadata()>>>16);
        int shaderPacked=(low&65535)|((high&65535)<<16);
        assertEquals(0x80ff40,shaderPacked&0xffffff);assertEquals(5,(shaderPacked>>>24)&15);
        assertEquals(3,shaderPacked>>>28);assertEquals(14,a.blockEmission());
        assertEquals(-Math.sqrt(.5),a.normal().x,1e-6);
        assertEquals(Math.sqrt(.5),a.normal().y,1e-6);
        assertEquals(2,NativeTerrainAttributes.attributes(slope(),-1,0,true).tintMetadata()>>>28);
    }
    @Test void compilerThreadScopeRestoresNestedContextEvenAfterFailure() throws Exception {
        var a=NativeTerrainAttributes.attributes(slope(),-1,0,false);
        var b=NativeTerrainAttributes.attributes(slope(),0xffabcdef,15,false);
        assertNull(NativeTerrainAttributes.current());
        NativeTerrainAttributes.with(a,()->{
            assertSame(a,NativeTerrainAttributes.current());
            var observed=new java.util.concurrent.atomic.AtomicReference<NativeTerrainAttributes.Attributes>(a);
            var thread=new Thread(()->observed.set(NativeTerrainAttributes.current()));thread.start();
            try{thread.join();}catch(InterruptedException e){throw new AssertionError(e);}
            assertNull(observed.get());
            assertThrows(IllegalStateException.class,()->NativeTerrainAttributes.with(b,()->{assertSame(b,NativeTerrainAttributes.current());throw new IllegalStateException();}));
            assertSame(a,NativeTerrainAttributes.current());
        });
        assertNull(NativeTerrainAttributes.current());
    }
}
