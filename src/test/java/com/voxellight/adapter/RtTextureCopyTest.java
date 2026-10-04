package com.voxellight.adapter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.systems.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class RtTextureCopyTest {
 @Test void fullRectangularSignalPassesActualMinecraftValidation(){
  int w=640,h=338;var calls=new AtomicInteger();
  var backend=(CommandEncoderBackend)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CommandEncoderBackend.class},(proxy,method,args)->{
   assertEquals("copyBufferToTexture",method.getName());calls.incrementAndGet();
   assertArrayEquals(new Object[]{0,0,w,h},java.util.Arrays.copyOfRange(args,1,5));
   assertArrayEquals(new Object[]{0,0,w,h,0,0},java.util.Arrays.copyOfRange(args,6,12));return null;
  });
  var encoder=new CommandEncoder(null,null,backend);
  var buffer=new GpuBuffer(GpuBuffer.USAGE_COPY_SRC,(long)w*h*16){public boolean isClosed(){return false;}public void close(){}public GpuBufferSlice.MappedView map(long o,long l,boolean r,boolean wr){throw new UnsupportedOperationException();}};
  var texture=new GpuTexture(GpuTexture.USAGE_COPY_DST,"RT test",GpuFormat.RGBA32_FLOAT,w,h,1,1){public boolean isClosed(){return false;}public void close(){}};
  RtxLightingPass.copyRtTexture(encoder,buffer,texture,w,h);
  assertEquals(1,calls.get());
  assertThrows(IllegalArgumentException.class,()->encoder.copyBufferToTexture(buffer.slice(),w,0,0,0,texture,0,0,0,0,w,h));
 }
}
