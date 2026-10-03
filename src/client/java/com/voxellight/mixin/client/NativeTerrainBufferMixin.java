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
    // The pinned BLOCK fast path writes only the original 28 bytes. Use setters for the extended format.
    @Inject(method="addVertex(FFFIFFIIFFF)V",at=@At("HEAD"),cancellable=true)
    private void voxellight$extendedVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz,CallbackInfo ci){
        if(format!=NativeTerrainAttributes.FORMAT)return;
        var a=NativeTerrainAttributes.nextVertex();int packed=a==null?0:a.tintMetadata();
        addVertex(x,y,z).setColor(color).setUv(u,v).setUv2(light&65535,light>>>16)
                .setUv1(packed&65535,packed>>>16).setNormal(a==null?nx:a.normal().x,a==null?ny:a.normal().y,a==null?nz:a.normal().z);
        MemoryUtil.memPutByte(vertexPointer+35,(byte)(a==null?0:16+a.blockEmission()));
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
