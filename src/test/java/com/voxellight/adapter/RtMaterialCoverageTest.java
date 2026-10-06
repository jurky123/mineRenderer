package com.voxellight.adapter;
import java.nio.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RtMaterialCoverageTest {
    @AfterEach void clear(){RtMaterialCoverage.clear();}
    private byte[] triangle(int flags,int tint){var data=ByteBuffer.allocate(120).order(ByteOrder.nativeOrder());for(int v=0;v<3;v++){data.putFloat(v*40+12,.5f).putFloat(v*40+16,.5f).putInt(v*40+32,tint<<24).putInt(v*40+36,flags);}return data.array();}
    @Test void staticAtlasWritesInvalidateOnlyTheirRegion(){
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"test atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,2,2,1,1){public void close(){}public boolean isClosed(){return false;}};
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{-1,256,255,255});RtMaterialCoverage.watch(texture);long epoch=RtMaterialCoverage.opacityEpoch();
        RtMaterialCoverage.written(texture,0,0,0,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,0,1,0,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,1,0,1,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,0,0,1,1,1);assertTrue(RtMaterialCoverage.opacityValid());assertEquals(epoch+1,RtMaterialCoverage.opacityEpoch());assertEquals(-3,RtMaterialCoverage.opacity(triangle(1,255),0));
    }
    @Test void animatedWritesLeaveUntouchedStaticOpacityAvailable(){
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,16,16,1,1){public void close(){}public boolean isClosed(){return false;}};
        var alpha=new short[256];java.util.Arrays.fill(alpha,(short)255);RtMaterialCoverage.publish(16,16,new byte[256],alpha);RtMaterialCoverage.watch(texture);
        byte[] source=triangle(1,255);assertEquals(-2,RtMaterialCoverage.opacity(source,0));long epoch=RtMaterialCoverage.opacityEpoch();
        RtMaterialCoverage.written(texture,0,0,0,2,2);assertEquals(epoch+1,RtMaterialCoverage.opacityEpoch());assertEquals(-2,RtMaterialCoverage.opacity(source,0));
        RtMaterialCoverage.written(texture,0,0,0,2,2);assertEquals(epoch+1,RtMaterialCoverage.opacityEpoch());
        RtMaterialCoverage.written(texture,0,7,7,1,1);assertEquals(-3,RtMaterialCoverage.opacity(source,0));assertTrue(RtMaterialCoverage.opacityValid());
    }
    @Test void fullyUnknownCoverageIsUnavailableAndWritesInvalidateLastKnownTexels(){
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{-1,-1,-1,-1});assertFalse(RtMaterialCoverage.opacityValid());assertEquals(0,RtMaterialCoverage.knownOpacityTexels());
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,2,2,1,1){public void close(){}public boolean isClosed(){return false;}};
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,255,-1,256});RtMaterialCoverage.watch(texture);assertEquals(2,RtMaterialCoverage.knownOpacityTexels());
        RtMaterialCoverage.written(texture,0,0,0,2,1);assertFalse(RtMaterialCoverage.opacityValid());assertEquals(0,RtMaterialCoverage.knownOpacityTexels());assertEquals(-3,RtMaterialCoverage.opacity(triangle(1,255),0));
    }
    @Test void refreshIndicesIgnoreOpaqueAndTransmissionAndKeepCutoutOrder(){
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,255,255,255});
        var source=ByteBuffer.allocate(480).put(triangle(0,255)).put(triangle(1,255)).put(triangle(2,255)).put(triangle(1,100)).array();
        assertArrayEquals(new int[]{-2,-1},RtMaterialCoverage.indices(source));
        var sorted=com.voxellight.rt.RtGeometryRanges.split(source,offset->RtMaterialCoverage.transmissive(source,offset));assertArrayEquals(RtMaterialCoverage.indices(source),RtMaterialCoverage.indices(sorted.triangles()));
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{-1,-1,-1,-1});assertArrayEquals(new int[]{-3,-3},RtMaterialCoverage.indices(source));
    }
    @Test void vanillaAnimationRenderPassPreservesStaticCoverageAndStillInvalidatesAlbedoVersion(){
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,4,4,1,2){public void close(){}public boolean isClosed(){return false;}};
        var view=new com.mojang.blaze3d.textures.GpuTextureView(texture,0,1){public void close(){}public boolean isClosed(){return false;}};
        var alpha=new short[16];java.util.Arrays.fill(alpha,(short)255);alpha[0]=-1;RtMaterialCoverage.publish(4,4,new byte[16],alpha);RtMaterialCoverage.watch(texture);
        long version=NativeTextureVersions.version(texture),epoch=RtMaterialCoverage.opacityEpoch();
        RtMaterialCoverage.animationPass(texture,()->NativeTextureVersions.rendered(view));
        assertEquals(version+1,NativeTextureVersions.version(texture));assertEquals(epoch,RtMaterialCoverage.opacityEpoch());assertEquals(15,RtMaterialCoverage.knownOpacityTexels());
        var mip=new com.mojang.blaze3d.textures.GpuTextureView(texture,1,1){public void close(){}public boolean isClosed(){return false;}};
        NativeTextureVersions.rendered(mip);assertEquals(epoch,RtMaterialCoverage.opacityEpoch());
        assertThrows(IllegalStateException.class,()->RtMaterialCoverage.animationPass(texture,()->{throw new IllegalStateException("draw failed");}));
        NativeTextureVersions.rendered(view);assertFalse(RtMaterialCoverage.opacityValid());assertEquals(0,RtMaterialCoverage.knownOpacityTexels());
    }
    @Test void trustedAnimationScopeDoesNotHideDirectWritesOrAnotherTextureRender(){
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,2,2,1,1){public void close(){}public boolean isClosed(){return false;}};
        var other=new com.mojang.blaze3d.textures.GpuTexture(0,"other",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,2,2,1,1){public void close(){}public boolean isClosed(){return false;}};
        var view=new com.mojang.blaze3d.textures.GpuTextureView(texture,0,1){public void close(){}public boolean isClosed(){return false;}};
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,255,255,255});RtMaterialCoverage.watch(texture);
        RtMaterialCoverage.animationPass(texture,()->RtMaterialCoverage.written(texture,0,0,0,1,1));assertEquals(3,RtMaterialCoverage.knownOpacityTexels());
        RtMaterialCoverage.animationPass(other,()->NativeTextureVersions.rendered(view));assertFalse(RtMaterialCoverage.opacityValid());
    }
    @Test void uncertainAndAuthoredTransmissionCannotEnterOpaqueRange(){
        var source=triangle(0,255);assertTrue(RtMaterialCoverage.transmissive(source,0));
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,255,255,255});assertFalse(RtMaterialCoverage.transmissive(source,0));
        RtMaterialCoverage.publish(2,2,new byte[]{0,1,0,0},new short[]{255,255,255,255});assertTrue(RtMaterialCoverage.transmissive(source,0));
        assertTrue(RtMaterialCoverage.transmissive(triangle(2,255),0));
        ByteBuffer.wrap(source).order(ByteOrder.nativeOrder()).putFloat(12,Float.NaN);assertTrue(RtMaterialCoverage.transmissive(source,0));
        assertFalse(RtMaterialCoverage.transmissive(triangle(128,255),0));
    }
    @Test void opacityMatchesShaderThresholdsIncludingGlassException(){
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,255,255,255});assertEquals(-2,RtMaterialCoverage.opacity(triangle(1,128),0));assertEquals(-1,RtMaterialCoverage.opacity(triangle(1,127),0));
        assertEquals(-2,RtMaterialCoverage.opacity(triangle(17,26),0));assertEquals(-1,RtMaterialCoverage.opacity(triangle(17,25),0));
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{256,256,256,256});assertEquals(-2,RtMaterialCoverage.opacity(triangle(1,0),0));
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{0,255,0,255});assertEquals(-3,RtMaterialCoverage.opacity(triangle(1,255),0));
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{255,-1,255,255});assertEquals(-3,RtMaterialCoverage.opacity(triangle(1,255),0));assertEquals(-3,RtMaterialCoverage.opacity(triangle(129,255),0));
    }
}
