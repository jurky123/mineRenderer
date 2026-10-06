package com.voxellight.adapter;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.PoseStack;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Loaded-only block-entity casters, independent of the main camera's visible section list. */
final class BlockEntityShadows implements AutoCloseable {
    private final DynamicModelBuffer buffer=new DynamicModelBuffer("block entity");
    private boolean enabled=true,loggedFailure,rtCapture;
    void rtCapture(){rtCapture=true;}
    private int selected,candidates,overflow,failures,chunks;
    private long captureNs;

    void prepare() {
        long start=System.nanoTime();
        buffer.begin();selected=candidates=overflow=failures=chunks=0;
        if(!enabled){captureNs=0;return;}
        var minecraft=Minecraft.getInstance();
        var camera=minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var dispatcher=minecraft.getBlockEntityRenderDispatcher();
        var selection=new DynamicCasterSelection<BlockEntity>();
        int cx=SectionPos.blockToSectionCoord(camera.pos.x()),cz=SectionPos.blockToSectionCoord(camera.pos.z());
        int radius=(int)Math.ceil(DynamicCasterSelection.RADIUS/16);
        for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++) {
            var chunk=minecraft.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
            if(chunk==null)continue;
            chunks++;
            for(var blockEntity:chunk.getBlockEntities().values()) {
                if(blockEntity.isRemoved() || !blockEntity.hasLevel()
                        || !blockEntity.getType().isValid(blockEntity.getBlockState()))continue;
                // Non-rendering block entities must not consume admission slots.
                if(dispatcher.getRenderer(blockEntity)==null)continue;
                var pos=blockEntity.getBlockPos();
                selection.consider(blockEntity,pos.asLong(),Vec3.atCenterOf(pos).distanceToSqr(camera.pos));
            }
        }
        var values=selection.selected();selected=values.size();candidates=selection.candidates();overflow=selection.overflow();
        var collection=new SubmitNodeCollection(){
            @Override public <S> void submitModel(Model<? super S> model,S state,PoseStack pose,RenderType type,
                    int light,int overlay,int tint,TextureAtlasSprite sprite,int outline,ModelFeatureRenderer.CrumblingOverlay crumbling) {
                buffer.capture(model,state,pose,type,light,overlay,tint,sprite);
            }
            @Override public void submitItem(PoseStack pose,net.minecraft.world.item.ItemDisplayContext display,int light,int overlay,int outline,int[] tints,java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil){if(rtCapture)buffer.captureItem(pose,light,overlay,tints,quads);}
            @Override public void submitCustomGeometry(PoseStack pose,RenderType type,net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer){if(rtCapture)buffer.captureCustom(pose,type,renderer);}
        };
        var collector=new SubmitNodeStorage(){
            @Override public SubmitNodeCollection order(int ignored){return collection;}
        };
        float partial=minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for(var blockEntity:values) {
            var before=buffer.checkpoint();
            try {
                var renderer=dispatcher.getRenderer(blockEntity);
                if(renderer==null)continue;
                var state=dispatcher.tryExtractRenderState(blockEntity,partial,null,renderer.shouldRenderOffScreen());
                if(state==null)continue;
                var pos=state.blockPos;
                var pose=new PoseStack();
                pose.translate(pos.getX()-camera.pos.x(),pos.getY()-camera.pos.y(),pos.getZ()-camera.pos.z());
                dispatcher.submit(state,pose,collector,camera);
            }catch(RuntimeException error){
                buffer.rollback(before);failures++;
                if(!loggedFailure){
                    LoggerFactory.getLogger("VoxelLight").warn("Skipping unsupported block-entity shadow renderer",error);
                    loggedFailure=true;
                }
            }
        }
        buffer.finish();captureNs=System.nanoTime()-start;
    }
    java.util.List<DynamicModelBuffer.RtModel> rtModels(){return buffer.rtModels();}
    void upload(CommandEncoder encoder){buffer.upload(encoder);}
    int maxIndices(){return buffer.maxIndices();}
    void borrowIndices(com.mojang.blaze3d.buffers.GpuBuffer indices,com.mojang.blaze3d.IndexType type){buffer.borrowIndices(indices,type);}
    boolean hasModels(){return buffer.hasModels();}
    void draw(RenderPass pass){buffer.draw(pass);}
    void setEnabled(boolean enabled){this.enabled=enabled;}
    String status(){return ", blockEntityShadows="+(enabled?"on":"off")+", blockEntityCasters="+selected+"/"+candidates
            +", blockEntityOverflow="+overflow+", blockEntityModels="+buffer.modelCount()+", blockEntitySkipped="+buffer.skipped()
            +", blockEntityFailures="+failures+", blockEntityChunks="+chunks+", blockEntityUploadBytes="+buffer.bytes()
            +", blockEntityCaptureNs="+captureNs+", blockEntityBufferBytes="+buffer.bufferBytes();}
    @Override public void close(){buffer.close();selected=candidates=overflow=failures=chunks=0;captureNs=0;loggedFailure=false;}
}
