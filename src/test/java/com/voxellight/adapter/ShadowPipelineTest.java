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
            for (String field : List.of("CASTER", "ENTITY", "COMPOSITE", "MASK", "MAP")) {
                var pipeline = pipeline(loader, field);
                try (var vertex = compile(compiler, loader, pipeline.getVertexShader().getPath(), ShaderType.VERTEX);
                     var fragment = compile(compiler, loader, pipeline.getFragmentShader().getPath(), ShaderType.FRAGMENT)) {
                    var entries = new ArrayList<VulkanBindGroupLayout.Entry>();
                    addBindings.invoke(null, entries, vertex, pipeline);
                    addBindings.invoke(null, entries, fragment, pipeline);
                    var inputs = new ArrayList<String>();
                    for (var format : pipeline.getVertexFormatBindings()) {
                        if (format != null) format.getElements().forEach(element -> inputs.add(element.name()));
                    }
                    vertex.rebind(inputs, entries);
                    var outputs = new ArrayList<String>();
                    for (Object output : vertex.outputs()) {
                        var name = output.getClass().getDeclaredMethod("name");
                        name.setAccessible(true); // SpvVariable is a package-private game record.
                        outputs.add((String)name.invoke(output));
                    }
                    fragment.rebind(outputs, entries);
                    assertFalse(entries.isEmpty());
                    if (!field.equals("CASTER") && !field.equals("ENTITY")) assertNull(pipeline.getDepthStencilState(), "Resolve must not write the world/hand depth");
                }
            }
        }
    }

    private static URLClassLoader shippedLoader() throws Exception {
        return new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("voxellight.modJar")).toUri().toURL()}, ShadowPipelineTest.class.getClassLoader());
    }

    private static RenderPipeline pipeline(ClassLoader loader, String name) throws Exception {
        var field = Class.forName("com.voxellight.adapter.ShadowRenderer", true, loader).getDeclaredField(name);
        field.setAccessible(true);
        return (RenderPipeline) field.get(null);
    }

    private static IntermediaryShaderModule compile(GlslCompiler compiler, URLClassLoader loader, String name, ShaderType type) throws Exception {
        String path = "assets/voxellight/shaders/" + name + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
        var resource = loader.findResource(path);
        assertNotNull(resource, path);
        try (var stream = resource.openStream()) {
            return compiler.createIntermediary(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8), type);
        }
    }
}
