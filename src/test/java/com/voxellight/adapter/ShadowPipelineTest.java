package com.voxellight.adapter;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.junit.jupiter.api.Test;
import com.voxellight.world.ShadowVolume;
import com.voxellight.world.LocalLightVolume;
import com.voxellight.world.ShadowCascades;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShadowPipelineTest {
    @Test
    void surfaceCorrectionFitsTheActualUploadedUniformBlock() throws Exception {
        try (var loader = shippedLoader(); var compiler = new GlslCompiler()) {
            for (var type : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
                try (var module = compile(compiler, loader, type == ShaderType.VERTEX ? "shadow_caster" : "shadow", type);
                     var stack = MemoryStack.stackPush()) {
                    var pointer = stack.callocPointer(1);
                    assertEquals(0, Spvc.spvc_context_create(pointer));
                    long context = pointer.get(0);
                    try {
                        assertEquals(0, Spvc.spvc_context_parse_spirv(context, module.spirv().asIntBuffer(), module.spirv().remaining() / 4, pointer));
                        long ir = pointer.get(0);
                        assertEquals(0, Spvc.spvc_context_create_compiler(context, 0, ir, 1, pointer));
                        long reflection = pointer.get(0);
                        assertEquals(0, Spvc.spvc_compiler_create_shader_resources(reflection, pointer));
                        long resources = pointer.get(0);
                        var count = stack.callocPointer(1);
                        assertEquals(0, Spvc.spvc_resources_get_resource_list_for_type(resources, 1, pointer, count));
                        var uniforms = SpvcReflectedResource.create(pointer.get(0), (int)count.get(0));
                        boolean found = false;
                        boolean foundLocal = type == ShaderType.VERTEX;
                        for (var uniform : uniforms) {
                            boolean local = uniform.nameString().equals("LocalLightSettings");
                            boolean resolve = uniform.nameString().equals("ShadowResolveSettings");
                            if (!local && !resolve && !uniform.nameString().equals("ShadowSettings")) continue;
                            if (local) foundLocal = true; else found = true;
                            long struct = Spvc.spvc_compiler_get_type_handle(reflection, uniform.base_type_id());
                            assertEquals(0, Spvc.spvc_compiler_get_declared_struct_size(reflection, struct, pointer));
                            assertEquals(local ? LocalLightVolume.SETTINGS_BYTES : resolve ? ShadowCascades.RESOLVE_BYTES : ShadowVolume.SETTINGS_BYTES, pointer.get(0));
                        }
                        assertTrue(found);
                        assertTrue(foundLocal, "Artificial light block must reflect the actual CPU upload size");
                    } finally {
                        Spvc.spvc_context_destroy(context);
                    }
                }
            }
        }
    }
    @Test
    void packagedCasterMatchesDepthClearAndWriteDisabledAttachment() throws Exception {
        try (var loader = shippedLoader()) {
            var caster = pipeline(loader, "CASTER");
            assertEquals(1, caster.getColorTargetStates().length, "26.2 builder declares a color slot even without withColorTargetState");
            assertEquals(GpuFormat.R8_UNORM, caster.getColorTargetState().format());
            assertEquals(ColorTargetState.WRITE_NONE, caster.getColorTargetState().writeMask());
            assertEquals(CompareOp.LESS_THAN_OR_EQUAL, caster.getDepthStencilState().depthTest());
            assertTrue(caster.getDepthStencilState().writeDepth());
            assertFalse(caster.isCull(), "Thin/cutout models need both sides in the reference pass");
            assertEquals(PrimitiveTopology.QUADS, caster.getPrimitiveTopology());
            assertEquals(28, caster.getVertexFormatBinding(0).getVertexSize(), "Match the real 26.2 BLOCK mesh stride");
            var position = caster.getVertexFormatBinding(0).getElement("Position");
            assertEquals(0, position.offset(), "Caster bounds read the shipped position attribute at byte zero");
            assertEquals(GpuFormat.RGB32_FLOAT, position.format(), "Bounds extraction must match the real uploaded float3 positions");
        }
    }

    @Test
    void shippedShadowStagesLinkToTheirActualPipelineBindingsWithoutAGpu() throws Exception {
        // Run the game's binding and stage-I/O rebinding before its device creation step.
        var addBindings = GlslCompiler.class.getDeclaredMethod("addToBindGroup", List.class, IntermediaryShaderModule.class, RenderPipeline.class);
        addBindings.setAccessible(true);
        try (var loader = shippedLoader(); var compiler = new GlslCompiler()) {
            for (String field : List.of("CASTER", "ENTITY", "COMPOSITE", "MASK", "MAP", "CAPTURE", "NATIVE_CAPTURE", "DISPLAY", "LIGHTING", "OUTPUT", "CULL", "NO_CULL", "LIGHTING_TEMPORAL", "TEMPORAL", "AO", "AO_FILTER", "BLOOM_EXTRACT", "BLOOM_BLUR", "WATER_STORE", "FIRST", "REDUCE", "VOLUMETRIC", "VOLUME_FILTER", "VOLUME_TEMPORAL", "MOTION", "WATER_MASK", "SURFACE_REFLECTION", "SURFACE_COMPOSITE", "COLOR_TEMPORAL", "PT_CAPTURE", "PT_COMPOSITE", "RTX_CAPTURE", "RTX_COMPOSITE", "RTX_ATLAS", "RTX_DIELECTRIC", "RTX_ENVIRONMENT", "RTX_REFERENCE_DISPLAY", "CLOUD")) {
                var pipeline = pipeline(loader, field);
                try (var vertex = compile(compiler, loader, pipeline.getVertexShader().getPath(), ShaderType.VERTEX,pipeline.getShaderDefines());
                     var fragment = compile(compiler, loader, pipeline.getFragmentShader().getPath(), ShaderType.FRAGMENT,pipeline.getShaderDefines())) {
                    var entries = new ArrayList<VulkanBindGroupLayout.Entry>();
                    addBindings.invoke(null, entries, vertex, pipeline);
                    addBindings.invoke(null, entries, fragment, pipeline);
                    var inputs = new ArrayList<String>();
                    for (var format : pipeline.getVertexFormatBindings()) {
                        if (format != null) format.getElements().forEach(element -> inputs.add(element.name()));
                    }
                    vertex.rebind(inputs, entries);
                    if (field.equals("CAPTURE") || field.equals("NATIVE_CAPTURE") || field.equals("CULL") || field.equals("NO_CULL")) {
                        int location = 0;
                        for (String input : inputs) {
                            Object reflected = null;
                            for (Object candidate : vertex.inputs()) {
                                var name = candidate.getClass().getDeclaredMethod("name"); name.setAccessible(true);
                                if (input.equals(name.invoke(candidate))) reflected = candidate;
                            }
                            assertNotNull(reflected, "Unused format attributes shift native Vulkan bindings: " + input);
                            var offset = reflected.getClass().getDeclaredMethod("locationOffset"); offset.setAccessible(true);
                            assertEquals(location++, vertex.spirv().asIntBuffer().get((int)offset.invoke(reflected)));
                        }
                    }
                    var outputs = new ArrayList<String>();
                    for (Object output : vertex.outputs()) {
                        var name = output.getClass().getDeclaredMethod("name");
                        name.setAccessible(true); // SpvVariable is a package-private game record.
                        outputs.add((String)name.invoke(output));
                    }
                    fragment.rebind(outputs, entries);
                    assertFalse(entries.isEmpty());
                    if (field.equals("CAPTURE") || field.equals("NATIVE_CAPTURE") || field.equals("CULL") || field.equals("NO_CULL")) {
                        var names = new ArrayList<String>();
                        for (Object output : fragment.outputs()) {
                            var name = output.getClass().getDeclaredMethod("name"); name.setAccessible(true);
                            names.add((String)name.invoke(output));
                        }
                        assertEquals(List.of("outAlbedo", "outNormal", "outEmission", "outMaterialPbr"), names,
                                "Native output rebinding order must match the actual MRT attachments");
                    }
                    if(field.equals("RTX_CAPTURE")){var names=new ArrayList<String>();for(Object output:fragment.outputs()){var name=output.getClass().getDeclaredMethod("name");name.setAccessible(true);names.add((String)name.invoke(output));}assertEquals(List.of("position","normal","albedo","material"),names,"RT guide attachments must retain native Vulkan reflection order");}
                    if(field.equals("MOTION")){
                        assertEquals(3,pipeline.getColorTargetStates().length);assertEquals(GpuFormat.RG16_FLOAT,pipeline.getColorTargetStates()[0].format());assertEquals(GpuFormat.R32_FLOAT,pipeline.getColorTargetStates()[1].format());assertEquals(GpuFormat.RGBA8_UNORM,pipeline.getColorTargetStates()[2].format());
                        var names=new ArrayList<String>();for(Object output:fragment.outputs()){var name=output.getClass().getDeclaredMethod("name");name.setAccessible(true);names.add((String)name.invoke(output));}assertEquals(List.of("velocity","depthGuide","normalGuide"),names);
                    }
                    if (field.equals("LIGHTING_TEMPORAL") || field.equals("TEMPORAL")) {
                        assertEquals(2,pipeline.getColorTargetStates().length);
                        var names = new ArrayList<String>();
                        for (Object output : fragment.outputs()) {
                            var name = output.getClass().getDeclaredMethod("name"); name.setAccessible(true);
                            names.add((String)name.invoke(output));
                        }
                        assertEquals(field.equals("TEMPORAL") ? List.of("resolvedHdr","nextHistory") : List.of("fragColor","shadowTemporalInput"),names);
                    }
                    if (!field.equals("CASTER") && !field.equals("ENTITY") && !field.equals("CAPTURE") && !field.equals("NATIVE_CAPTURE") && !field.equals("CULL") && !field.equals("NO_CULL") && !field.equals("WATER_MASK")) assertNull(pipeline.getDepthStencilState(), "Resolve must not write the world/hand depth");
                }
            }
        }
    }

    @Test
    void materialMrtHasFourExplicitTargetsAndUsesNormalsAndReversedDepth() throws Exception {
        try (var loader = shippedLoader()) {
            var capture = pipeline(loader, "CAPTURE");
            assertEquals(net.minecraft.client.renderer.RenderPipelines.CUTOUT_TERRAIN.isCull(),capture.isCull(),
                    "Native foliage contains opposing quads; capture must not draw extra coplanar backfaces");
            assertTrue(capture.isCull());
            assertEquals(4, capture.getColorTargetStates().length);
            assertEquals(GpuFormat.RGBA8_UNORM, capture.getColorTargetStates()[0].format());
            assertEquals(GpuFormat.RGBA8_UNORM, capture.getColorTargetStates()[1].format());
            assertEquals(GpuFormat.RGBA8_UNORM, capture.getColorTargetStates()[2].format());
            for (var target : capture.getColorTargetStates()) assertTrue(target.blendFunction().isEmpty());
            assertEquals(CompareOp.GREATER_THAN_OR_EQUAL, capture.getDepthStencilState().depthTest());
            assertTrue(capture.getDepthStencilState().writeDepth());
            assertNotNull(capture.getVertexFormatBinding(0).getElement("Normal"));
            assertNotNull(capture.getVertexFormatBinding(0).getElement("UV1"));
            assertEquals(36, capture.getVertexFormatBinding(0).getVertexSize());
            assertNull(pipeline(loader, "DISPLAY").getDepthStencilState());
        }
    }

    @Test
    void aoDescriptorsUseTheRoundedHalfSizeAndNeutralGuidesWithoutADepthAttachment() throws Exception {
        try(var loader=shippedLoader()) {
            var factory=Class.forName("com.voxellight.adapter.AmbientOcclusionPass",true,loader)
                    .getDeclaredMethod("descriptor",com.mojang.blaze3d.textures.GpuTextureView.class,int.class,int.class,String.class);
            factory.setAccessible(true);
            for(int[] full:new int[][]{{1,1},{853,479},{2560,1440},{3840,2160}}) {
                int width=com.voxellight.world.AmbientOcclusion.halfSize(full[0]),height=com.voxellight.world.AmbientOcclusion.halfSize(full[1]);
                var descriptor=(com.mojang.blaze3d.systems.RenderPassDescriptor)factory.invoke(null,null,width,height,"AO test");
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,width,height),descriptor.renderArea);
                assertNull(descriptor.depthAttachment,"AO must leave native and material depth untouched");
                assertEquals(1,descriptor.colorAttachments.size());
                assertEquals(new org.joml.Vector4f(1,.5f,.5f,-1),descriptor.colorAttachments.getFirst().clearValue().orElseThrow());
            }
            for(String field:List.of("AO","AO_FILTER")) {
                var pipeline=pipeline(loader,field);
                assertEquals(1,pipeline.getColorTargetStates().length);
                assertEquals(GpuFormat.RGBA16_FLOAT,pipeline.getColorTargetState().format());
                assertNull(pipeline.getDepthStencilState());
                assertEquals(PrimitiveTopology.TRIANGLES,pipeline.getPrimitiveTopology());
            }
        }
    }

    @Test
    void bloomPassUsesExplicitAreaAndIndependentTargetsWithoutDepthWrites() throws Exception {
        try(var loader=shippedLoader()) {
            var factory=Class.forName("com.voxellight.adapter.EmissiveBloom",true,loader)
                    .getDeclaredMethod("descriptor",com.mojang.blaze3d.textures.GpuTextureView.class,int.class,int.class);
            factory.setAccessible(true);
            for(int[] full:new int[][]{{2560,1440},{853,479},{3840,2160}}) {
                int width=com.voxellight.world.VisualPolish.quarterSize(full[0]),height=com.voxellight.world.VisualPolish.quarterSize(full[1]);
                var descriptor=(com.mojang.blaze3d.systems.RenderPassDescriptor)factory.invoke(null,null,width,height);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,width,height),descriptor.renderArea);
                assertNull(descriptor.depthAttachment);
                assertEquals(new org.joml.Vector4f(0),descriptor.colorAttachments.getFirst().clearValue().orElseThrow());
            }
            for(String name:List.of("BLOOM_EXTRACT","BLOOM_BLUR")) {
                var pipeline=pipeline(loader,name);
                assertNull(pipeline.getDepthStencilState());
                assertEquals(GpuFormat.RGBA16_FLOAT,pipeline.getColorTargetState().format());
                assertTrue(pipeline.getColorTargetState().blendFunction().isEmpty());
            }
        }
    }

    @Test
    void shippedMaterialDescriptorCoversTheFullTargetAfterResize() throws Exception {
        try (var loader = shippedLoader()) {
            var factory = Class.forName("com.voxellight.adapter.MaterialCapture", true, loader)
                    .getDeclaredMethod("captureDescriptor", com.mojang.blaze3d.textures.GpuTextureView[].class, int.class, int.class);
            factory.setAccessible(true);
            // Descriptor construction is CPU-only; GPU views are not dereferenced here.
            var views = new com.mojang.blaze3d.textures.GpuTextureView[5];
            for (int[] size : new int[][]{{1920,1080},{853,479},{2560,1440}}) {
                var descriptor = (com.mojang.blaze3d.systems.RenderPassDescriptor) factory.invoke(null,views,size[0],size[1]);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,size[0],size[1]),descriptor.renderArea);
                assertEquals(4,descriptor.colorAttachments.size());
                for (var attachment : descriptor.colorAttachments) {
                    assertEquals(new org.joml.Vector4f(0,0,0,0),attachment.clearValue().orElseThrow());
                }
                assertEquals(0.0,descriptor.depthAttachment.clearValue().orElseThrow());
            }
        }
    }

    @Test
    void materialCutoutSamplingMatchesPinnedNativeTerrain() throws Exception {
        try (var loader = shippedLoader();
             var nativeStream = getClass().getResourceAsStream("/assets/minecraft/shaders/core/terrain.fsh");
             var captureStream = loader.findResource("assets/voxellight/shaders/material_capture.fsh").openStream()) {
            assertNotNull(nativeStream);
            var nativeShader = new String(nativeStream.readAllBytes(), StandardCharsets.UTF_8);
            var captureShader = new String(captureStream.readAllBytes(), StandardCharsets.UTF_8);
            var sampling = nativeShader.substring(nativeShader.indexOf("vec4 sampleNearest"),nativeShader.indexOf("void main()"));
            assertTrue(captureShader.contains(sampling), "Alpha coverage must use the native nearest/RGSS sampling functions");
            assertTrue(captureShader.contains("UseRgss == 1 ? sampleRGSS"));
        }
    }

    @Test
    void foundationUsesHdrWithoutSamplingTheAlreadyLitScene() throws Exception {
        try (var loader = shippedLoader(); var compiler = new GlslCompiler()) {
            var lighting = pipeline(loader,"LIGHTING");
            assertEquals(GpuFormat.RGBA16_FLOAT,lighting.getColorTargetState().format());
            assertTrue(lighting.getColorTargetState().blendFunction().isEmpty());
            assertEquals(com.mojang.blaze3d.pipeline.BlendFunction.ENTITY_OUTLINE_BLIT,pipeline(loader,"OUTPUT").getColorTargetState().blendFunction().orElseThrow(),"Distance blend preserves native target alpha without a SceneColor copy");
            for (String name : List.of("LIGHTING","OUTPUT")) {
                var pipeline = pipeline(loader,name);
                assertNull(pipeline.getDepthStencilState());
                try (var module = compile(compiler,loader,pipeline.getFragmentShader().getPath(),ShaderType.FRAGMENT)) {
                    var samplers = new ArrayList<String>();
                    for (Object sampler : module.samplers()) {
                        var method = sampler.getClass().getDeclaredMethod("name");method.setAccessible(true);
                        samplers.add((String)method.invoke(sampler));
                    }
                    assertFalse(samplers.contains("SceneColor"),"Foundation must use actual materials, not relight native SceneColor");
                    assertTrue(samplers.contains("SceneDepth"));
                    assertTrue(samplers.contains(name.equals("LIGHTING") ? "MaterialNormal" : "LightingHdr"));
                }
            }
        }
    }

    @Test
    void volumetricTargetIsQuarterSizedNeutralAndDoesNotWriteWorldDepth() throws Exception {
        try(var loader=shippedLoader()) {
            var factory=Class.forName("com.voxellight.adapter.VolumetricPass",true,loader)
                    .getDeclaredMethod("descriptor",com.mojang.blaze3d.textures.GpuTextureView.class,int.class,int.class);
            factory.setAccessible(true);
            for(int[] full:new int[][]{{1,1},{853,479},{2560,1440},{3840,2160}}) {
                int w=com.voxellight.world.VisualPolish.quarterSize(full[0]),h=com.voxellight.world.VisualPolish.quarterSize(full[1]);
                var descriptor=(com.mojang.blaze3d.systems.RenderPassDescriptor)factory.invoke(null,null,w,h);
                assertEquals(new com.mojang.blaze3d.systems.RenderPass.RenderArea(0,0,w,h),descriptor.renderArea);
                assertNull(descriptor.depthAttachment);
            }
            assertEquals(GpuFormat.RGBA16_FLOAT,pipeline(loader,"VOLUMETRIC").getColorTargetState().format());
        }
    }

    @Test
    void foundationUniformsAndVisibilityKernelsMatchTheirProducers() throws Exception {
        try (var loader = shippedLoader(); var compiler = new GlslCompiler()) {
            for (String shader : List.of("lighting","lighting_output","temporal_shadow","ao","ao_filter","bloom_blur","volumetric","volumetric_filter")) {
                try (var module = compile(compiler,loader,shader,ShaderType.FRAGMENT);var stack = MemoryStack.stackPush()) {
                    var pointer = stack.callocPointer(1);
                    assertEquals(0,Spvc.spvc_context_create(pointer));long context = pointer.get(0);
                    try {
                        assertEquals(0,Spvc.spvc_context_parse_spirv(context,module.spirv().asIntBuffer(),module.spirv().remaining()/4,pointer));
                        long ir = pointer.get(0);
                        assertEquals(0,Spvc.spvc_context_create_compiler(context,0,ir,1,pointer));long reflection = pointer.get(0);
                        assertEquals(0,Spvc.spvc_compiler_create_shader_resources(reflection,pointer));long resources = pointer.get(0);
                        var count = stack.callocPointer(1);
                        assertEquals(0,Spvc.spvc_resources_get_resource_list_for_type(resources,1,pointer,count));
                        boolean found = false;
                        for (var uniform : SpvcReflectedResource.create(pointer.get(0),(int)count.get(0))) {
                            if(uniform.nameString().equals("ShadowResolveSettings")) {
                                long block=Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                                assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,block,pointer));
                                assertEquals(ShadowCascades.RESOLVE_BYTES,pointer.get(0),"All resolve consumers share the expanded CPU transform upload");
                                var offset=stack.callocInt(1);
                                assertEquals(0,Spvc.spvc_compiler_type_struct_member_offset(reflection,block,5,offset));
                                assertEquals(304,offset.get(0),"InvProjection follows the unchanged original fields");
                                assertEquals(0,Spvc.spvc_compiler_type_struct_member_offset(reflection,block,6,offset));
                                assertEquals(368,offset.get(0),"Normal matrix array matches CPU std140 packing");
                                for(int member=7;member<=11;member++) {
                                    assertEquals(0,Spvc.spvc_compiler_type_struct_member_offset(reflection,block,member,offset));
                                    assertEquals(560+(member-7)*192,offset.get(0),"Epoch matrices and blend controls must match CPU packing");
                                }
                            }
                            if(uniform.nameString().equals("AtmosphereSettings") || uniform.nameString().equals("LightingEnvironment")) {
                                long block=Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                                assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,block,pointer));
                                assertEquals(uniform.nameString().equals("AtmosphereSettings") ? com.voxellight.world.Atmosphere.SETTINGS_BYTES : com.voxellight.world.LightingEnvironment.SETTINGS_BYTES,pointer.get(0));
                            }
                            if(uniform.nameString().equals("VisualSettings")) {
                                long block=Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                                assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,block,pointer));
                                assertEquals(com.voxellight.world.VisualPolish.SETTINGS_BYTES,pointer.get(0));
                                var offset=stack.callocInt(1);
                                assertEquals(0,Spvc.spvc_compiler_type_struct_member_offset(reflection,block,1,offset));
                                assertEquals(16,offset.get(0));
                            }
                            if(uniform.nameString().equals("AoSettings")) {
                                long block=Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                                assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,block,pointer));
                                assertEquals(com.voxellight.world.AmbientOcclusion.SETTINGS_BYTES,pointer.get(0));
                                var offset=stack.callocInt(1);
                                assertEquals(0,Spvc.spvc_compiler_type_struct_member_offset(reflection,block,1,offset));
                                assertEquals(16,offset.get(0),"Filter/enabled/debug flags follow the uploaded parameters");
                            }
                            String expected = shader.equals("volumetric_filter") ? "VolumeFilterSettings" : shader.equals("volumetric") ? "VolumetricSettings" : shader.equals("bloom_blur") ? "BloomSettings" : shader.equals("lighting") ? "LightingEnvironment" : shader.equals("temporal_shadow") ? "TemporalSettings" : shader.startsWith("ao") ? "AoSettings" : "Fog";
                            if (!uniform.nameString().equals(expected)) continue;
                            long struct = Spvc.spvc_compiler_get_type_handle(reflection,uniform.base_type_id());
                            assertEquals(0,Spvc.spvc_compiler_get_declared_struct_size(reflection,struct,pointer));
                            assertEquals(shader.equals("volumetric_filter") ? 16 : shader.equals("volumetric") ? com.voxellight.world.VolumetricLight.SETTINGS_BYTES : shader.equals("bloom_blur") ? 16 : shader.equals("lighting") ? com.voxellight.world.LightingEnvironment.SETTINGS_BYTES : shader.equals("temporal_shadow") ? com.voxellight.world.TemporalShadowState.SETTINGS_BYTES : shader.startsWith("ao") ? com.voxellight.world.AmbientOcclusion.SETTINGS_BYTES : 40,pointer.get(0));
                            found = true;
                        }
                        assertTrue(found);
                    } finally { Spvc.spvc_context_destroy(context); }
                }
            }
            try (var legacyStream = loader.findResource("assets/voxellight/shaders/shadow.fsh").openStream();
                 var foundationStream = loader.findResource("assets/voxellight/shaders/lighting.fsh").openStream()) {
                var legacy = new String(legacyStream.readAllBytes(),StandardCharsets.UTF_8);
                var foundation = new String(foundationStream.readAllBytes(),StandardCharsets.UTF_8);
                String visibility = legacy.substring(legacy.indexOf("bool intersectsBox"),legacy.indexOf("float planeError"));
                assertTrue(foundation.contains(visibility),"Use the same accepted caster PCF/DDA visibility during lighting migration");
                assertFalse(foundation.contains("planeError"),"Foundation uses real material normals");
            }
        }
    }

    @Test
    void shippedFoliageLightingDoesNotFlipNormalsWithTheView() throws Exception {
        try (var loader = shippedLoader();
             var stream = loader.findResource("assets/voxellight/shaders/lighting.fsh").openStream()) {
            var shader = new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(shader.contains("dot(normal, position)"),
                    "Camera-facing normal flips caused plant brightness/ray-origin discontinuities");
            assertTrue(shader.contains("foliage ? abs(directFacing)"));
            assertTrue(shader.contains("foliage ? abs(lightFacing)"));
            assertTrue(shader.contains("foliage && lightFacing < 0.0 ? -normal : normal"));
        }
    }

    private static URLClassLoader shippedLoader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("voxellight.modJar")).toUri().toURL()}, ShadowPipelineTest.class.getClassLoader());
    }

    private static RenderPipeline pipeline(ClassLoader loader, String name) throws Exception {
        if(name.startsWith("RTX_")||name.equals("CLOUD")){var field=Class.forName("com.voxellight.adapter."+(name.equals("CLOUD")?"VoxelCloudPass":"RtxLightingPass"),true,loader).getDeclaredField(name.equals("CLOUD")?name:name.substring(4));field.setAccessible(true);return (RenderPipeline)field.get(null);}
        var field = Class.forName("com.voxellight.adapter." + (name.startsWith("PT_") ? "PathTracePass" : (name.equals("FIRST") || name.equals("REDUCE")) ? "DepthPyramid" : (name.equals("VOLUMETRIC") || name.equals("VOLUME_FILTER") || name.equals("VOLUME_TEMPORAL")) ? "VolumetricPass" : name.equals("WATER_MASK") ? "WaterSurfaceCapture" : name.equals("MOTION") ? "MotionFrame" : name.startsWith("SURFACE_") || name.equals("COLOR_TEMPORAL") ? "SurfaceEffects" : name.equals("OUTPUT") ? "VisualComposite" : name.equals("WATER_STORE") ? "WaterPass" : name.startsWith("BLOOM_") ? "EmissiveBloom" : name.equals("AO") || name.equals("AO_FILTER") ? "AmbientOcclusionPass" : name.equals("TEMPORAL") ? "TemporalShadowHistory" : name.equals("CULL") || name.equals("NO_CULL") ? "EntityMaterials" : name.equals("LIGHTING") || name.equals("LIGHTING_TEMPORAL") || name.equals("OUTPUT") ? "LightingResolvePass" : name.equals("CAPTURE") || name.equals("NATIVE_CAPTURE") || name.equals("DISPLAY") ? "MaterialCapture" : "ShadowRenderer"), true, loader).getDeclaredField(name);
        field.setAccessible(true);
        return (RenderPipeline) field.get(null);
    }

    private static IntermediaryShaderModule compile(GlslCompiler compiler, URLClassLoader loader, String name, ShaderType type) throws Exception {
        return compile(compiler,loader,name,type,net.minecraft.client.renderer.ShaderDefines.EMPTY);
    }
    private static IntermediaryShaderModule compile(GlslCompiler compiler,URLClassLoader loader,String name,ShaderType type,net.minecraft.client.renderer.ShaderDefines defines) throws Exception {
        String path = "assets/voxellight/shaders/" + name + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
        var resource = loader.findResource(path);
        assertNotNull(resource, path);
        try (var stream = resource.openStream()) {
            return compiler.createIntermediary(path, com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(new String(stream.readAllBytes(), StandardCharsets.UTF_8),defines), type);
        }
    }
}
