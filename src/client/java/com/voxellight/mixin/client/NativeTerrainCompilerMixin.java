package com.voxellight.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.voxellight.adapter.NativeTerrainAttributes;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionCompiler.class)
abstract class NativeTerrainCompilerMixin {
    @Shadow private BlockColors blockColors;
    @Shadow private boolean cutoutLeaves;
    @WrapOperation(method="compile",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void voxellight$compileMaterial(ModelBlockRenderer renderer,BlockQuadOutput output,float x,float y,float z,BlockAndTintGetter world,BlockPos pos,BlockState state,BlockStateModel model,long seed,Operation<Void> original){
        boolean forceSolid=ModelBlockRenderer.forceOpaque(cutoutLeaves,state);
        BlockQuadOutput decorated=(qx,qy,qz,quad,instance)->{
            var source=quad.materialInfo().tintIndex()<0?null:blockColors.getTintSource(state,quad.materialInfo().tintIndex());
            int tint=source==null?-1:source.colorInWorld(state,world,pos);
            var attributes=NativeTerrainAttributes.attributes(quad,tint,state.getLightEmission(),forceSolid);
            NativeTerrainAttributes.with(attributes,()->output.put(qx,qy,qz,quad,instance));
        };
        original.call(renderer,decorated,x,y,z,world,pos,state,model,seed);
    }
}
