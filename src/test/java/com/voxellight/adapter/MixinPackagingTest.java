package com.voxellight.adapter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MixinPackagingTest {
    @Test
    void packagedMixinSelectorsAndShadowFieldsMatchPinnedMinecraft() throws Exception {
        try (var jar = new ZipFile(System.getProperty("voxellight.modJar"))) {
            var config = JsonParser.parseString(read(jar, "voxellight.client.mixins.json")).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var name : config.getAsJsonArray("client")) {
                var mixin = new ClassNode(Opcodes.ASM9);
                try (var stream = jar.getInputStream(jar.getEntry(prefix + name.getAsString() + ".class"))) {
                    new ClassReader(stream).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                var annotation = mixin.invisibleAnnotations.stream()
                        .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                var targets = (List<Type>) annotationValue(annotation, "value");
                assertEquals(1, targets.size());
                var target = new ClassNode(Opcodes.ASM9);
                new ClassReader(targets.getFirst().getInternalName()).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (var method : mixin.methods) {
                    if (method.visibleAnnotations == null) continue;
                    for (var inject : method.visibleAnnotations) {
                        if (!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                                && !inject.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")
                                && !inject.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;")) continue;
                        @SuppressWarnings("unchecked")
                        var selectors = (List<String>) annotationValue(inject, "method");
                        for (String selector : selectors) {
                            int descriptor = selector.indexOf('(');
                            String methodName = descriptor < 0 ? selector : selector.substring(0, descriptor);
                            assertEquals(1, target.methods.stream().filter(candidate -> candidate.name.equals(methodName)
                                            && (descriptor < 0 || candidate.desc.equals(selector.substring(descriptor)))).count(),
                                    mixin.name + " targets missing/ambiguous method " + selector);
                            if (inject.desc.endsWith("/WrapOperation;")) {
                                @SuppressWarnings("unchecked")
                                var locations = (List<AnnotationNode>)annotationValue(inject, "at");
                                assertEquals(1, locations.size());
                                var at = locations.getFirst();
                                String wrapped = (String)annotationValue(at, "target");
                                int ownerEnd = wrapped.indexOf(';'), signature = wrapped.indexOf('(');
                                int calls = 0;
                                for (var selected : target.methods) {
                                    if (!selected.name.equals(methodName)) continue;
                                    for (var instruction : selected.instructions) {
                                        if (instruction instanceof MethodInsnNode call
                                                && call.owner.equals(wrapped.substring(1, ownerEnd))
                                                && call.name.equals(wrapped.substring(ownerEnd + 1, signature))
                                                && call.desc.equals(wrapped.substring(signature))) calls++;
                                    }
                                }
                                assertEquals(1, calls, "The light scope must wrap exactly the pinned light-packet rebuild call");
                            }
                        }
                    }
                }
                for (var field : mixin.fields) {
                    if (field.visibleAnnotations != null && field.visibleAnnotations.stream()
                            .anyMatch(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) {
                        assertTrue(target.fields.stream().anyMatch(candidate -> candidate.name.equals(field.name)
                                && candidate.desc.equals(field.desc)), "Missing shadow field " + mixin.name + "." + field.name);
                    }
                }
            }
        }
    }

    @Test
    void nativeFormatFirstBuilderHookTargetsOnlyBlockLayout() throws Exception {
        var target=new ClassNode(Opcodes.ASM9);
        new ClassReader("com/mojang/blaze3d/vertex/DefaultVertexFormat").accept(target,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        var initializer=target.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        for(var instruction:initializer.instructions){
            if(instruction instanceof MethodInsnNode call && call.owner.equals("com/mojang/blaze3d/vertex/VertexFormat$Builder") && call.name.equals("build")){
                var next=instruction.getNext();while(next!=null && next.getOpcode()<0)next=next.getNext();
                var assignment=assertInstanceOf(org.objectweb.asm.tree.FieldInsnNode.class,next);
                assertEquals(Opcodes.PUTSTATIC,assignment.getOpcode());assertEquals("BLOCK",assignment.name);
                return;
            }
        }
        fail("Missing BLOCK format builder");
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("Missing annotation value " + key);
    }

    @Test
    void reservedMixinPackagesContainOnlyDeclaredMixins() throws Exception {
        try (var jar = new ZipFile(System.getProperty("voxellight.modJar"))) {
            var metadata = JsonParser.parseString(read(jar, "fabric.mod.json")).getAsJsonObject();
            for (var configName : metadata.getAsJsonArray("mixins")) {
                var config = JsonParser.parseString(read(jar, configName.getAsString())).getAsJsonObject();
                String prefix = config.get("package").getAsString().replace('.', '/') + "/";
                Set<String> declared = new HashSet<>();
                for (String section : Set.of("mixins", "client", "server")) {
                    if (config.has(section)) {
                        for (var name : config.getAsJsonArray(section)) {
                            declared.add(prefix + name.getAsString().replace('.', '/'));
                        }
                    }
                }
                for (String name : declared) {
                    assertNotNull(jar.getEntry(name + ".class"), "Missing declared mixin: " + name);
                }
                for (var entry : jar.stream().filter(entry -> entry.getName().startsWith(prefix)
                        && entry.getName().endsWith(".class")).toList()) {
                    String name = entry.getName().substring(0, entry.getName().length() - 6).split("\\$", 2)[0];
                    assertTrue(declared.contains(name),
                            "Ordinary class in reserved mixin package: " + entry.getName());
                }
            }
        }
    }

    private static String read(ZipFile jar, String entry) throws Exception {
        assertNotNull(jar.getEntry(entry), entry);
        try (var stream = jar.getInputStream(jar.getEntry(entry))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
