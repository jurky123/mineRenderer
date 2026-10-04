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
        com.voxellight.ui.RendererSettings.initialize();
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
                    .then(literal("settings").executes(context->{context.getSource().getClient().execute(()->context.getSource().getClient().setScreenAndShow(new com.voxellight.ui.RendererSettingsScreen(null)));return 1;}))
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(PROBE.status()));
                        return 1;
                    })
                    .then(literal("status").executes(context -> {
                        context.getSource().sendFeedback(Component.literal(PROBE.status()));
                        return 1;
                    }))
                    .then(literal("profile").then(literal("on").executes(context -> {
                        com.voxellight.adapter.RenderPassProfile.setEnabled(true);PROBE.setWorldProfiling(true);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: per-pass profiling on; export after the comparison"));return 1;
                    })).then(literal("off").executes(context -> {
                        com.voxellight.adapter.RenderPassProfile.setEnabled(false);PROBE.setWorldProfiling(false);
                        context.getSource().sendFeedback(Component.literal("VoxelLight: per-pass profiling off"));return 1;
                    })))
                    .then(literal("export").executes(context -> {
                        try {
                            var directory = FabricLoader.getInstance().getGameDir().resolve("benchmark-results/voxellight");
                            Files.createDirectories(directory);
                            var path = directory.resolve("probe-" + Instant.now().toEpochMilli() + ".csv");
                            PROBE.metrics().export(path);
                            PROBE.exportWorldBudget(path.resolveSibling(path.getFileName()+".world.csv"));
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
            command.then(literal("preset").then(literal("rtx_quality").executes(context->{PROBE.fullReferenceRt(false);PROBE.setMode(RenderProbe.Mode.FOUNDATION);PROBE.setPbr(true);PROBE.setPathTrace(false);PROBE.setTemporalShadows(false);PROBE.setRtBackend(true);PROBE.setQuality(com.voxellight.world.VisualQuality.HIGH);PROBE.setEnvironment("voxel_clouds",true);PROBE.setBloom(true);PROBE.setVolumetric(true);context.getSource().sendFeedback(Component.literal("VoxelLight: RTX Quality; OptiX lighting with raster primary and automatic raster fallback"));return 1;})));
            command.then(literal("preset").then(literal("cinematic").executes(context->{context.getSource().sendFeedback(Component.literal("VoxelLight: full primary-ray Cinematic is reserved; use rtx_quality for raster-primary RT"));return 0;})));
            for(String preset:new String[]{"performance","balanced","quality"})command.then(literal("preset").then(literal(preset).executes(context->{PROBE.setRtBackend(false);PROBE.setEnvironment("voxel_clouds",false);PROBE.setMode(RenderProbe.Mode.FOUNDATION);PROBE.setPbr(true);PROBE.setQuality(preset.equals("performance")?com.voxellight.world.VisualQuality.FAST:preset.equals("quality")?com.voxellight.world.VisualQuality.HIGH:com.voxellight.world.VisualQuality.BALANCED);PROBE.setVolumetric(!preset.equals("performance"));PROBE.setMaterialReflections(!preset.equals("performance"));PROBE.setColorTaa(preset.equals("quality"));PROBE.setBloom(!preset.equals("performance"));PROBE.setPathTrace(preset.equals("quality"));context.getSource().sendFeedback(Component.literal("VoxelLight: "+preset+" preset; quality retains optional NVIDIA diffuse GI, not full PT"));return 1;})));
            for(String name:new String[]{"volumetric_temporal","taa","material_reflections","adaptive_quality"}){
                var effectCommand=literal(name);for(boolean enabled:new boolean[]{true,false})effectCommand.then(literal(enabled?"on":"off").executes(context->{switch(name){case "volumetric_temporal"->PROBE.setVolumeTemporal(enabled);case "adaptive_quality"->PROBE.setAdaptiveQuality(enabled);case "taa"->PROBE.setColorTaa(enabled);case "material_reflections"->PROBE.setMaterialReflections(enabled);}context.getSource().sendFeedback(Component.literal("VoxelLight: "+name+" "+enabled));return 1;}));command.then(effectCommand);
            }
            command.then(literal("gpu_world_target").then(argument("milliseconds",FloatArgumentType.floatArg(4,50)).executes(context->{float value=FloatArgumentType.getFloat(context,"milliseconds");PROBE.setGpuWorldTarget(value);context.getSource().sendFeedback(Component.literal("VoxelLight: measured GPU world region target "+value+" ms"));return 1;})));
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
            var shadowFilterCommand=literal("shadow_filter");
            for(var filter:com.voxellight.world.ShadowFilter.values())shadowFilterCommand.then(literal(filter.name().toLowerCase(java.util.Locale.ROOT)).executes(context->{
                PROBE.setShadowFilter(filter);context.getSource().sendFeedback(Component.literal("VoxelLight: shadow filter "+filter.name().toLowerCase(java.util.Locale.ROOT)+", taps="+filter.taps()));return 1;
            }));
            var hzbCommand=literal("water_hzb");
            for(boolean enabled:new boolean[]{true,false})hzbCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setWaterHzb(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: water HZB "+enabled));return 1;}));
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
            command.then(literal("rt_backend").then(literal("optix_rt").executes(context->{PROBE.setMode(RenderProbe.Mode.FOUNDATION);PROBE.setRtBackend(true);context.getSource().sendFeedback(Component.literal("VoxelLight: experimental OptiX GAS/IAS + GPU interop; rebuilding compiled scene"));return 1;})).then(literal("raster").executes(context->{PROBE.setRtBackend(false);return 1;})).then(literal("cuda_voxel_reference").executes(context->{PROBE.setRtBackend(false);PROBE.setPathTrace(true);return 1;})));
            for(String option:new String[]{"rt_gi","rt_reflections","rt_transmission","rt_denoiser","radiance_cache","rt_caustics","rt_primary_glossy_nee","rt_environment","rt_multiscatter"})for(boolean enabled:new boolean[]{true,false})command.then(literal(option).then(literal(enabled?"on":"off").executes(context->{PROBE.setRtOption(option,enabled);return 1;})));
            for(boolean enabled:new boolean[]{true,false})command.then(literal("voxel_clouds").then(literal(enabled?"on":"off").executes(context->{PROBE.setEnvironment("voxel_clouds",enabled);return 1;})));
            var fullReference=literal("rt_reference_full").then(literal("on").executes(context->{PROBE.fullReferenceRt(true);return 1;})).then(literal("off").executes(context->{PROBE.fullReferenceRt(false);return 1;}));
            var referenceScale=literal("scale");for(int divisor:new int[]{1,2,4})referenceScale.then(literal(Integer.toString(divisor)).executes(context->{PROBE.referenceScale(divisor);return 1;}));command.then(fullReference.then(referenceScale));
            command.then(literal("rt_reference").then(literal("on").executes(context->{PROBE.referenceRt(true);context.getSource().sendFeedback(Component.literal("VoxelLight: reference enabled; OptiX initializes automatically. Hold the camera still; progress is shown in settings/status."));return 1;})).then(literal("off").executes(context->{PROBE.referenceRt(false);context.getSource().sendFeedback(Component.literal("VoxelLight: realtime RTX restored"));return 1;})).then(literal("reset").executes(context->{PROBE.referenceReset();return 1;})).then(literal("spp").then(argument("samples",com.mojang.brigadier.arguments.IntegerArgumentType.integer(4,4096)).executes(context->{PROBE.referenceSpp(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context,"samples"));return 1;}))));
            command.then(literal("rt_firefly_clamp").then(literal("on").executes(context->{PROBE.fireflyClamp(true);return 1;})).then(literal("off").executes(context->{PROBE.fireflyClamp(false);return 1;})));
            command.then(literal("rt_benchmark").executes(context->{PROBE.benchmarkRt();context.getSource().sendFeedback(Component.literal("VoxelLight: triangle/custom AABB benchmark queued; profile on and export timings"));return 1;}));
            var rtDebug=literal("rt_debug");int rtDebugIndex=0;for(String view:new String[]{"off","diffuse","specular","transmission","albedo","roughness","metal","material_transmission","ior","normal","cache_validity","cache_age","cache_cascade","scene_coverage","material","glass_transmittance","water_absorption","material_class","base_color_linear","microfacet_alpha","F0","eta","k","coat_weight","coat_roughness","absorption","sigma_s","sigma_a","medium_id","porosity","sss","emission","path_throughput","bounce_count","light_sample_type","bsdf_lobe","mis_weight","direct_secondary","indirect_diffuse","indirect_specular","emissive_nee","sun_nee","bsdf_pdf","light_pdf","edge_confidence"}){int index=rtDebugIndex++;rtDebug.then(literal(view).executes(context->{PROBE.setRtDebug(index);return 1;}));}command.then(rtDebug);
            var cloudDebug=literal("cloud_debug");int cloudIndex=0;for(String view:new String[]{"off","density","macro_cells"}){int index=cloudIndex++;cloudDebug.then(literal(view).executes(context->{PROBE.setCloudDebug(index);return 1;}));}command.then(cloudDebug);
            command.then(literal("volume_density").then(argument("density",FloatArgumentType.floatArg(0,.08f)).executes(context->{float value=FloatArgumentType.getFloat(context,"density");PROBE.setVolumeDensity(value);context.getSource().sendFeedback(Component.literal("VoxelLight volume density: "+value));return 1;})));
            command.then(literal("forward_scatter").then(argument("strength",FloatArgumentType.floatArg(0,4)).executes(context->{float value=FloatArgumentType.getFloat(context,"strength");PROBE.setForwardStrength(value);context.getSource().sendFeedback(Component.literal("VoxelLight forward scattering: "+value));return 1;})));
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
            for(String option:new String[]{"sky","clouds","cloud_shadows","underwater","caustics","rain_ripples"}){
                var effect=literal(option);for(boolean enabled:new boolean[]{true,false})effect.then(literal(enabled?"on":"off").executes(context->{PROBE.setEnvironment(option,enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: "+option+" "+enabled));return 1;}));command.then(effect);
            }
            var singleRasterCommand=literal("single_raster");
            for(boolean enabled:new boolean[]{true,false})singleRasterCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setSingleRaster(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: single terrain raster "+enabled));return 1;}));
            var pbrCommand=literal("pbr");var wetnessCommand=literal("wetness");var pbrDebugCommand=literal("pbr_debug");
            for(boolean enabled:new boolean[]{true,false}){
                pbrCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setPbr(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: PBR "+enabled));return 1;}));
                wetnessCommand.then(literal(enabled?"on":"off").executes(context->{PROBE.setWetness(enabled);context.getSource().sendFeedback(Component.literal("VoxelLight: rain wetness "+enabled));return 1;}));
            }
            String[] pbrModes={"off","roughness","metal","normal"};
            for(int i=0;i<pbrModes.length;i++){final int mode=i;pbrDebugCommand.then(literal(pbrModes[i]).executes(context->{PROBE.setPbrDebug(mode);context.getSource().sendFeedback(Component.literal("VoxelLight: PBR debug "+pbrModes[mode]));return 1;}));}
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
            var pathCommand=literal("pathtrace");
            var denoiseCommand=literal("pathtrace_denoise");
            var pathDebugCommand=literal("pathtrace_debug");
            var freezeCommand=literal("pathtrace_freeze");var historyCommand=literal("pathtrace_history");
            var rejectionCommand=literal("pathtrace_rejection");var uploadDelayCommand=literal("pathtrace_upload_delay");
            for(boolean value:new boolean[]{true,false}) {
                freezeCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceFreeze(value);context.getSource().sendFeedback(Component.literal("VoxelLight: freeze PT observations "+value));return 1;}));
                historyCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceHistory(value);context.getSource().sendFeedback(Component.literal("VoxelLight: persistent per-surface GI history "+value));return 1;}));
                rejectionCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceRejection(value);context.getSource().sendFeedback(Component.literal("VoxelLight: PT rejection debug "+value));return 1;}));
                uploadDelayCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceUploadDelay(value);context.getSource().sendFeedback(Component.literal("VoxelLight: delay upload source release "+value));return 1;}));
                pathCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTrace(value);context.getSource().sendFeedback(Component.literal("VoxelLight: experimental diffuse path tracing "+value+"; inspect /voxellight status"));return 1;}));
                denoiseCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceDenoise(value);context.getSource().sendFeedback(Component.literal("VoxelLight: OptiX HDR denoising "+value));return 1;}));
                pathDebugCommand.then(literal(value?"on":"off").executes(context->{PROBE.setPathTraceDebug(value);context.getSource().sendFeedback(Component.literal("VoxelLight: indirect-only view "+value));return 1;}));
            }
            var settingsRoot=dispatcher.register(command.then(modeCommand).then(sceneCommand).then(cacheCommand).then(epochCommand).then(sunCommand).then(localCommand).then(distanceCommand).then(occlusionCommand).then(entityCommand).then(blockEntityCommand).then(casterCommand).then(entityMaterialCommand).then(temporalCommand).then(aoCommand).then(lookCommand).then(bloomCommand).then(coverageCommand).then(exposureCommand).then(heldCommand).then(atmosphereCommand).then(densityCommand).then(volumetricCommand).then(waterCommand).then(nativeMaterialCommand).then(singleRasterCommand).then(pbrCommand).then(wetnessCommand).then(pbrDebugCommand).then(reflectionCommand).then(wavesCommand).then(filterCommand).then(waveStrengthCommand).then(waveSpeedCommand).then(qualityCommand).then(shadowFilterCommand).then(hzbCommand).then(pathCommand).then(denoiseCommand).then(pathDebugCommand).then(freezeCommand).then(historyCommand).then(rejectionCommand).then(uploadDelayCommand));
            com.voxellight.ui.RendererSettings.register(dispatcher,settingsRoot);
        });
    }
}
