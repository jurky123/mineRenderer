package com.voxellight.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.voxellight.VoxelLightClient;
import com.voxellight.adapter.RenderPassProfile;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Omit the vanilla frame graph only after this frame's PT composite succeeds. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow @Final private SectionOcclusionGraph sectionOcclusionGraph;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow protected abstract void repositionCamera(CameraRenderState camera);
    @Shadow protected abstract void compileSections(CameraRenderState camera);
    @Shadow public abstract boolean isSectionCompiledAndVisible(net.minecraft.core.BlockPos pos);

    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void voxellight$rtWorld(GraphicsResourceAllocator allocator,DeltaTracker delta,boolean outline,
            CameraRenderState camera,Matrix4fc view,GpuBufferSlice fog,Vector4f fogColor,boolean sky,CallbackInfo ci){
        var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if(!VoxelLightClient.probe().tryReplaceWorld(target))return;
        // These tasks previously lived after executeFrameGraph. Keep native dirty-section
        // compilation feeding RT, including edits, uploads and loading-screen completion.
        long start=System.nanoTime();
        repositionCamera(camera);
        compileSections(camera);
        if(sectionRenderDispatcher!=null){
            sectionRenderDispatcher.lock();
            try{sectionRenderDispatcher.uploadTerrainBuffersToGpu();}
            finally{sectionRenderDispatcher.unlock();}
        }
        sectionOcclusionGraph.update(camera,optionsRenderState.fov,levelRenderState.chunkLoadingRenderState);
        var ready=levelRenderState.playerCompiledSectionCallback;
        if(ready!=null&&isSectionCompiledAndVisible(camera.blockPos))ready.run();
        RenderPassProfile.cpu("rt_world_maintenance",System.nanoTime()-start);
        ci.cancel();
    }
}
