package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import com.voxellight.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.Optional;

/** Native translucent terrain replacement with a bounded pre-tone HDR background. No duplicate water mesh. */
final class WaterPass implements AutoCloseable {
    static final RenderPipeline WATER=RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/water"))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","water_native"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","water_native"))
            .withShaderDefine("ALPHA_CUTOUT",.1f)
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("WaterHdr").withSampler("WaterDepth").withSampler("EmissiveBloom")
                    .withUniform("WaterSettings",UniformType.UNIFORM_BUFFER).withUniform("VisualSettings",UniformType.UNIFORM_BUFFER)
                    .withUniform("AtmosphereSettings",UniformType.UNIFORM_BUFFER).withUniform("LightingEnvironment",UniformType.UNIFORM_BUFFER)
                    .withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
    static final RenderPipeline WATER_STORE=RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxellight","pipeline/water_store"))
            .withVertexShader(Identifier.fromNamespaceAndPath("voxellight","probe"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("voxellight","water_store"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("InputHdr").withSampler("SceneDepth")
                    .withUniform("Projection",UniformType.UNIFORM_BUFFER).withUniform("ShadowResolveSettings",UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    private GpuTexture hdr,depth;
    private GpuTextureView hdrView,depthView;
    private GpuBuffer settings,visual,atmosphere,environment;
    private ShadowRenderer shadows;
    private EmissiveBloom bloom;
    private boolean captured,ready,failed;
    private String state="waiting";
    private int width,height;
    private boolean eligible() {
        var mc=Minecraft.getInstance();
        return mc.level!=null && mc.levelRenderer.translucentTarget()==null && mc.gameRenderer.mainCamera().getFluidInCamera()==FogType.NONE
                && mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState.skybox==DimensionType.Skybox.OVERWORLD;
    }
    void capture(CommandEncoder encoder,RenderTarget output,GpuTextureView source,ShadowRenderer shadows,boolean terrain,boolean enabled) {
        if(terrain){captured=ready=false;}
        if(!enabled || !eligible() || WaterOptics.targetBytes(output.width,output.height)>WaterOptics.TARGET_LIMIT) {
            close();state="off/medium/budget fallback";return;
        }
        if(failed)return;
        try {
            var device=RenderSystem.getDevice();
            if(!device.precompilePipeline(WATER_STORE,RenderProbe.SHADERS).isValid())throw new IllegalStateException("Water HDR background shader failed");
            if(hdr==null || width!=output.width || height!=output.height) {
                close();width=output.width;height=output.height;
                hdr=device.createTexture("VoxelLight water HDR background",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.RGBA16_FLOAT,width,height,1,1);
                hdrView=device.createTextureView(hdr);
                depth=device.createTexture("VoxelLight immutable water background depth",GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,GpuFormat.D32_FLOAT,width,height,1,1);
                depthView=device.createTextureView(depth);
                settings=device.createBuffer(()->"VoxelLight water settings",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,WaterOptics.SETTINGS_BYTES);
            }
            if(!terrain && !captured)return;
            try(var pass=encoder.createRenderPass(storeDescriptor(hdrView,width,height,terrain))) {
                pass.setPipeline(WATER_STORE);
                var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.bindTexture("InputHdr",source,nearest);pass.bindTexture("SceneDepth",output.getDepthTextureView(),nearest);
                shadows.bindTransform(pass);pass.draw(3,1,0,0);
            }
            captured=true;state="HDR captured; waiting for translucent terrain";
        } catch(RuntimeException e){fail(e);}
    }
    void prepareTranslucent(RenderTarget target,ShadowRenderer shadows,GpuBuffer visual,GpuBuffer atmosphere,GpuBuffer environment,EmissiveBloom bloom,boolean enabled) {
        ready=false;
        if(!enabled || !captured || failed || !eligible())return;
        var mc=Minecraft.getInstance();
        // Dedicated Fabulous translucency targets use a different final composition contract.
        if(target!=mc.gameRenderer.mainRenderTarget() || target.width!=width || target.height!=height) {state="native separate translucency target retained";return;}
        try {
            if(!RenderSystem.getDevice().precompilePipeline(WATER,WaterPass::shaderSource).isValid())throw new IllegalStateException("Native water shader failed");
            var encoder=RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(target.getDepthTexture(),depth,0,0,0,0,0,width,height);
            var atlas=(TextureAtlas)mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            var still=atlas.getSprite(Identifier.withDefaultNamespace("block/water_still"));
            var flow=atlas.getSprite(Identifier.withDefaultNamespace("block/water_flow"));
            if(still==atlas.missingSprite() || flow==atlas.missingSprite()){state="missing water sprite; native retained";return;}
            var camera=mc.gameRenderer.mainCamera().position();
            try(var stack=MemoryStack.stackPush()) {
                encoder.writeToBuffer(settings.slice(),Std140Builder.onStack(stack,WaterOptics.SETTINGS_BYTES)
                        .putVec4(still.getU0(),still.getV0(),still.getU1(),still.getV1())
                        .putVec4(flow.getU0(),flow.getV0(),flow.getU1(),flow.getV1())
                        .putVec4((float)(camera.x%64),(float)(camera.y%64),(float)(camera.z%64),0)
                        .putVec4(24,32,0,0).get());
            }
            this.shadows=shadows;this.visual=visual;this.atmosphere=atmosphere;this.environment=environment;this.bloom=bloom;
            ready=true;state="native-stream HDR water active";
        } catch(RuntimeException e){fail(e);}
    }
    boolean bind(RenderPass pass) {
        if(!ready)return false;
        pass.setPipeline(WATER);
        var nearest=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.bindTexture("WaterHdr",hdrView,nearest);pass.bindTexture("WaterDepth",depthView,nearest);bloom.bind(pass);
        pass.setUniform("WaterSettings",settings);pass.setUniform("VisualSettings",visual);pass.setUniform("AtmosphereSettings",atmosphere);pass.setUniform("LightingEnvironment",environment);
        shadows.bindTransform(pass);return true;
    }
    static String shaderSource(Identifier id,ShaderType type) {
        var nativeSource=Minecraft.getInstance().getShaderManager().getShader(Identifier.withDefaultNamespace("core/terrain"),type);
        return WaterShaders.wrap(nativeSource,type==ShaderType.VERTEX,
                RenderProbe.SHADERS.get(Identifier.fromNamespaceAndPath("voxellight","water_overlay"),ShaderType.FRAGMENT),
                RenderProbe.SHADERS.get(Identifier.fromNamespaceAndPath("voxellight","lighting_output"),ShaderType.FRAGMENT));
    }
    static RenderPassDescriptor storeDescriptor(GpuTextureView target,int width,int height,boolean clear) {
        return RenderPassDescriptor.create(()->"VoxelLight pre-tone water background").withRenderArea(new RenderPass.RenderArea(0,0,width,height))
                .withColorAttachment(target,clear?Optional.of(new Vector4f(0)):Optional.empty());
    }
    void endFrame(){captured=ready=false;}
    String status(){return ", water="+state+", waterBytes="+(hdr==null?0:WaterOptics.targetBytes(width,height));}
    private void fail(RuntimeException error){close();failed=true;state="failed; native water retained";org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Water reference disabled; native terrain retained",error);}
    @Override public void close() {
        if(hdrView!=null){hdrView.close();hdrView=null;}if(hdr!=null){hdr.close();hdr=null;}
        if(depthView!=null){depthView.close();depthView=null;}if(depth!=null){depth.close();depth=null;}
        if(settings!=null){settings.close();settings=null;}width=height=0;captured=ready=failed=false;
        shadows=null;visual=atmosphere=environment=null;bloom=null;state="off/waiting";
    }
}
