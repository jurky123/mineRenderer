package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.DepthPyramidLayout;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Owned current-frame HZB. Only disjoint mip views are read/written in each native Vulkan pass. */
final class DepthPyramid implements AutoCloseable {
    static final RenderPipeline FIRST=pipeline(true),REDUCE=pipeline(false);
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuTextureView[] mips;
    private DepthPyramidLayout layout;
    private int width,height;
    private boolean ready,failed;
    boolean build(CommandEncoder encoder,GpuTextureView source,int w,int h) {
        ready=false;
        if(width!=w || height!=h){close();width=w;height=h;}
        if(failed)return false;
        var next=DepthPyramidLayout.of(w,h);
        if(next.bytes()>DepthPyramidLayout.LIMIT){release();return false;}
        try {
            var device=RenderSystem.getDevice();
            if(!device.precompilePipeline(FIRST,RenderProbe.SHADERS).isValid() || !device.precompilePipeline(REDUCE,RenderProbe.SHADERS).isValid())
                throw new IllegalStateException("Depth pyramid shader failed");
            if(texture==null) {
                layout=next;
                texture=device.createTexture("VoxelLight reversed-Z depth pyramid",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.R32_FLOAT,layout.width(),layout.height(),1,layout.levels());
                view=device.createTextureView(texture);
                mips=new GpuTextureView[layout.levels()];
                for(int i=0;i<mips.length;i++)mips[i]=device.createTextureView(texture,i,1);
            }
            var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try(var profile=RenderPassProfile.begin(encoder,"water_hzb")) {
                for(int i=0;i<mips.length;i++)try(var pass=encoder.createRenderPass(descriptor(mips[i],layout.width(i),layout.height(i)))) {
                    pass.setPipeline(i==0?FIRST:REDUCE);
                    pass.bindTexture("DepthSource",i==0?source:mips[i-1],nearest);
                    pass.draw(3,1,0,0);
                }
            }
            ready=true;return true;
        } catch(RuntimeException e) {
            release();failed=true;
            org.slf4j.LoggerFactory.getLogger("VoxelLight").warn("Depth pyramid disabled; linear water reflections retained",e);
            return false;
        }
    }
    GpuTextureView view(){return view;}
    int levels(){return ready?layout.levels():0;}
    long bytes(){return texture==null?0:layout.bytes();}
    boolean ready(){return ready;}
    static RenderPassDescriptor descriptor(GpuTextureView target,int width,int height) {
        return RenderPassDescriptor.create(()->"VoxelLight conservative depth reduction")
                .withRenderArea(new RenderPass.RenderArea(0,0,width,height)).withColorAttachment(target,Optional.empty());
    }
    private void release() {
        if(mips!=null){for(var mip:mips)if(mip!=null)mip.close();mips=null;}
        if(view!=null){view.close();view=null;}
        if(texture!=null){texture.close();texture=null;}layout=null;ready=false;
    }
    @Override public void close(){release();failed=false;width=height=0;}
    private static RenderPipeline pipeline(boolean first) {
        var builder=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight",first?"pipeline/depth_first":"pipeline/depth_reduce"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe")).withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","depth_reduce"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("DepthSource").build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.R32_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if(first)builder.withShaderDefine("FIRST_DEPTH");
        return builder.build();
    }
}
