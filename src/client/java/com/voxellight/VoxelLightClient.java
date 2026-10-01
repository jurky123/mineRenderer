package com.voxellight;

import net.fabricmc.api.ClientModInitializer;
import com.voxellight.adapter.RenderProbe;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class VoxelLightClient implements ClientModInitializer {
    private static final RenderProbe PROBE = new RenderProbe();

    public static RenderProbe probe() {
        return PROBE;
    }

    @Override
    public void onInitializeClient() {
        LoggerFactory.getLogger("VoxelLight").info("VoxelLight 26.2 rendering prototype loaded; diagnostics are off by default");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var command = literal("voxellight")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(PROBE.status()));
                        return 1;
                    })
                    .then(literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(PROBE.status()));
                        return 1;
                    }))
                    .then(literal("export").executes(context -> {
                        try {
                            var directory = FabricLoader.getInstance().getGameDir().resolve("benchmark-results/voxellight");
                            Files.createDirectories(directory);
                            var path = directory.resolve("probe-" + Instant.now().toEpochMilli() + ".csv");
                            PROBE.metrics().export(path);
                            Files.writeString(path.resolveSibling(path.getFileName() + ".txt"),
                                    "Minecraft=26.2\nVoxelLight=0.1.0\n" + PROBE.status() + "\n");
                            context.getSource().sendFeedback(Component.literal("VoxelLight: exported " + path.getFileName()
                                    + " to benchmark-results/voxellight"));
                            return 1;
                        } catch (IOException e) {
                            LoggerFactory.getLogger("VoxelLight").error("Benchmark export failed", e);
                            context.getSource().sendError(Component.literal("VoxelLight: export failed; see client log"));
                            return 0;
                        }
                    }));
            var modeCommand = literal("mode");
            for (var mode : RenderProbe.Mode.values()) {
                modeCommand.then(literal(mode.name().toLowerCase(java.util.Locale.ROOT)).executes(context -> {
                    PROBE.setMode(mode);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: " + mode));
                    return 1;
                }));
            }
            dispatcher.register(command.then(modeCommand));
        });
    }
}
