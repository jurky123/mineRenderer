package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.rt.vulkan.VulkanRtContext;
import com.voxellight.world.SectionKey;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.util.*;

/** Native animated entity/block-entity meshes and a GPU-only texture atlas in the same RT world. */
final class RtDynamicScene implements AutoCloseable {
    static final int SIZE=2048,CELL=128,SLOTS=256;
    private static final RenderPipeline COPY=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/rt_dynamic_atlas"))
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","rt_atlas"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).withCull(false).build();
    private final EntityShadows entities=new EntityShadows();
    private final DynamicModelBuffer particles=new DynamicModelBuffer("RT quad particles");
    private final DynamicModelBuffer hands=new DynamicModelBuffer("RT held models");
    private final BlockEntityShadows blocks=new BlockEntityShadows();
    private GpuTexture atlas,cell;
    private GpuTextureView cellView;
    private GpuTextureView atlasView;
    private int previousCount;
    private long bytes;
    RtDynamicScene(){entities.rtCapture();blocks.rtCapture();}
    void prepare(CommandEncoder encoder,VulkanRtContext context,double x,double y,double z){
        entities.prepare();blocks.prepare();hands.begin();var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.player!=null&&mc.options.getCameraType().isFirstPerson()&&!mc.player.isSpectator()&&!mc.player.isSleeping()&&!mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden){
            var collection=new net.minecraft.client.renderer.SubmitNodeCollection(){
                @Override public <S> void submitModel(net.minecraft.client.model.Model<? super S> model,S state,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.rendertype.RenderType type,int light,int overlay,int tint,net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,int outline,net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling){hands.capture(model,state,pose,type,light,overlay,tint,sprite);}
                @Override public void submitItem(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.world.item.ItemDisplayContext display,int light,int overlay,int outline,int[] tints,List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil){hands.captureItem(pose,light,overlay,tints,quads);}
                @Override public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.rendertype.RenderType type,net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer){hands.captureCustom(pose,type,renderer);}
            };
            var collector=new net.minecraft.client.renderer.SubmitNodeStorage(){@Override public net.minecraft.client.renderer.SubmitNodeCollection order(int ignored){return collection;}};
            var pose=new com.mojang.blaze3d.vertex.PoseStack();pose.mulPose(new org.joml.Matrix4f(mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix).invert());
            try{mc.gameRenderer.itemInHandRenderer.submitHandsWithItems(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false),pose,collector,mc.player,15728880);}catch(RuntimeException error){hands.begin();org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("RT first-person model capture deferred",error);}
        }
        hands.finish();particles.begin();for(var group:mc.gameRenderer.gameRenderState().levelRenderState.particlesRenderState.particles)if(group instanceof net.minecraft.client.renderer.state.level.QuadParticleRenderState quad)particles.captureParticles(quad);particles.finish();var models=new ArrayList<DynamicModelBuffer.RtModel>();models.addAll(hands.rtModels());models.addAll(entities.rtModels());models.addAll(blocks.rtModels());models.addAll(particles.rtModels());
        var textures=new LinkedHashMap<GpuTextureView,Integer>();var changes=new ArrayList<RtGeometryStream.Section>();int index=0;bytes=0;
        if(atlas==null){var device=RenderSystem.getDevice();if(!device.precompilePipeline(COPY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Dynamic RT atlas unavailable");atlas=device.createTexture("VoxelLight RT dynamic textures",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);atlasView=device.createTextureView(atlas);cell=device.createTexture("VoxelLight dynamic RT texture resample",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,CELL,CELL,1,1);cellView=device.createTextureView(cell);}
        for(var model:models){
            var texture=model.texture().textureView();if(!textures.containsKey(texture)&&textures.size()>=SLOTS)continue;
            int slot=textures.computeIfAbsent(texture,ignored->textures.size());byte[] vertices=triangles(model.quads(),slot,x,y,z);bytes+=vertices.length;
            if(bytes>8L*1024*1024)break;long hash=0xcbf29ce484222325L;for(byte b:vertices){hash^=b&255;hash*=0x100000001b3L;}
            changes.add(new RtGeometryStream.Section(new SectionKey(index++,Integer.MIN_VALUE,0),hash,vertices));
        }
        for(int i=index;i<previousCount;i++)changes.add(new RtGeometryStream.Section(new SectionKey(i,Integer.MIN_VALUE,0),0,new byte[0]));previousCount=index;
        context.prepareScene(encoder,changes,x,y,z);
        for(var entry:textures.entrySet()){try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight dynamic RT texture copy").withRenderArea(new RenderPass.RenderArea(0,0,CELL,CELL)).withColorAttachment(cellView,Optional.empty()))){
            pass.setPipeline(COPY);pass.bindTexture("Sampler0",entry.getKey(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
        }encoder.copyTextureToTexture(cell,atlas,0,entry.getValue()%16*CELL,entry.getValue()/16*CELL,0,0,CELL,CELL);
        }
    }
    static byte[] triangles(byte[] quads,int slot,double x,double y,double z){
        var input=ByteBuffer.wrap(quads).order(ByteOrder.nativeOrder());int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();
        var output=ByteBuffer.allocate(quads.length/stride/4*240).order(ByteOrder.nativeOrder());
        for(int q=0;q<quads.length/stride/4;q++)for(int v:new int[]{0,1,2,2,3,0}){
            int o=(q*4+v)*stride;output.putFloat((float)(input.getFloat(o)+x)).putFloat((float)(input.getFloat(o+4)+y)).putFloat((float)(input.getFloat(o+8)+z));
            output.putFloat(input.getFloat(o+16)).putFloat(input.getFloat(o+20));output.putFloat(0).putFloat(0).putFloat(0);
            output.putInt(input.getInt(o+12)).putInt(128|1|(slot<<20));
        }
        return output.array();
    }
    boolean hasHands(){return !hands.rtModels().isEmpty();}
    GpuTexture atlas(){return atlas;}
    String status(){return ", rtDynamicModels="+previousCount+", rtDynamicBytes="+bytes+", rtDynamicCoverage=entity+block_entity+held+custom+cutout_quad_particles";}
    public void close(){entities.close();blocks.close();hands.close();particles.close();if(cellView!=null)cellView.close();if(cell!=null)cell.close();cellView=null;cell=null;if(atlasView!=null)atlasView.close();if(atlas!=null)atlas.close();atlasView=null;atlas=null;previousCount=0;bytes=0;}
}
