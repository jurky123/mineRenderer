package com.voxellight.adapter;
import java.nio.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class RtMaterialCoverageTest {
    @AfterEach void clear(){RtMaterialCoverage.clear();}
    private byte[] triangle(int flags,int tint){var data=ByteBuffer.allocate(120).order(ByteOrder.nativeOrder());for(int v=0;v<3;v++){data.putFloat(v*40+12,.5f).putFloat(v*40+16,.5f).putInt(v*40+32,tint<<24).putInt(v*40+36,flags);}return data.array();}
    @Test void staticAtlasWritesInvalidateButAnimatedAndTransmissionWritesRemainSafe(){
        var texture=new com.mojang.blaze3d.textures.GpuTexture(0,"test atlas",com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,2,2,1,1){public void close(){}public boolean isClosed(){return false;}};
        RtMaterialCoverage.publish(2,2,new byte[4],new short[]{-1,256,255,255});RtMaterialCoverage.watch(texture);long epoch=RtMaterialCoverage.opacityEpoch();
        RtMaterialCoverage.written(texture,0,0,0,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,0,1,0,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,1,0,1,1,1);assertTrue(RtMaterialCoverage.opacityValid());
        RtMaterialCoverage.written(texture,0,0,1,1,1);assertFalse(RtMaterialCoverage.opacityValid());assertEquals(epoch+1,RtMaterialCoverage.opacityEpoch());assertEquals(-3,RtMaterialCoverage.opacity(triangle(1,255),0));
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
