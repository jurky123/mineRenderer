package com.voxellight.adapter;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.voxellight.world.DynamicCasterSelection;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Bounded private native-model stream shared by entity and block-entity shadow owners. */
final class DynamicModelBuffer implements AutoCloseable {
    private record Draw(int baseVertex,int indices,PreparedRenderType.Texture texture,Object owner,int feature,double x,double y,double z,boolean transientGeometry) { }
    private Object owner;
    private int feature;
    private double ownerX,ownerY,ownerZ;
    private boolean transientGeometry=true;
    void owner(Object key,double x,double y,double z){owner=key;feature=0;ownerX=x;ownerY=y;ownerZ=z;transientGeometry=false;}
    void transientOwner(Object key){owner(key,0,0,0);transientGeometry=true;}
    record Checkpoint(int draws, int position,int feature) { }
    private final String label;
    private final List<Draw> draws = new ArrayList<>();
    private ByteBuffer frame;
    private ByteBufferBuilder scratch;
    private GpuBuffer vertices, indices;
    private IndexType indexType;
    private int bytes, maxIndices, skipped, modelAttempts;
    DynamicModelBuffer(String label) { this.label = label; }
    void begin() {
        draws.clear(); bytes=maxIndices=skipped=modelAttempts=0;owner=null;feature=0;ownerX=ownerY=ownerZ=0;transientGeometry=true;
        if (frame != null) frame.clear();
    }
    private void allocate() {
        if (frame == null) {
            frame=MemoryUtil.memAlloc(DynamicCasterSelection.FRAME_BYTES);
            scratch=new ByteBufferBuilder(4096,DynamicCasterSelection.MODEL_BYTES);
        }
    }
    Checkpoint checkpoint() { return new Checkpoint(draws.size(),frame==null ? 0 : frame.position(),feature); }
    void rollback(Checkpoint checkpoint) {
        draws.subList(checkpoint.draws(),draws.size()).clear();
        if(frame!=null) frame.position(checkpoint.position());feature=checkpoint.feature();
    }
    void finish() {
        bytes=frame==null ? 0 : frame.position();
        maxIndices=draws.stream().mapToInt(Draw::indices).max().orElse(0);
    }
    <S> void capture(Model<? super S> model, S state, PoseStack pose, RenderType type,
            int light, int overlay, int tint, TextureAtlasSprite sprite) {
        if (type.isOutline() || (type.hasBlending() && !(model instanceof PlayerModel)) || type.primitiveTopology() != PrimitiveTopology.QUADS) { skipped++; return; }
        if (++modelAttempts > DynamicCasterSelection.MAX_MODELS) { skipped++; return; }
        allocate();
        if (frame.remaining() < 4 * DefaultVertexFormat.BLOCK.getVertexSize()) { skipped++; return; }
        var texture = type.prepare().textures().stream().filter(binding -> binding.name().equals("Sampler0")).findFirst();
        if (texture.isEmpty()) { skipped++; return; }
        scratch.clear();
        try {
            try (var mesh = buildModel(scratch, model, state, pose, light, overlay, tint, sprite)) {
                if (mesh == null) return;
                append(mesh,texture.get());
            }
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Maximum capacity of ByteBufferBuilder")) skipped++;
            else throw error;
        } finally { scratch.clear(); }
    }

    void captureItem(PoseStack pose,int light,int overlay,int[] tints,List<net.minecraft.client.resources.model.geometry.BakedQuad> quads){
        for(var quad:quads){var type=quad.materialInfo().itemRenderType();
            captureCustom(pose,type,(current,consumer)->{var instance=new QuadInstance();int i=quad.materialInfo().tintIndex();instance.setColor(i>=0&&i<tints.length?tints[i]:-1);instance.setLightCoords(light);instance.setOverlayCoords(overlay);consumer.putBakedQuad(current,quad,instance);});
        }
    }
    void captureCustom(PoseStack pose,RenderType type,net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer){
        if(type.isOutline()||type.primitiveTopology()!=PrimitiveTopology.QUADS){skipped++;return;}
        var texture=type.prepare().textures().stream().filter(t->t.name().equals("Sampler0")).findFirst();if(texture.isEmpty())return;
        allocate();scratch.clear();try{var builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);renderer.render(pose.last(),builder);try(var mesh=builder.build()){if(mesh!=null)append(mesh,texture.get());}}finally{scratch.clear();}
    }
    void captureParticles(net.minecraft.client.renderer.state.level.QuadParticleRenderState state){
        allocate();
        for(var layer:state.layers()){
            scratch.clear();try{
                var builder=new BufferBuilder(scratch,PrimitiveTopology.QUADS,DefaultVertexFormat.BLOCK);
                VertexConsumer consumer=new VertexConsumer(){
                    public VertexConsumer addVertex(float x,float y,float z){builder.addVertex(x,y,z).setNormal(0,0,0);return this;}
                    public VertexConsumer setColor(int r,int g,int b,int a){builder.setColor(r,g,b,a);return this;}
                    public VertexConsumer setColor(int color){builder.setColor(color);return this;}
                    public VertexConsumer setUv(float u,float v){builder.setUv(u,v);return this;}
                    public VertexConsumer setUv1(int u,int v){return this;}
                    public VertexConsumer setUv2(int u,int v){builder.setUv2(u,v);return this;}
                    public VertexConsumer setNormal(float x,float y,float z){builder.setNormal(x,y,z);return this;}
                    public VertexConsumer setLineWidth(float width){return this;}
                };
                state.buildLayer(layer,consumer);try(var mesh=builder.build()){
                    if(mesh!=null){var view=net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(layer.textureAtlasLocation()).getTextureView();append(mesh,new PreparedRenderType.Texture("Sampler0",view,RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST)));}
                }
            }catch(IllegalArgumentException error){skipped++;}finally{scratch.clear();}
        }
    }
    boolean append(MeshData mesh,PreparedRenderType.Texture texture) {
        var data=mesh.vertexBuffer().duplicate();
        if(draws.size()>=DynamicCasterSelection.MAX_MODELS || data.remaining()>DynamicCasterSelection.FRAME_BYTES
                || mesh.drawState().vertexCount()%4!=0){skipped++;return false;}
        allocate();
        if(data.remaining()>frame.remaining()){skipped++;return false;}
        int base=frame.position()/DefaultVertexFormat.BLOCK.getVertexSize();
        frame.put(data);
        draws.add(new Draw(base,mesh.drawState().indexCount(),texture,owner,feature++,ownerX,ownerY,ownerZ,transientGeometry));
        return true;
    }

    /** Same native model-to-private-BLOCK conversion used by the live capture and CPU contract tests. */
    static <S> MeshData buildModel(ByteBufferBuilder scratch, Model<? super S> model, S state, PoseStack pose,
            int light, int overlay, int tint, TextureAtlasSprite sprite) {
        var builder = new BufferBuilder(scratch, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        VertexConsumer consumer = sprite == null ? builder : sprite.wrap(builder);
        model.setupAnim(state);
        model.renderToBuffer(pose, consumer, light, overlay, tint);
        return builder.build();
    }

    void upload(CommandEncoder encoder) {
        if (draws.isEmpty()) return;
        if (vertices == null) vertices = RenderSystem.getDevice().createBuffer(() -> "VoxelLight dynamic " + label + " vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, DynamicCasterSelection.FRAME_BYTES);
        encoder.writeToBuffer(vertices.slice(0, bytes), frame.duplicate().flip());

    }
    int maxIndices(){return maxIndices;}
    void borrowIndices(GpuBuffer indices,IndexType type){this.indices=indices;this.indexType=type;}
    boolean hasModels() { return !draws.isEmpty(); }
    void draw(RenderPass pass) {
        if (draws.isEmpty()) return;
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, indexType);
        for (var draw : draws) {
            pass.bindTexture("Sampler0", draw.texture().textureView(), draw.texture().sampler());
            pass.drawIndexed(draw.indices(), 1, 0, draw.baseVertex(), 0);
        }
    }
    record RtModel(byte[] quads,PreparedRenderType.Texture texture,Object owner,int feature,double x,double y,double z,boolean transientGeometry){}
    List<RtModel> rtModels(){
        if(frame==null)return List.of();var result=new ArrayList<RtModel>();int stride=DefaultVertexFormat.BLOCK.getVertexSize();
        for(var draw:draws){int count=draw.indices()/6*4;byte[] bytes=new byte[count*stride];var source=frame.duplicate();source.position(draw.baseVertex()*stride).limit((draw.baseVertex()+count)*stride);source.get(bytes);result.add(new RtModel(bytes,draw.texture(),draw.owner(),draw.feature(),draw.x(),draw.y(),draw.z(),draw.transientGeometry()));}
        return List.copyOf(result);
    }
    int modelCount() { return draws.size(); }
    int skipped() { return skipped; }
    int bytes() { return bytes; }
    int bufferBytes() { return vertices==null ? 0 : DynamicCasterSelection.FRAME_BYTES; }
    @Override public void close() {
        draws.clear(); indices=null;indexType=null;
        if(vertices!=null){vertices.close();vertices=null;}
        if(scratch!=null){scratch.close();scratch=null;}
        if(frame!=null){MemoryUtil.memFree(frame);frame=null;}
        bytes=maxIndices=skipped=modelAttempts=0;
    }
}
