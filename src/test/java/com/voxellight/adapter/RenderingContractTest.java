package com.voxellight.adapter;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderingContractTest {
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
            for (String name : List.of("probe.vsh", "color.fsh", "depth.fsh")) {
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
