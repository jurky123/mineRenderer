package com.voxellight.mixin.client;

import com.mojang.blaze3d.vertex.*;
import com.voxellight.adapter.NativeTerrainAttributes;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BufferBuilder.class)
abstract class NativeTerrainBufferMixin implements VertexConsumer {
    @Shadow private VertexFormat format;
    @Shadow private long vertexPointer;
    @Shadow private int elementsToFill;
    @Shadow private long beginVertex() { throw new AssertionError(); }
    @Shadow private static void putRgba(long pointer,int color) { throw new AssertionError(); }
    @Shadow private static void putPackedUv(long pointer,int value) { throw new AssertionError(); }
    @Shadow private static void putNormals(long pointer,float x,float y,float z) { throw new AssertionError(); }
    // Retain vanilla reservation/counting/endian conversion, write the extended stride directly.
    @Inject(method="addVertex(FFFIFFIIFFF)V",at=@At("HEAD"),cancellable=true)
    private void voxellight$extendedVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz,CallbackInfo ci){
        if(format!=NativeTerrainAttributes.FORMAT)return;
        var a=NativeTerrainAttributes.nextVertex();int packed=a==null?0:a.tintMetadata();
        long pointer=beginVertex();
        MemoryUtil.memPutFloat(pointer,x);MemoryUtil.memPutFloat(pointer+4,y);MemoryUtil.memPutFloat(pointer+8,z);
        putRgba(pointer+12,color);
        MemoryUtil.memPutFloat(pointer+16,u);MemoryUtil.memPutFloat(pointer+20,v);
        putPackedUv(pointer+24,light);putPackedUv(pointer+28,packed);
        putNormals(pointer+32,a==null?nx:a.normal().x,a==null?ny:a.normal().y,a==null?nz:a.normal().z);
        MemoryUtil.memPutByte(pointer+35,(byte)(a==null?0:16+a.blockEmission()));
        elementsToFill=0;
        ci.cancel();
    }
    @Inject(method="beginVertex",at=@At("RETURN"))
    private void voxellight$clearMaterial(CallbackInfoReturnable<Long> ci){
        if(format==NativeTerrainAttributes.FORMAT)MemoryUtil.memSet(ci.getReturnValue()+28,0,8);
    }
    // Fluids and third-party raw emitters may omit the extra fields. Zero means unsupported, never stale memory.
    @Inject(method="endLastVertex",at=@At("HEAD"))
    private void voxellight$defaultAttributes(CallbackInfo ci){
        if(format!=NativeTerrainAttributes.FORMAT || vertexPointer<0)return;
        if((elementsToFill & (1<<3))!=0)setUv1(0,0);
        if((elementsToFill & (1<<5))!=0){setNormal(0,1,0);MemoryUtil.memPutByte(vertexPointer+35,(byte)0);}
    }
}
