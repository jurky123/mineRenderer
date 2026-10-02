package com.voxellight.adapter;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class RenderingContractTest {
    @Test
    void partialRenderAreaDoesNotRescaleTheMinecraftVulkanViewport() throws Exception {
        var node = new ClassNode(Opcodes.ASM9);
        new ClassReader("com.mojang.blaze3d.vulkan.VulkanRenderPass").accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var constructor = node.methods.stream().filter(method -> method.name.equals("<init>")).findFirst().orElseThrow();
        int checked = 0;
        for (var instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("org/lwjgl/vulkan/VkViewport$Buffer")
                    && (call.name.equals("width") || call.name.equals("height"))) {
                var conversion = instruction.getPrevious();
                assertEquals(Opcodes.I2F, conversion.getOpcode());
                assertInstanceOf(VarInsnNode.class, conversion.getPrevious());
                var source = (VarInsnNode)conversion.getPrevious();
                assertEquals(Opcodes.ILOAD, source.getOpcode());
                assertEquals(call.name.equals("width") ? 6 : 7, source.var,
                        "Viewport must use full attachment dimensions, not a tile's RenderArea dimensions");
                checked++;
            }
        }
        assertEquals(2, checked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RenderProbe", "ShadowRenderer", "MaterialCapture"})
    void packagedDiagnosticDrawSubmitsGeometry(String className) throws Exception {
        // Check the shipped call, not a separately constructed test triangle.
        try (var jar = new ZipFile(System.getProperty("voxellight.modJar"));
             var stream = jar.getInputStream(jar.getEntry("com/voxellight/adapter/" + className + ".class"))) {
            var node = new ClassNode(Opcodes.ASM9);
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int draws = 0;
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals("com/mojang/blaze3d/systems/RenderPass")
                            && call.name.equals("draw") && call.desc.equals("(IIII)V")) {
                        draws++;
                        int[] arguments = new int[4];
                        var operand = instruction.getPrevious();
                        for (int i = 3; i >= 0; i--) {
                            while (operand.getOpcode() < 0) {
                                operand = operand.getPrevious();
                            }
                            arguments[i] = integerConstant(operand);
                            operand = operand.getPrevious();
                        }
                        assertTrue(arguments[0] >= 3, "Diagnostic draw must submit a triangle, not zero vertices");
                        assertTrue(arguments[1] > 0, "Diagnostic draw must submit at least one instance");
                        assertEquals(0, arguments[2], "Fullscreen shader starts at vertex zero");
                        assertEquals(0, arguments[3], "Diagnostic does not require nonzero-first-instance support");
                    }
                }
            }
            assertTrue(draws > 0, "Missing diagnostic draw");
        }
    }

    private static int integerConstant(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (instruction instanceof IntInsnNode value) {
            return value.operand;
        }
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Integer integer) {
            return integer;
        }
        throw new AssertionError("Expected explicit diagnostic draw count");
    }

    @Test
    void worldHookOccursOnceBeforeWorldDepthIsCleared() throws Exception {
        var reader = new ClassReader("net.minecraft.client.renderer.GameRenderer");
        var node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var renderLevel = node.methods.stream().filter(method -> method.name.equals("renderLevel")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (var instruction : renderLevel.instructions) {
            if (instruction instanceof MethodInsnNode method) {
                calls.add(method.owner + ";" + method.name + method.desc);
            }
        }
        String hook = "net/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V";
        assertEquals(1, calls.stream().filter(hook::equals).count());
        int clear = calls.indexOf("com/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V");
        assertTrue(clear > calls.indexOf(hook), "Diagnostic must run before hand/HUD replaces world depth");
        for (String lifecycle : List.of("resize", "resetData", "setLevel", "close")) {
            assertEquals(1, node.methods.stream().filter(method -> method.name.equals(lifecycle)).count());
        }
    }

    @Test
    void packagedShadersCompileWithMinecraftVulkanCompiler() throws Exception {
        try (var compiler = new GlslCompiler()) {
            for (String name : List.of("probe.vsh", "color.fsh", "depth.fsh", "normal.fsh", "shadow_caster.vsh", "shadow_caster.fsh", "shadow.fsh", "shadow_map.fsh")) {
                try (var stream = getClass().getResourceAsStream("/assets/voxellight/shaders/" + name)) {
                    assertNotNull(stream, name);
                    var module = compiler.createIntermediary(name, new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                            name.endsWith(".vsh") ? ShaderType.VERTEX : ShaderType.FRAGMENT);
                    assertNotNull(module);
                    module.close();
                }
            }
        }
    }
}
