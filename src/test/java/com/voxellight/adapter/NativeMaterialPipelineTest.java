package com.voxellight.adapter;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.*;
import com.voxellight.world.NativeMaterialShaders;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMaterialPipelineTest {
    @Test void solidAndCutoutNativeMrtCompileWithFiveOrderedOutputsAndEveryVertexAttribute() throws Exception {
        var add=GlslCompiler.class.getDeclaredMethod("addToBindGroup",List.class,IntermediaryShaderModule.class,RenderPipeline.class);add.setAccessible(true);
        try(var loader=loader();var compiler=new GlslCompiler()) {
            for(String variant:List.of("SOLID","CUTOUT")){
                var field=Class.forName("com.voxellight.adapter.NativeMaterialPass",true,loader).getDeclaredField(variant);field.setAccessible(true);
                var pipeline=(RenderPipeline)field.get(null);
                assertEquals(5,pipeline.getColorTargetStates().length);assertEquals(36,pipeline.getVertexFormatBinding(0).getVertexSize());
                assertEquals(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN.getDepthStencilState(),pipeline.getDepthStencilState());
                String vertex=NativeMaterialShaders.wrap(nativeShader(loader,"vsh"),true,read(loader,"assets/voxellight/shaders/native_material_capture.vsh"));
                String fragment=NativeMaterialShaders.wrap(nativeShader(loader,"fsh"),false,read(loader,"assets/voxellight/shaders/material_capture.fsh"));
                try(var vs=compiler.createIntermediary("mrt.vsh",GlslPreprocessor.injectDefines(vertex,pipeline.getShaderDefines()),ShaderType.VERTEX);
                    var fs=compiler.createIntermediary("mrt.fsh",GlslPreprocessor.injectDefines(fragment,pipeline.getShaderDefines()),ShaderType.FRAGMENT)){
                    var entries=new ArrayList<VulkanBindGroupLayout.Entry>();add.invoke(null,entries,vs,pipeline);add.invoke(null,entries,fs,pipeline);
                    var attributes=new ArrayList<String>();pipeline.getVertexFormatBinding(0).getElements().forEach(e->attributes.add(e.name()));vs.rebind(attributes,entries);
                    assertEquals(attributes.size(),vs.inputs().size());
                    var varyings=new ArrayList<String>();for(Object item:vs.outputs()){var name=item.getClass().getDeclaredMethod("name");name.setAccessible(true);varyings.add((String)name.invoke(item));}
                    fs.rebind(varyings,entries);
                    var outputs=new ArrayList<String>();for(Object item:fs.outputs()){var name=item.getClass().getDeclaredMethod("name");name.setAccessible(true);outputs.add((String)name.invoke(item));}
                    assertEquals(List.of("fragColor","outAlbedo","outNormal","outEmission","outMaterialPbr"),outputs);
                    assertFalse(fragment.contains("if ((flags & 1) != 0"));assertTrue(fragment.contains("vec4 texel=vlTexel"));
                    assertTrue(fragment.indexOf("vlMaterialMain();vlNativeMain();")>0,"Derivatives run before native alpha discard");
                }
            }
        }
    }
    @Test void nativeColorAndDepthLoadWhileOnlyMaterialAttachmentsClearAfterResize() throws Exception {
        try(var loader=loader()) {
            var factory=Class.forName("com.voxellight.adapter.NativeMaterialPass",true,loader).getDeclaredMethod("descriptor",com.mojang.blaze3d.textures.GpuTextureView.class,com.mojang.blaze3d.textures.GpuTextureView.class,com.mojang.blaze3d.textures.GpuTextureView[].class,int.class,int.class);factory.setAccessible(true);
            for(int[] size:new int[][]{{853,479},{2560,1440},{3840,2160}}){
                var descriptor=(com.mojang.blaze3d.systems.RenderPassDescriptor)factory.invoke(null,null,null,new com.mojang.blaze3d.textures.GpuTextureView[5],size[0],size[1]);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,size[0],size[1]),descriptor.renderArea);
                assertEquals(5,descriptor.colorAttachments.size());assertTrue(descriptor.colorAttachments.getFirst().clearValue().isEmpty());assertTrue(descriptor.depthAttachment.clearValue().isEmpty());
                for(int i=1;i<5;i++)assertEquals(new org.joml.Vector4f(0),descriptor.colorAttachments.get(i).clearValue().orElseThrow());
            }
        }
    }
    @Test void unexpectedNativeShaderContractRejectsBeforeNativeDrawing() {
        assertThrows(IllegalArgumentException.class,()->NativeMaterialShaders.wrap(null,true,""));
        assertThrows(IllegalArgumentException.class,()->NativeMaterialShaders.wrap("void main(){}",false,""));
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
