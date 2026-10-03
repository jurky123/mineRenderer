package com.voxellight.adapter;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.voxellight.world.WaterShaders;
import com.voxellight.world.WaterOptics;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.*;
import static org.junit.jupiter.api.Assertions.*;

class WaterPipelineTest {
    @Test void activeNativeTerrainShadersLinkWithWaterBindingsAndUnchangedBlockFormat() throws Exception {
        var add=GlslCompiler.class.getDeclaredMethod("addToBindGroup",List.class,IntermediaryShaderModule.class,RenderPipeline.class);add.setAccessible(true);
        try(var loader=loader();var compiler=new GlslCompiler()) {
            var field=Class.forName("com.voxellight.adapter.WaterPass",true,loader).getDeclaredField("WATER");field.setAccessible(true);
            var pipeline=(RenderPipeline)field.get(null);
            assertEquals(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN.getVertexFormatBinding(0),pipeline.getVertexFormatBinding(0));
            assertEquals(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN.getDepthStencilState(),pipeline.getDepthStencilState());
            assertEquals(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN.getColorTargetState(),pipeline.getColorTargetState());
            String overlay=read(loader,"assets/voxellight/shaders/water_overlay.fsh"),visual=read(loader,"assets/voxellight/shaders/lighting_output.fsh");
            String vertex=WaterShaders.wrap(nativeShader(loader,"vsh"),true,overlay,visual),fragment=WaterShaders.wrap(nativeShader(loader,"fsh"),false,overlay,visual);
            try(var vs=compiler.createIntermediary("water.vsh",GlslPreprocessor.injectDefines(vertex,pipeline.getShaderDefines()),ShaderType.VERTEX);
                var fs=compiler.createIntermediary("water.fsh",GlslPreprocessor.injectDefines(fragment,pipeline.getShaderDefines()),ShaderType.FRAGMENT)) {
                var entries=new ArrayList<VulkanBindGroupLayout.Entry>();add.invoke(null,entries,vs,pipeline);add.invoke(null,entries,fs,pipeline);
                var inputs=new ArrayList<String>();for(var format:pipeline.getVertexFormatBindings())if(format!=null)format.getElements().forEach(e->inputs.add(e.name()));
                vs.rebind(inputs,entries);
                var outputs=new ArrayList<String>();for(Object item:vs.outputs()){var name=item.getClass().getDeclaredMethod("name");name.setAccessible(true);outputs.add((String)name.invoke(item));}
                assertTrue(outputs.contains("waterSkyAccess"));fs.rebind(outputs,entries);
                var samplers=new HashSet<String>();for(Object item:fs.samplers()){var name=item.getClass().getDeclaredMethod("name");name.setAccessible(true);samplers.add((String)name.invoke(item));}
                assertTrue(samplers.containsAll(List.of("Sampler0","WaterHdr","WaterDepth","WaterHzb","EmissiveBloom")));
                assertFalse(samplers.contains("SceneColor"));
                try(var stack=MemoryStack.stackPush()) {
                    var pointer=stack.callocPointer(1);assertEquals(0,Spvc.spvc_context_create(pointer));long context=pointer.get(0);
                    try {
                        assertEquals(0,Spvc.spvc_context_parse_spirv(context,fs.spirv().asIntBuffer(),fs.spirv().remaining()/4,pointer));long ir=pointer.get(0);
                        assertEquals(0,Spvc.spvc_context_create_compiler(context,0,ir,1,pointer));long reflection=pointer.get(0);
                        assertEquals(0,Spvc.spvc_compiler_create_shader_resources(reflection,pointer));long resources=pointer.get(0);var count=stack.callocPointer(1);
                        assertEquals(0,Spvc.spvc_resources_get_resource_list_for_type(resources,1,pointer,count));boolean found=false;
                        for(var uniform:SpvcReflectedResource.create(pointer.get(0),(int)count.get(0)))if(uniform.nameString().equals("WaterSettings")) {
                            long type=Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                            assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,type,pointer));assertEquals(WaterOptics.SETTINGS_BYTES,pointer.get(0));found=true;
                        }
                        assertTrue(found);
                    } finally {Spvc.spvc_context_destroy(context);}
                }
            }
        }
    }
    @Test void surfaceDerivativesPrecedeNativeDiscardAndPerPixelFallback() throws Exception {
        try(var loader=loader()) {
            String shader=read(loader,"assets/voxellight/shaders/water_overlay.fsh");
            String main=shader.substring(shader.indexOf("void main()"));
            int derivative=main.indexOf("dFdx(surface)");
            assertTrue(derivative>=0);
            assertTrue(main.indexOf("waveSlope(surface.xz")<main.indexOf("voxellightNativeMain();"),"Ripple footprint derivatives must precede native discard");
            assertTrue(derivative<main.indexOf("voxellightNativeMain();"),"Native alpha test may discard helper lanes");
            assertTrue(derivative<main.indexOf("if(!background("),"Per-pixel rejection must not make derivatives divergent");
            assertTrue(derivative<main.indexOf("if(!waterSprite("));
        }
    }
    @Test void backgroundStoreExplicitlyClearsTerrainButLoadsForEntityMerges() throws Exception {
        try(var loader=loader()) {
            var method=Class.forName("com.voxellight.adapter.WaterPass",true,loader).getDeclaredMethod("storeDescriptor",com.mojang.blaze3d.textures.GpuTextureView.class,int.class,int.class,boolean.class);method.setAccessible(true);
            for(boolean terrain:new boolean[]{true,false}) {
                var descriptor=(com.mojang.blaze3d.systems.RenderPassDescriptor)method.invoke(null,null,853,479,terrain);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,853,479),descriptor.renderArea);
                assertNull(descriptor.depthAttachment);
                assertEquals(terrain,descriptor.colorAttachments.getFirst().clearValue().isPresent());
            }
        }
    }
    @Test void waterControlsSurviveResourceCloseWithoutResettingTheWavePhase() throws Exception {
        try(var loader=loader()) {
            var type=Class.forName("com.voxellight.adapter.WaterPass",true,loader);
            var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);var water=ctor.newInstance();
            for(var option:List.of("setReflections","setWaves","setHzb")) {
                var method=type.getDeclaredMethod(option,boolean.class);method.setAccessible(true);method.invoke(water,false);
            }
            var strength=type.getDeclaredMethod("setWaveStrength",float.class);strength.setAccessible(true);strength.invoke(water,.2f);
            var speed=type.getDeclaredMethod("setWaveSpeed",float.class);speed.setAccessible(true);speed.invoke(water,0f);
            var quality=type.getDeclaredMethod("setQuality",com.voxellight.world.VisualQuality.class);quality.setAccessible(true);quality.invoke(water,com.voxellight.world.VisualQuality.HIGH);
            var phase=type.getDeclaredField("wavePhase");phase.setAccessible(true);phase.set(water,1.25);
            type.getMethod("close").invoke(water);
            for(String field:List.of("reflectionsEnabled","wavesEnabled","hzbEnabled")) {
                var value=type.getDeclaredField(field);value.setAccessible(true);assertEquals(false,value.get(water));
            }
            var value=type.getDeclaredField("waveStrength");value.setAccessible(true);assertEquals(.2f,value.get(water));
            value=type.getDeclaredField("waveSpeed");value.setAccessible(true);assertEquals(0f,value.get(water));
            value=type.getDeclaredField("quality");value.setAccessible(true);assertEquals(com.voxellight.world.VisualQuality.HIGH,value.get(water));
            assertEquals(1.25,phase.get(water));
        }
    }

    @Test void pyramidPassHasExplicitMipAreaAndWaterHzbOptionSurvivesReload() throws Exception {
        try(var loader=loader()) {
            var type=Class.forName("com.voxellight.adapter.DepthPyramid",true,loader);
            var descriptor=type.getDeclaredMethod("descriptor",com.mojang.blaze3d.textures.GpuTextureView.class,int.class,int.class);descriptor.setAccessible(true);
            for(int[] size:new int[][]{{427,240},{213,120},{1,1}}) {
                var pass=(com.mojang.blaze3d.systems.RenderPassDescriptor)descriptor.invoke(null,null,size[0],size[1]);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,size[0],size[1]),pass.renderArea);
                assertNull(pass.depthAttachment);assertEquals(1,pass.colorAttachments.size());
            }
            type=Class.forName("com.voxellight.adapter.WaterPass",true,loader);
            var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var water=constructor.newInstance();
            var method=type.getDeclaredMethod("setHzb",boolean.class);method.setAccessible(true);method.invoke(water,true);
            type.getMethod("close").invoke(water);
            var option=type.getDeclaredField("hzbEnabled");option.setAccessible(true);assertEquals(true,option.get(water));
        }
    }

    private static URLClassLoader loader() throws Exception {return new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("voxellight.modJar")).toUri().toURL()},WaterPipelineTest.class.getClassLoader());}
    private static String read(ClassLoader loader,String name) {
        try(var stream=loader.getResourceAsStream(name)){if(stream==null)throw new IllegalArgumentException("Missing "+name);return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    private static String nativeShader(ClassLoader loader,String extension) {
        var preprocessor=new GlslPreprocessor() {
            private final Set<String> included=new HashSet<>();
            @Override public String applyImport(boolean relative,String path) {
                if(!included.add(path))return null;
                String name=path.startsWith("minecraft:")?path.substring(10):path;
                return read(loader,"assets/minecraft/shaders/include/"+name);
            }
        };
        return String.join("",preprocessor.process(read(loader,"assets/minecraft/shaders/core/terrain."+extension)));
    }
}
