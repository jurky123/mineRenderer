package com.voxellight.mixin.client;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.voxellight.adapter.NativeTextureVersions;
import java.nio.ByteBuffer;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanCommandEncoder.class)
abstract class VulkanTextureVersionsMixin {
    @Inject(method="writeToTexture",at=@At("HEAD"))
    private void voxellight$upload(GpuTexture destination,ByteBuffer source,int mip,int layer,int x,int y,int w,int h,CallbackInfo ci){NativeTextureVersions.written(destination,mip,x,y,w,h);}
    @Inject(method="copyTextureToTexture",at=@At("HEAD"))
    private void voxellight$copy(GpuTexture source,GpuTexture destination,int mip,int dx,int dy,int sx,int sy,int w,int h,CallbackInfo ci){NativeTextureVersions.written(destination,mip,dx,dy,w,h);}
    @Inject(method="copyBufferToTexture",at=@At("HEAD"))
    private void voxellight$bufferCopy(GpuBufferSlice source,int sourceX,int sourceY,int rowLength,int imageHeight,GpuTexture destination,int x,int y,int w,int h,int mip,int layer,CallbackInfo ci){NativeTextureVersions.written(destination,mip,x,y,w,h);}
    @Inject(method="createRenderPass",at=@At("HEAD"))
    private void voxellight$render(RenderPassDescriptor descriptor,CallbackInfoReturnable<?> ci){for(var attachment:descriptor.colorAttachments())if(attachment!=null)NativeTextureVersions.rendered(attachment.textureView());}
    @Inject(method="clearColorTexture",at=@At("HEAD"))
    private void voxellight$clear(GpuTexture texture,Vector4fc color,CallbackInfo ci){NativeTextureVersions.written(texture);}
    @Inject(method="clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V",at=@At("HEAD"))
    private void voxellight$clearBoth(GpuTexture texture,Vector4fc color,GpuTexture depth,double value,CallbackInfo ci){NativeTextureVersions.written(texture);}
}
