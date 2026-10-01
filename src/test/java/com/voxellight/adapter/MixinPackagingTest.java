package com.voxellight.adapter;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class MixinPackagingTest {
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
