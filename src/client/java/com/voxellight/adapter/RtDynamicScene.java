package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.UniformType;
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
        .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","rt_dynamic_atlas"))
        .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").withUniform("AtlasRegion",UniformType.UNIFORM_BUFFER).build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL)).withCull(false).build();
    private final EntityShadows entities=new EntityShadows();
    private final DynamicModelBuffer particles=new DynamicModelBuffer("RT quad particles");
    private final DynamicModelBuffer hands=new DynamicModelBuffer("RT held models");
    private final BlockEntityShadows blocks=new BlockEntityShadows();
    private GpuTexture cell;
    private GpuBuffer regions;
    record TextureRegion(GpuTextureView texture,float u,float v,float width,float height){}
    private GpuTextureView cellView;
    private int previousCount,sourceModels;
    private final LinkedHashMap<TextureRegion,Integer> textureSlots=new LinkedHashMap<>();
        private record ModelKey(Object owner,int feature,boolean hand){}
    private final Map<ModelKey,Integer> modelSlots=new LinkedHashMap<>();
    private final Set<Integer> dirtyTextureSlots=new HashSet<>();
    private final Map<TextureRegion,Long> textureVersions=new HashMap<>();
    private com.voxellight.rt.vulkan.VulkanRtBuffer textureDestination;
    private long textureDestinationOffset=-1;
    private Set<Integer> activeTextureSlots=Set.of();
    private Set<SectionKey> previousKeys=Set.of();
    private long bytes,textureCopyBytes;
    private int nextModelSlot;
    RtDynamicScene(){entities.rtCapture();blocks.rtCapture();}
    void prepare(CommandEncoder encoder,VulkanRtContext context,double x,double y,double z){
        long captureStart=System.nanoTime();
        entities.prepare();blocks.prepare();hands.begin();hands.owner("view-model",0,0,0);var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.player!=null&&mc.options.getCameraType().isFirstPerson()&&!mc.player.isSpectator()&&!mc.player.isSleeping()&&!mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden){
            var collection=new net.minecraft.client.renderer.SubmitNodeCollection(){
                @Override public <S> void submitModel(net.minecraft.client.model.Model<? super S> model,S state,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.rendertype.RenderType type,int light,int overlay,int tint,net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,int outline,net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumbling){hands.capture(model,state,pose,type,light,overlay,tint,sprite);}
                @Override public void submitItem(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.world.item.ItemDisplayContext display,int light,int overlay,int outline,int[] tints,List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil){hands.captureItem(pose,light,overlay,tints,quads);}
                @Override public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.rendertype.RenderType type,net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer){hands.captureCustom(pose,type,renderer);}
            };
            var collector=new net.minecraft.client.renderer.SubmitNodeStorage(){@Override public net.minecraft.client.renderer.SubmitNodeCollection order(int ignored){return collection;}};
            var pose=new com.mojang.blaze3d.vertex.PoseStack();var camera=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            pose.mulPose(new org.joml.Matrix4f(camera.viewRotationMatrix).invert());
            // Hands use the HUD FOV in vanilla. Compensate before their native transforms,
            // so tracing them through the world projection preserves that screen footprint.
            var scale=handProjectionScale(camera.projectionMatrix,camera.hudFov,(float)mc.getWindow().getWidth()/mc.getWindow().getHeight());
            pose.scale(scale.x,scale.y,1);
            try{mc.gameRenderer.itemInHandRenderer.submitHandsWithItems(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false),pose,collector,mc.player,15728880);}catch(RuntimeException error){hands.begin();org.slf4j.LoggerFactory.getLogger("VoxelLight").debug("RT first-person model capture deferred",error);}
        }
        hands.finish();particles.begin();particles.transientOwner("particles");for(var group:mc.gameRenderer.gameRenderState().levelRenderState.particlesRenderState.particles)if(group instanceof net.minecraft.client.renderer.state.level.QuadParticleRenderState quad)particles.captureParticles(quad);particles.finish();var models=new ArrayList<DynamicModelBuffer.RtModel>();models.addAll(hands.rtModels());models.addAll(entities.rtModels());models.addAll(blocks.rtModels());models.addAll(particles.rtModels());
        var textures=textureSlots;
        var modelRegions=models.stream().map(model->region(model.quads(),model.texture().textureView())).toList();
        var requiredRegions=new HashSet<>(modelRegions);textures.keySet().removeIf(key->!requiredRegions.contains(key));
        textureVersions.keySet().removeIf(key->!requiredRegions.contains(key));
        var frameTextureSlots=new HashSet<Integer>();
        var groups=new LinkedHashMap<SectionKey,RtGeometryStream.Section>();var changes=new ArrayList<RtGeometryStream.Section>();bytes=0;sourceModels=models.size();
        if(cell==null){var device=RenderSystem.getDevice();if(!device.precompilePipeline(COPY,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Dynamic RT atlas unavailable");cell=device.createTexture("VoxelLight dynamic RT texture resample",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC,GpuFormat.RGBA8_UNORM,CELL,CELL,1,1);cellView=device.createTextureView(cell);regions=device.createBuffer(()->"RT atlas crop regions",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,(long)SLOTS*256);}
        int handModels=hands.rtModels().size(),modelIndex=0;
        for(var model:models){
            boolean hand=modelIndex<handModels;
            var texture=modelRegions.get(modelIndex++);if(!textures.containsKey(texture)&&textures.size()>=SLOTS)continue;
            boolean newTexture=!textures.containsKey(texture);
            int slot=textures.computeIfAbsent(texture,ignored->freeSlot(textures.values()));
            long contentVersion=NativeTextureVersions.version(texture.texture().texture());
            Long previousVersion=textureVersions.put(texture,contentVersion);
            if(newTexture||!Objects.equals(previousVersion,contentVersion))dirtyTextureSlots.add(slot);
            double ox=hand||model.transientGeometry()?x:model.x(),oy=hand||model.transientGeometry()?y:model.y(),oz=hand||model.transientGeometry()?z:model.z();
            byte[] vertices=triangles(model.quads(),slot,x-ox,y-oy,z-oz,texture);
            if(bytes+vertices.length>8L*1024*1024)break;bytes+=vertices.length;
            // Crop tiles are per triangle material data, not BLAS identities.
            var modelKey=new ModelKey(model.owner(),model.feature(),hand);
            int modelSlot=modelSlots.computeIfAbsent(modelKey,ignored->nextModelSlot++);
            var key=new SectionKey(modelSlot,Integer.MIN_VALUE,hand?1:0);
            frameTextureSlots.add(slot);
            long hash=0xcbf29ce484222325L;for(byte b:vertices){hash^=b&255;hash*=0x100000001b3L;}
            groups.put(key,new RtGeometryStream.Section(key,hash,vertices,ox,oy,oz,hand,!model.transientGeometry()));
        }
        changes.addAll(groups.values());
        for(var key:previousKeys)if(!groups.containsKey(key))changes.add(new RtGeometryStream.Section(key,0,new byte[0]));
        modelSlots.entrySet().removeIf(entry->!groups.containsKey(new SectionKey(entry.getValue(),Integer.MIN_VALUE,entry.getKey().hand()?1:0)));
        previousKeys=Set.copyOf(groups.keySet());previousCount=groups.size();activeTextureSlots=Set.copyOf(frameTextureSlots);
        RenderPassProfile.cpu("vulkan_rt_dynamic_capture",System.nanoTime()-captureStart);
        context.prepareScene(encoder,changes,x,y,z);
    }
    void uploadTextures(CommandEncoder encoder,com.voxellight.rt.vulkan.VulkanRtBuffer assets,long offset){
        textureCopyBytes=0;if(cell==null)return;
        var activeSlots=activeTextureSlots;
        if(textureDestination!=assets||textureDestinationOffset!=offset){dirtyTextureSlots.addAll(activeSlots);textureDestination=assets;textureDestinationOffset=offset;}
        if(dirtyTextureSlots.stream().noneMatch(activeSlots::contains))return;
        var cropData=ByteBuffer.allocateDirect(SLOTS*256).order(ByteOrder.nativeOrder());
        for(var entry:textureSlots.entrySet()){var r=entry.getKey();cropData.position(entry.getValue()*256);cropData.putFloat(r.u()).putFloat(r.v()).putFloat(r.width()).putFloat(r.height());}
        cropData.position(0).limit(SLOTS*256);encoder.writeToBuffer(regions.slice(),cropData);
        try(var profile=RenderPassProfile.begin(encoder,"vulkan_rt_dynamic_textures")){
            for(var entry:textureSlots.entrySet())if(activeSlots.contains(entry.getValue())&&dirtyTextureSlots.contains(entry.getValue())){
                try(var pass=encoder.createRenderPass(RenderPassDescriptor.create(()->"VoxelLight dynamic RT texture copy").withRenderArea(new RenderPass.RenderArea(0,0,CELL,CELL)).withColorAttachment(cellView,Optional.empty()))){
                    pass.setPipeline(COPY);pass.setUniform("AtlasRegion",regions.slice((long)entry.getValue()*256,16));pass.bindTexture("Sampler0",entry.getKey().texture(),RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));pass.draw(3,1,0,0);
                }
                long size=(long)CELL*CELL*4;encoder.copyTextureToBuffer(cell,assets,offset+entry.getValue()*size,()->{},0);textureCopyBytes+=size;dirtyTextureSlots.remove(entry.getValue());
            }
        }
    }
    static byte[] triangles(byte[] quads,int slot,double x,double y,double z){
        return triangles(quads,slot,x,y,z,new TextureRegion(null,0,0,1,1));
    }
    static byte[] triangles(byte[] quads,int slot,double x,double y,double z,TextureRegion region){
        var input=ByteBuffer.wrap(quads).order(ByteOrder.nativeOrder());int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();
        var output=ByteBuffer.allocate(quads.length/stride/4*240).order(ByteOrder.nativeOrder());
        for(int q=0;q<quads.length/stride/4;q++)for(int v:new int[]{0,1,2,2,3,0}){
            int o=(q*4+v)*stride;output.putFloat((float)(input.getFloat(o)+x)).putFloat((float)(input.getFloat(o+4)+y)).putFloat((float)(input.getFloat(o+8)+z));
            output.putFloat((input.getFloat(o+16)-region.u())/region.width()).putFloat((input.getFloat(o+20)-region.v())/region.height());output.putFloat(0).putFloat(0).putFloat(0);
            output.putInt(input.getInt(o+12)).putInt(128|1|(slot<<20));
        }
        return output.array();
    }
    static SectionKey geometryKey(Map<GpuTextureView,Integer> groups,TextureRegion region,boolean hand){
        int group=groups.computeIfAbsent(region.texture(),ignored->freeSlot(groups.values()));
        return new SectionKey(group,Integer.MIN_VALUE,hand?1:0);
    }
    static int freeSlot(Collection<Integer> slots){
        var used=new boolean[SLOTS];for(int slot:slots)used[slot]=true;
        for(int slot=0;slot<SLOTS;slot++)if(!used[slot])return slot;
        throw new IllegalStateException("Dynamic RT texture slots exhausted");
    }
    static org.joml.Vector2f handProjectionScale(org.joml.Matrix4f projection,float hudFov,float aspect){
        float hudY=(float)(1.0/Math.tan(Math.toRadians(hudFov)*.5));
        float sx=Math.abs((hudY/aspect)/projection.m00()),sy=Math.abs(hudY/projection.m11());
        return Float.isFinite(sx)&&Float.isFinite(sy)&&sx>0&&sy>0?new org.joml.Vector2f(sx,sy):new org.joml.Vector2f(1,1);
    }
    static TextureRegion region(byte[] quads,GpuTextureView texture){
        if(texture!=null&&texture.getWidth(0)<=CELL&&texture.getHeight(0)<=CELL)return new TextureRegion(texture,0,0,1,1);
        int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();
        var data=ByteBuffer.wrap(quads).order(ByteOrder.nativeOrder());
        float u=1,v=1,maxU=0,maxV=0;
        for(int o=0;o<quads.length;o+=stride){u=Math.min(u,data.getFloat(o+16));v=Math.min(v,data.getFloat(o+20));maxU=Math.max(maxU,data.getFloat(o+16));maxV=Math.max(maxV,data.getFloat(o+20));}
        if(!(maxU>u&&maxV>v))return new TextureRegion(texture,0,0,1,1);
        return new TextureRegion(texture,u,v,maxU-u,maxV-v);
    }
    boolean hasHands(){return !hands.rtModels().isEmpty();}
    String status(){return ", rtDynamicModels="+sourceModels+", rtDynamicGroups="+previousCount+", rtDynamicTextureTiles="+activeTextureSlots.size()+", rtDynamicBytes="+bytes+", rtDynamicTextureCopyBytes="+textureCopyBytes+", rtDynamicCoverage=entity+block_entity+held+custom+cutout_quad_particles";}
    public void close(){entities.close();blocks.close();hands.close();particles.close();if(cellView!=null)cellView.close();if(cell!=null)cell.close();if(regions!=null)regions.close();regions=null;cellView=null;cell=null;previousCount=sourceModels=0;bytes=textureCopyBytes=0;previousKeys=Set.of();activeTextureSlots=Set.of();textureSlots.clear();textureVersions.clear();modelSlots.clear();dirtyTextureSlots.clear();textureDestination=null;textureDestinationOffset=-1;nextModelSlot=0;}
}
