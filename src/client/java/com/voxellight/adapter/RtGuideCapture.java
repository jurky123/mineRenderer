package com.voxellight.adapter;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Explicit, asynchronous input/output capture. No per-frame readback when unrequested. */
public final class RtGuideCapture {
    private static final AtomicBoolean requested=new AtomicBoolean();
    private RtGuideCapture(){}
    public static boolean request(){return requested.compareAndSet(false,true);}
    static void capture(CommandEncoder encoder,com.voxellight.rt.vulkan.VulkanRtContext context,GpuTextureView[] textures,String[] originalNames,int[] originalChannels,boolean[] half,String metadata){
        if(!requested.compareAndSet(true,false))return;
        String[] names=java.util.Arrays.copyOf(originalNames,textures.length+3);int[] channels=java.util.Arrays.copyOf(originalChannels,textures.length+3);
        names[textures.length]="diffuse-aov-hdr";names[textures.length+1]="reflection-aov-hdr";names[textures.length+2]="transmission-aov-hdr";
        byte[][] values=new byte[names.length][];int[] widths=new int[names.length],heights=new int[names.length];
        AtomicInteger remaining=new AtomicInteger(names.length);AtomicBoolean failed=new AtomicBoolean();
        Path path=Minecraft.getInstance().gameDirectory.toPath().resolve("benchmark-results/voxellight/rr-guides-"+System.currentTimeMillis()+".zip");
        for(int plane=3;plane<=5;plane++){
            final int index=textures.length+plane-3;int w=textures[0].texture().getWidth(0),h=textures[0].texture().getHeight(0);widths[index]=w;heights[index]=h;channels[index]=4;
            var read=RenderSystem.getDevice().createBuffer(()->"VoxelLight AOV capture",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,w*h*16);
            try{context.copyGuideToBuffer(encoder,plane,read.slice());RenderSystem.queueFencedTask(()->{
                try(var map=read.map(true,false)){var input=map.data().order(ByteOrder.nativeOrder());var output=ByteBuffer.allocate(w*h*16).order(ByteOrder.LITTLE_ENDIAN);for(int n=0;n<w*h*4;n++)output.putFloat(input.getFloat(n*4));values[index]=output.array();}
                catch(RuntimeException error){failed.set(true);org.slf4j.LoggerFactory.getLogger("VoxelLight").error("AOV capture failed",error);}
                finally{read.close();if(remaining.decrementAndGet()==0&&!failed.get())java.util.concurrent.CompletableFuture.runAsync(()->write(path,names,channels,widths,heights,values,metadata));}
            });}catch(RuntimeException error){read.close();failed.set(true);remaining.decrementAndGet();throw error;}
        }
        for(int i=0;i<textures.length;i++){
            final int index=i;var texture=textures[i].texture();int w=texture.getWidth(0),h=texture.getHeight(0);widths[i]=w;heights[i]=h;
            int bytes=w*h*channels[i]*(half[i]?2:4);var read=RenderSystem.getDevice().createBuffer(()->"VoxelLight RR capture "+names[index],GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes);
            try{encoder.copyTextureToBuffer(texture,read,0,()->{
                try(var map=read.map(true,false)){
                    var input=map.data().order(ByteOrder.nativeOrder());var output=ByteBuffer.allocate(w*h*channels[index]*4).order(ByteOrder.LITTLE_ENDIAN);
                    for(int n=0;n<w*h*channels[index];n++)output.putFloat(half[index]?Float.float16ToFloat(input.getShort(n*2)):input.getFloat(n*4));values[index]=output.array();
                }catch(RuntimeException error){failed.set(true);org.slf4j.LoggerFactory.getLogger("VoxelLight").error("RR capture readback failed",error);}
                finally{read.close();if(remaining.decrementAndGet()==0&&!failed.get())java.util.concurrent.CompletableFuture.runAsync(()->write(path,names,channels,widths,heights,values,metadata));}
            },0);}catch(RuntimeException error){read.close();failed.set(true);remaining.decrementAndGet();throw error;}
        }
    }
    private static void write(Path path,String[] names,int[] channels,int[] widths,int[] heights,byte[][] values,String metadata){
        try{Files.createDirectories(path.getParent());try(var zip=new ZipOutputStream(Files.newOutputStream(path))){
            StringBuilder description=new StringBuilder(metadata+"\nRaw files: little-endian float32, row order matches Vulkan readback; PNGs are diagnostic previews, not reference HDR.\n");
            for(int i=0;i<names.length;i++){
                description.append(names[i]).append(" ").append(widths[i]).append("x").append(heights[i]).append(" channels=").append(channels[i]).append('\n');
                zip.putNextEntry(new ZipEntry(names[i]+".f32"));zip.write(values[i]);zip.closeEntry();
                var b=ByteBuffer.wrap(values[i]).order(ByteOrder.LITTLE_ENDIAN);var preview=new BufferedImage(widths[i],heights[i],BufferedImage.TYPE_INT_RGB);
                for(int p=0;p<widths[i]*heights[i];p++){
                    int color=0;for(int c=0;c<3;c++){float v=b.getFloat((p*channels[i]+Math.min(c,channels[i]-1))*4);
                        if(names[i].contains("position"))v=b.getFloat((p*channels[i]+3)*4)>0?1:0;else if(names[i].contains("normal"))v=v*.5f+.5f;else if(names[i].contains("motion"))v=.5f+v/32;else if(names[i].contains("hdr")||names[i].contains("output"))v=(float)Math.pow(Math.max(0,v)/(1+Math.max(0,v)),1/2.2);
                        color=(color<<8)|Math.clamp(Math.round(v*255),0,255);
                    }preview.setRGB(p%widths[i],p/widths[i],color);
                }zip.putNextEntry(new ZipEntry(names[i]+".png"));ImageIO.write(preview,"png",zip);zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("capture.txt"));zip.write(description.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }org.slf4j.LoggerFactory.getLogger("VoxelLight").info("RR guides exported: {}",path);
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger("VoxelLight").error("RR guides export failed",error);}
    }
}
