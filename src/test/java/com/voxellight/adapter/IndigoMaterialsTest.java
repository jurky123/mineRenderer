package com.voxellight.adapter;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndigoMaterialsTest {
    @Test void capturesPerVertexUnlitColorBeforeRendererMutatesLighting(){
        var quad=new IndigoMaterials.Quad();quad.pos(0,0,0,0).pos(1,1,1,0).pos(2,1,1,1).pos(3,0,0,1);
        quad.nominalFace(net.minecraft.core.Direction.UP);quad.chunkLayer(ChunkSectionLayer.CUTOUT);quad.tintIndex(0);quad.diffuseShade(true);quad.animated(true);
        for(int i=0;i<4;i++)quad.color(i,0xff80ff40+i).lightmap(i,5*16);
        var material=IndigoMaterials.capture(quad,0xff80ffff,14);
        for(int i=0;i<4;i++)quad.color(i,0xff010101).lightmap(i,240);
        for(int i=0;i<4;i++){
            assertEquals(0x40ff40+i,material[i].tintMetadata()&0xffffff);
            assertEquals(5,(material[i].tintMetadata()>>>24)&15);
            assertEquals(11,material[i].tintMetadata()>>>28);
            assertEquals(14,material[i].blockEmission());assertEquals(-Math.sqrt(.5),material[i].normal().x,1e-6);
        }
    }
    @Test void perVertexEmissionScopeDoesNotLeakOrOverrideNestedScalarOutput(){
        var a=new NativeTerrainAttributes.Attributes(1,new org.joml.Vector3f(0,1,0),0);
        var b=new NativeTerrainAttributes.Attributes(2,new org.joml.Vector3f(0,1,0),15);
        assertThrows(IllegalStateException.class,()->NativeTerrainAttributes.withVertices(new NativeTerrainAttributes.Attributes[]{a,b},()->{
            assertSame(a,NativeTerrainAttributes.nextVertex());
            NativeTerrainAttributes.with(a,()->assertSame(a,NativeTerrainAttributes.nextVertex()));
            assertSame(b,NativeTerrainAttributes.nextVertex());assertNull(NativeTerrainAttributes.nextVertex());
            throw new IllegalStateException();
        }));
        assertNull(NativeTerrainAttributes.nextVertex());
    }
    @Test void pinnedFabricCompilerRoutesThroughAltRendererInsteadOfVanillaOutput() throws Exception {
        var type=new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader("net/fabricmc/fabric/mixin/client/renderer/block/render/SectionCompilerMixin").accept(type,0);
        var proxy=type.methods.stream().filter(m->m.name.equals("tesselateBlockProxy")).findFirst().orElseThrow();
        boolean alt=false;
        for(var instruction:proxy.instructions)if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call){
            assertNotEquals("net/minecraft/client/renderer/block/BlockQuadOutput",call.owner);
            if(call.owner.equals("net/fabricmc/fabric/api/client/renderer/v1/render/AltModelBlockRenderer") && call.name.equals("tesselateBlock"))alt=true;
        }
        assertTrue(alt);
    }
}
