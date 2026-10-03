package com.voxellight;

import net.fabricmc.api.ClientModInitializer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.voxellight.adapter.RenderProbe;
import com.voxellight.adapter.ClientScene;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;

public final class VoxelLightClient implements ClientModInitializer {
    private static final RenderProbe PROBE = new RenderProbe();
    private static final ClientScene SCENE = new ClientScene();

    public static RenderProbe probe() {
        return PROBE;
    }

    public static ClientScene scene() {
        return SCENE;
    }

    @Override
    public void onInitializeClient() {
        com.voxellight.adapter.NativeTerrainAttributes.verifyWriter();
        com.voxellight.adapter.IndigoMaterials.verifyWriter();
        LoggerFactory.getLogger("VoxelLight").info("Native terrain material writer verified: 36-byte stride, vanilla + Indigo emission");
        LoggerFactory.getLogger("VoxelLight").info("VoxelLight 26.2 reference lighting prototype loaded; rendering effects are off by default");
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> { RenderProbe.invalidateCasterAdmission(); SCENE.chunkChanged(level, chunk.getPos().x(), chunk.getPos().z(), false); });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> { RenderProbe.invalidateCasterAdmission(); SCENE.chunkChanged(level, chunk.getPos().x(), chunk.getPos().z(), true); });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> SCENE.changeLevel(level));
        ClientTickEvents.END_CLIENT_TICK.register(SCENE::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SCENE.close());
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
                    .then(literal("profile").then(literal("on").executes(context -> {
                        com.voxellight.adapter.RenderPassProfile.setEnabled(true);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: per-pass profiling on; export after the comparison"));return 1;
                    })).then(literal("off").executes(context -> {
                        com.voxellight.adapter.RenderPassProfile.setEnabled(false);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: per-pass profiling off"));return 1;
                    })))
                    .then(literal("export").executes(context -> {
                        try {
                            var directory = FabricLoader.getInstance().getGameDir().resolve("benchmark-results/voxellight");
                            Files.createDirectories(directory);
                            var path = directory.resolve("probe-" + Instant.now().toEpochMilli() + ".csv");
                            PROBE.metrics().export(path);
                            com.voxellight.adapter.RenderPassProfile.export(path.resolveSibling(path.getFileName()+".passes.csv"));
                            SCENE.export(path.resolveSibling(path.getFileName() + ".scene.csv"));
                            Files.writeString(path.resolveSibling(path.getFileName() + ".txt"),
                                    "Minecraft=26.2\nVoxelLight=" + FabricLoader.getInstance().getModContainer("voxellight")
                                            .orElseThrow().getMetadata().getVersion().getFriendlyString() + "\n" + PROBE.status()
                                            + "\n" + SCENE.status() + "\n");
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
                    context.getSource().sendFeedback(Component.literal("VoxelLight: " + mode + (mode.isShadow() ? "; preparing nearby shadows" : "")));
                    return 1;
                }));
            }
            var sceneCommand = literal("scene")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(SCENE.status()));
                        return 1;
                    })
                    .then(literal("on").executes(context -> {
                        SCENE.setEnabled(true);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: scene tracking on"));
                        return 1;
                    }))
                    .then(literal("off").executes(context -> {
                        SCENE.setEnabled(false);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: scene tracking off"));
                        return 1;
                    }))
                    .then(literal("inspect").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(SCENE.inspect(context.getSource().getClient())));
                        return 1;
                    }));
            var cacheCommand = literal("shadow_cache");
            for (boolean enabled : new boolean[]{true, false}) {
                cacheCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setShadowCache(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: shadow cache " + (enabled ? "on" : "off (reference redraw)")));
                    return 1;
                }));
            }
            var epochCommand=literal("shadow_epochs");
            for(boolean enabled:new boolean[]{true,false})epochCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setShadowEpochs(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: "+(enabled?"smooth dual-angle terrain cache":"continuous-angle reference shadows")));return 1;
            }));
            var sunCommand = literal("sun");
            for (boolean worldSun : new boolean[]{true, false}) {
                sunCommand.then(literal(worldSun ? "world" : "fixed").executes(context -> {
                    PROBE.setWorldSun(worldSun);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: " + (worldSun ? "world sun/moon" : "fixed reference light")));
                    return 1;
                }));
            }
            var localCommand = literal("local_lights");
            for (boolean enabled : new boolean[]{true, false}) {
                localCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setLocalLights(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: artificial lights " + (enabled ? "on" : "off")));
                    return 1;
                }));
            }
            var heldCommand=literal("held_lights");
            for(boolean enabled:new boolean[]{true,false})heldCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setHeldLights(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: held emissive-block light "+enabled));return 1;
            }));
            var aoCommand=literal("ao");
            for(String choice:new String[]{"on","off","view"}) {
                aoCommand.then(literal(choice).executes(context -> {
                    if(choice.equals("view"))PROBE.setMode(RenderProbe.Mode.FOUNDATION);
                    PROBE.setAmbientOcclusion(!choice.equals("off"),choice.equals("view"));
                    context.getSource().sendFeedback(Component.literal("VoxelLight: terrain AO "+choice+" (foundation)"));
                    return 1;
                }));
            }
            var lookCommand=literal("look");
            for(String choice:new String[]{"polished","reference"})lookCommand.then(literal(choice).executes(context->{
                PROBE.setPolished(choice.equals("polished"));
                context.getSource().sendFeedback(Component.literal("VoxelLight: "+choice+" lighting (foundation)"));return 1;
            }));
            var bloomCommand=literal("bloom");
            var coverageCommand=literal("coverage_blend");
            for(boolean enabled:new boolean[]{true,false}) {
                bloomCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setBloom(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: emissive bloom "+enabled));return 1;}));
                coverageCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setCoverageBlend(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: material distance blend "+enabled));return 1;}));
            }
            var waterCommand=literal("water");
            for(boolean enabled:new boolean[]{true,false})waterCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setWater(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: HDR water "+enabled+" (foundation)"));return 1;
            }));
            var reflectionCommand=literal("water_reflections");
            var wavesCommand=literal("water_waves");
            var filterCommand=literal("volumetric_filter");
            for(boolean enabled:new boolean[]{true,false}) {
                reflectionCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setWaterReflections(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: water reflections "+enabled));return 1;}));
                wavesCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setWaterWaves(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: water waves "+enabled));return 1;}));
                filterCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setVolumeFilter(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: volumetric spatial filter "+enabled));return 1;}));
            }
            var waveStrengthCommand=literal("water_wave_strength").then(argument("strength",FloatArgumentType.floatArg(0,.3f)).executes(context->{
                float value=FloatArgumentType.getFloat(context,"strength");PROBE.setWaveStrength(value);context.getSource().sendFeedback(Component.literal("VoxelLight: water wave strength "+value));return 1;
            }));
            var waveSpeedCommand=literal("water_wave_speed").then(argument("speed",FloatArgumentType.floatArg(0,3)).executes(context->{
                float value=FloatArgumentType.getFloat(context,"speed");PROBE.setWaveSpeed(value);context.getSource().sendFeedback(Component.literal("VoxelLight: water wave speed "+value));return 1;
            }));
            var qualityCommand=literal("quality");
            for(var quality:com.voxellight.world.VisualQuality.values())qualityCommand.then(literal(quality.name().toLowerCase(java.util.Locale.ROOT)).executes(context->{
                PROBE.setQuality(quality);context.getSource().sendFeedback(Component.literal("VoxelLight: quality "+quality.name().toLowerCase(java.util.Locale.ROOT)+", volumeSteps="+quality.volumeSteps()+", reflectionSteps="+quality.reflectionSteps()));return 1;
            }));
            var atmosphereCommand=literal("atmosphere");
            for(boolean enabled:new boolean[]{true,false})atmosphereCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setAtmosphere(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: aerial perspective "+enabled));return 1;
            }));
            var volumetricCommand=literal("volumetric");
            for(boolean enabled:new boolean[]{true,false})volumetricCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setVolumetric(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: shadowed volumetric "+enabled));return 1;
            }));
            var densityCommand=literal("atmosphere_density").then(argument("density",FloatArgumentType.floatArg(0,.08f)).executes(context->{
                float density=FloatArgumentType.getFloat(context,"density");PROBE.setAtmosphereDensity(density);
                context.getSource().sendFeedback(Component.literal("VoxelLight: atmosphere density "+density));return 1;
            }));
            var exposureCommand=literal("exposure").then(argument("ev",FloatArgumentType.floatArg(-2,2)).executes(context->{
                float ev=FloatArgumentType.getFloat(context,"ev");PROBE.setExposure(ev);
                context.getSource().sendFeedback(Component.literal("VoxelLight: exposure "+ev+" EV (polished foundation)"));return 1;
            }));
            var temporalCommand = literal("temporal_shadows");
            for (boolean enabled : new boolean[]{true, false}) {
                temporalCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setTemporalShadows(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: terrain shadow history " + (enabled ? "on" : "off")));
                    return 1;
                }));
            }
            var entityMaterialCommand = literal("entity_materials");
            for (boolean enabled : new boolean[]{true, false}) {
                entityMaterialCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setEntityMaterials(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: opaque entity materials " + (enabled ? "on" : "off")));
                    return 1;
                }));
            }
            var entityCommand = literal("entity_shadows");
            for (boolean enabled : new boolean[]{true, false}) {
                entityCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setEntityShadows(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: entity shadows " + (enabled ? "on" : "off")));
                    return 1;
                }));
            }
            var blockEntityCommand = literal("block_entity_shadows");
            for (boolean enabled : new boolean[]{true, false}) {
                blockEntityCommand.then(literal(enabled ? "on" : "off").executes(context -> {
                    PROBE.setBlockEntityShadows(enabled);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: block entity shadows " + (enabled ? "on" : "off")));
                    return 1;
                }));
            }
            var casterCommand = literal("caster_volume");
            for (boolean lightAware : new boolean[]{true, false}) {
                casterCommand.then(literal(lightAware ? "light" : "cube").executes(context -> {
                    PROBE.setLightAwareCasters(lightAware);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: caster volume " + (lightAware ? "light-aware" : "legacy cube")));
                    return 1;
                }));
            }
            var occlusionCommand = literal("light_occlusion");
            for (boolean fine : new boolean[]{true, false}) {
                occlusionCommand.then(literal(fine ? "shapes" : "full").executes(context -> {
                    PROBE.setFineShapes(fine);
                    context.getSource().sendFeedback(Component.literal("VoxelLight: light occlusion " + (fine ? "shapes" : "full")));
                    return 1;
                }));
            }
            var nativeMaterialCommand=literal("native_material");
            for(boolean enabled:new boolean[]{true,false})nativeMaterialCommand.then(literal(enabled?"on":"off").executes(context->{
                PROBE.setNativeMaterial(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: material source "+(enabled?"native visible terrain":"local reference")));return 1;
            }));
            var distanceCommand = literal("shadow_distance")
                    .then(argument("blocks", IntegerArgumentType.integer(12, 128)).executes(context -> {
                        int distance = IntegerArgumentType.getInteger(context, "blocks");
                        PROBE.setShadowDistance(distance);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: shadow distance " + distance + " blocks"));
                        return 1;
                    }));
            dispatcher.register(command.then(modeCommand).then(sceneCommand).then(cacheCommand).then(epochCommand).then(sunCommand).then(localCommand).then(distanceCommand).then(occlusionCommand).then(entityCommand).then(blockEntityCommand).then(casterCommand).then(entityMaterialCommand).then(temporalCommand).then(aoCommand).then(lookCommand).then(bloomCommand).then(coverageCommand).then(exposureCommand).then(heldCommand).then(atmosphereCommand).then(densityCommand).then(volumetricCommand).then(waterCommand).then(nativeMaterialCommand).then(reflectionCommand).then(wavesCommand).then(filterCommand).then(waveStrengthCommand).then(waveSpeedCommand).then(qualityCommand));
        });
    }
}
