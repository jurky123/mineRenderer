package com.voxellight.mixin.client;

import com.voxellight.adapter.RenderPassProfile;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Omit the vanilla frame graph only after this frame's PT composite succeeds. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin implements com.voxellight.adapter.RtWorldMaintenance {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private OptionsRenderState optionsRenderState;
    @Shadow @Final private SectionOcclusionGraph sectionOcclusionGraph;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Shadow protected abstract void repositionCamera(CameraRenderState camera);
    @Shadow protected abstract void compileSections(CameraRenderState camera);
    @Shadow public abstract boolean isSectionCompiledAndVisible(net.minecraft.core.BlockPos pos);

    public void voxellight$maintainWorld(CameraRenderState camera){
        // These tasks previously lived after executeFrameGraph. Keep native dirty-section
        // compilation feeding RT, including edits, uploads and loading-screen completion.
        long start=System.nanoTime();
        long stage=System.nanoTime();repositionCamera(camera);RenderPassProfile.cpu("rt_world_reposition",System.nanoTime()-stage);
        stage=System.nanoTime();compileSections(camera);RenderPassProfile.cpu("rt_world_compile",System.nanoTime()-stage);
        stage=System.nanoTime();if(sectionRenderDispatcher!=null){
            sectionRenderDispatcher.lock();
            try{sectionRenderDispatcher.uploadTerrainBuffersToGpu();}
            finally{sectionRenderDispatcher.unlock();}
        }
        RenderPassProfile.cpu("rt_world_upload",System.nanoTime()-stage);stage=System.nanoTime();
        sectionOcclusionGraph.update(camera,optionsRenderState.fov,levelRenderState.chunkLoadingRenderState);
        RenderPassProfile.cpu("rt_world_occlusion",System.nanoTime()-stage);stage=System.nanoTime();
        var ready=levelRenderState.playerCompiledSectionCallback;
        if(ready!=null&&isSectionCompiledAndVisible(camera.blockPos))ready.run();
        RenderPassProfile.cpu("rt_world_callback",System.nanoTime()-stage);
        RenderPassProfile.cpu("rt_world_maintenance",System.nanoTime()-start);
    }
}
