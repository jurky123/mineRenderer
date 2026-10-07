package com.voxellight.adapter;

import com.google.gson.GsonBuilder;
import com.voxellight.VoxelLightClient;
import com.voxellight.debug.*;
import com.voxellight.rt.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Render-thread benchmark controller. Submission frame ranges own delayed GPU/readback results. */
public final class RtBenchmarkRunner {
    private enum Phase {INITIALIZING,WARMUP,SAMPLE,DRAIN}
    private static RtBenchmarkRunner running;
    private final RtBenchmarkPlan.Config original=RtBenchmarkPlan.Config.current();
    private final boolean originalProfile=RenderPassProfile.enabled();
    private final Object level=Minecraft.getInstance().level;
    private final long resources=VoxelLightClient.scene().bridge().stats().resourceGeneration();
    private final RtBenchmarkPlan.Plan plan;
    private final List<RtGeometryStream.Section> terrain=VoxelLightClient.probe().benchmarkSnapshot();
    public static List<RtGeometryStream.Section> terrainSnapshot(){return running==null?null:running.terrain;}
    private final List<Map<String,Object>> warmupDiagnostics=new ArrayList<>();
    private final long expectedTerrainSignature=com.voxellight.rt.vulkan.VulkanRtScene.benchmarkSignature(terrain);
    private long warmFrame=Long.MAX_VALUE;
    private final List<Long> warmGpu=new ArrayList<>();
    private long lastDiagnostic;
    private final int seconds;
    private final Path directory;
    private final Consumer<String> feedback;
    private final List<RtBenchmarkResults.Block> results=new ArrayList<>();
    private final Consumer<PassMetrics.Sample> gpuObserver=this::gpu;
    private final Consumer<RtWorkMetrics.Sample> workObserver=this::work;
    private final double[] pose=pose();
    private final RtBenchmarkState initial=VoxelLightClient.probe().benchmarkState();
    private final long start=System.nanoTime();
    private int index;
    private Phase phase=Phase.WARMUP;
    private long phaseStart;
    private RtBenchmarkWarmup warmup;
    private long first=Long.MAX_VALUE,last=Long.MAX_VALUE,querySkips;
    private RtBenchmarkState measuredState;
    private String startStatus;
    private boolean blockExported;
    private long currentFrame;
    private PassMetrics metrics=new PassMetrics(32000);
    private RtWorkMetrics rays=new RtWorkMetrics();
    private final Set<Long> counterFrames=new LinkedHashSet<>();
    private final long[] alive=new long[6];
    private final long[][] directCounters=new long[6][4];
    private long shadow,anyHit,opaque,replay,mismatches,scopeCount;
    private final LinkedHashSet<String> invalid=new LinkedHashSet<>();
    private RtBenchmarkRunner(int seconds,Consumer<String> feedback,boolean directOnly,boolean blasOnly,boolean realtimeOnly)throws IOException{
        if(terrain.isEmpty())throw new IllegalStateException("no resident terrain snapshot");
        this.seconds=seconds;this.feedback=feedback;
        plan=realtimeOnly?RtBenchmarkPlan.realtime(initial.querySupported()):blasOnly?RtBenchmarkPlan.blas(initial.querySupported()):directOnly?RtBenchmarkPlan.direct(initial.querySupported()):RtBenchmarkPlan.create(initial.querySupported(),initial.compactSupported(),initial.ommSupported()&&initial.opacityValid(),initial.serSupported());
        directory=FabricLoader.getInstance().getGameDir().resolve("benchmark-results/voxellight/rt-suite-"+System.currentTimeMillis());Files.createDirectories(directory);
    }
    public static boolean start(int seconds,Consumer<String> feedback){return start(seconds,feedback,false,false,false);}
    public static boolean startDirect(int seconds,Consumer<String> feedback){return start(seconds,feedback,true,false,false);}
    public static boolean startBlas(int seconds,Consumer<String> feedback){return start(seconds,feedback,false,true,false);}
    public static boolean startRealtime(int seconds,Consumer<String> feedback){return start(seconds,feedback,false,false,true);}
    private static boolean start(int seconds,Consumer<String> feedback,boolean directOnly,boolean blasOnly,boolean realtimeOnly){
        if(running!=null){feedback.accept("VoxelLight: RT benchmark already running; use rt_benchmark status / stop");return false;}
        var client=Minecraft.getInstance();var state=VoxelLightClient.probe().benchmarkState();
        if(client.level==null||state==null||!state.hasOpaque()||state.frozen()){feedback.accept("VoxelLight: enter a loaded Vulkan PT world with opaque geometry and rt_accumulate freeze off first");return false;}
        if(realtimeOnly&&(!state.realtime()||!state.compactSupported()||state.spp()!=1)){feedback.accept("VoxelLight: realtime suite requires realtime mode, compact queue support and 1 spp");return false;}
        if(seconds<4||seconds>30)throw new IllegalArgumentException("sample seconds must be 4..30");
        try{
            var suite=new RtBenchmarkRunner(seconds,feedback,directOnly,blasOnly,realtimeOnly);running=suite;
            RenderPassProfile.observe(suite.gpuObserver);RenderPassProfile.observeWork(suite.workObserver);RenderPassProfile.setEnabled(true);
            suite.begin();feedback.accept("VoxelLight: automatic RT A/B started; stay still and close menus. "+suite.plan.blocks().size()+" blocks, about "+Math.ceil(suite.plan.blocks().size()*(seconds+6)/60.0)+" minutes plus driver pipeline initialization; SER initialization may pause rendering; stop restores settings");return true;
        }catch(IOException|RuntimeException error){if(running!=null)running.finish("start failed: "+error.getMessage());else feedback.accept("VoxelLight: benchmark start failed: "+error.getMessage());return false;}
    }
    public static String status(){return running==null?"VoxelLight: no RT benchmark running":running.progress();}
    public static void stop(){if(running!=null)running.finish("cancelled by user");}
    public static void nextFrame(long frame){if(running!=null)try{running.advance(frame);}catch(IOException|RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Automatic RT benchmark failed",error);running.finish("error: "+error.getMessage());}}
    private String progress(){var block=plan.blocks().get(index);return "VoxelLight: RT benchmark "+(index+1)+"/"+plan.blocks().size()+" "+block.comparison()+" "+(block.candidate()?"B":"A")+" round "+(block.round()+1)+" "+phase+"; elapsed "+(System.nanoTime()-start)/1_000_000_000L+"s";}
    private static double[] pose(){var client=Minecraft.getInstance();if(client.player==null)return null;var p=client.gameRenderer.mainCamera().position();return new double[]{p.x,p.y,p.z,client.player.getXRot(),client.player.getYRot()};}
    private boolean moved(){var p=pose();if(p==null||pose==null)return true;for(int i=0;i<5;i++)if(Math.abs(p[i]-pose[i])>(i<3?.03:.1))return true;return false;}
    private void begin(){
        plan.blocks().get(index).config().apply();phase=Phase.INITIALIZING;phaseStart=System.nanoTime();warmup=new RtBenchmarkWarmup(phaseStart);lastDiagnostic=0;warmFrame=Long.MAX_VALUE;warmGpu.clear();first=last=Long.MAX_VALUE;measuredState=null;blockExported=false;invalid.clear();counterFrames.clear();
        metrics=new PassMetrics(32000);rays=new RtWorkMetrics();Arrays.fill(alive,0);for(var row:directCounters)Arrays.fill(row,0);shadow=anyHit=opaque=replay=mismatches=scopeCount=0;
        feedback.accept(progress());
    }
    private void advance(long frame)throws IOException{
        currentFrame=frame;long now=System.nanoTime();var client=Minecraft.getInstance();
        if(client.level!=level||VoxelLightClient.scene().bridge().stats().resourceGeneration()!=resources){finish("world/resources changed");return;}
        if(now-start>2_000_000_000L&&(moved()||!client.isWindowActive())){finish("camera moved or window lost focus; stationary comparison interrupted");return;}
        var state=VoxelLightClient.probe().benchmarkState();var block=plan.blocks().get(index);
        if(!RtBenchmarkPlan.Config.current().equals(block.config())||!RenderPassProfile.enabled()){finish("execution controls/profiling changed manually");return;}
        if(phase==Phase.INITIALIZING||phase==Phase.WARMUP){
            boolean snapshotLoaded=state!=null&&state.sections()==terrain.size()&&state.terrainSignature()==expectedTerrainSignature;
            boolean available=snapshotLoaded&&state.matches(block.config())&&!state.frozen()&&state.hasOpaque();
            var readiness=warmup.observe(now,available,state==null?0:state.terrainSignature());
            if(now-lastDiagnostic>=1_000_000_000L){
                var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("block",index+1);diagnostic.put("elapsedSeconds",(now-phaseStart)/1e9);diagnostic.put("expectedTerrainSignature",expectedTerrainSignature);diagnostic.put("expectedTerrainSections",terrain.size());diagnostic.put("snapshotLoaded",snapshotLoaded);diagnostic.put("warmGpuSamples",warmGpu.size());diagnostic.put("warmGpuStable",RtBenchmarkWarmup.gpuStable(warmGpu));diagnostic.put("actual",state);diagnostic.put("requested",block.config());diagnostic.put("readySeconds",warmup.readySeconds(now));diagnostic.put("stableSeconds",warmup.stableSeconds(now));diagnostic.put("readiness",readiness);diagnostic.put("renderer",VoxelLightClient.probe().status());warmupDiagnostics.add(diagnostic);lastDiagnostic=now;
            }

            if(readiness==RtBenchmarkWarmup.Status.INITIALIZATION_TIMEOUT){finish("initialization: renderer/configuration unavailable for 180 seconds; see warmup.json");return;}
            if(readiness==RtBenchmarkWarmup.Status.STABILITY_TIMEOUT){finish("warmup: pinned terrain changed continuously for 30 seconds after initialization; see warmup.json");return;}
            if(readiness==RtBenchmarkWarmup.Status.INITIALIZING){phase=Phase.INITIALIZING;warmFrame=Long.MAX_VALUE;warmGpu.clear();return;}
            if(phase==Phase.INITIALIZING){phase=Phase.WARMUP;warmFrame=frame;warmGpu.clear();feedback.accept(progress());}
            if(state.width()!=initial.width()||state.height()!=initial.height()||state.spp()!=initial.spp()||state.realtime()!=initial.realtime()){finish("resolution/spp/render mode changed");return;}
            if(readiness!=RtBenchmarkWarmup.Status.READY)return;
            if(!RtBenchmarkWarmup.gpuStable(warmGpu)){if(warmup.readySeconds(now)>=30)finish("warmup: GPU batch durations did not settle within 30 seconds; see warmup.json");return;}
            measuredState=state;startStatus=VoxelLightClient.probe().status();first=frame;querySkips=RenderPassProfile.skippedQueries();phase=Phase.SAMPLE;phaseStart=now;feedback.accept(progress());return;
        }
        if(phase==Phase.SAMPLE){
            if(state==null||!state.matches(block.config()))invalid.add("actual execution configuration changed/unavailable");
            else if(!measuredState.sameWorkload(state))invalid.add("resolution/spp/mode/terrain working set changed during block");
            if(now-phaseStart>=seconds*1_000_000_000L){last=frame-1;phase=Phase.DRAIN;phaseStart=now;}
            return;
        }
        if(now-phaseStart<2_000_000_000L)return;
        completeBlock();
        if(results.getLast().timings().keySet().stream().noneMatch(n->n.startsWith("vulkan_rt_batch"))){finish("no GPU RT batch timestamps; CPU time is not a substitute");return;}
        if(++index==plan.blocks().size()){index--;finish(null);}else begin();
    }
    private boolean owns(long frame){return RtBenchmarkResults.ownsFrame(frame,first,last);}
    private void gpu(PassMetrics.Sample sample){
        if(sample.gpuNanos()==null)return;
        if(phase==Phase.WARMUP&&sample.frame()>=warmFrame&&sample.mode().startsWith("vulkan_rt_batch")&&!RenderPassProfile.counterFrame(sample.frame())&&sample.width()==initial.width()&&sample.height()==initial.height()&&sample.spp()==initial.spp()){
            warmGpu.add(sample.gpuNanos());if(warmGpu.size()>60)warmGpu.removeFirst();
        }
        if(!owns(sample.frame()))return;
        if(RenderPassProfile.counterFrame(sample.frame()))counterFrames.add(sample.frame());
        metrics.recordScope(sample.scopeId(),sample.frame(),sample.parentScopeId(),sample.mode(),sample.width(),sample.height(),sample.spp(),sample.sceneGeneration(),sample.cpuNanos());metrics.completeGpu(sample.scopeId(),sample.gpuNanos());scopeCount++;
        if(sample.mode().startsWith("vulkan_rt_batch")&&(sample.width()!=measuredState.width()||sample.height()!=measuredState.height()||sample.spp()!=measuredState.spp()))invalid.add("submitted batch dimensions/spp differ from block metadata");
    }
    private void work(RtWorkMetrics.Sample sample){
        if(!owns(sample.frame()))return;
        if(sample.width()!=measuredState.width()||sample.height()!=measuredState.height()||sample.spp()!=measuredState.spp())invalid.add("ray counter dimensions/spp differ from block metadata");
        rays.record(sample.frame(),sample.width(),sample.height(),sample.spp(),sample.scene(),sample.active(),sample.shadow(),sample.anyHit(),sample.opaqueVisibility(),sample.visibilitySamples(),sample.visibilityMismatches(),sample.direct(),sample.realtime());
        for(int i=0;i<6;i++)for(int c=0;c<4;c++)directCounters[i][c]+=sample.direct()[i][c];
        for(int i=0;i<6;i++)alive[i]+=sample.active()[i];shadow+=sample.shadow();anyHit+=sample.anyHit();opaque+=sample.opaqueVisibility();replay+=sample.visibilitySamples();mismatches+=sample.visibilityMismatches();
    }
    private String blockName(){var b=plan.blocks().get(index);return String.format(java.util.Locale.ROOT,"%02d-%s-r%d-p%d-%s",index+1,b.comparison(),b.round()+1,b.position()+1,b.candidate()?"B":"A");}
    private void completeBlock()throws IOException{
        var times=RtBenchmarkResults.timings(metrics.snapshot(),counterFrames);
        long frames=last-first+1,batches=metrics.snapshot().stream().filter(s->s.mode().startsWith("vulkan_rt_batch")).count();
        if(batches<30||batches<frames*.8)invalid.add("GPU batch timestamp coverage below 80% or fewer than 30 samples");
        if(scopeCount>metrics.size())invalid.add("raw GPU sample ring overflow");
        var fractions=alive[0]==0?new double[0]:new double[6];for(int i=0;i<fractions.length;i++)fractions[i]=alive[i]/(double)alive[0];
        var result=new RtBenchmarkResults.Block(plan.blocks().get(index),first,last,measuredState,invalid.isEmpty(),List.copyOf(invalid),times,fractions,alive[0]==0?null:anyHit/(double)alive[0],replay,mismatches,RenderPassProfile.skippedQueries()-querySkips);
        results.add(result);blockExported=true;String name=blockName();metrics.export(directory.resolve(name+".passes.csv"));rays.export(directory.resolve(name+".rays.csv"));
        Files.writeString(directory.resolve(name+".direct_lighting.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("columns",List.of("surfaceHits","surfaceVisibilityConnections","risCandidates","risSelected"),"bounceCounters",directCounters)));
        Files.writeString(directory.resolve(name+".counter_frames.json"),new GsonBuilder().create().toJson(counterFrames));
        var leanFrames=metrics.snapshot().stream().map(PassMetrics.Sample::frame).filter(frame->!counterFrames.contains(frame)).collect(java.util.stream.Collectors.toSet());
        Files.writeString(directory.resolve(name+".diagnostic_timings.json"),new GsonBuilder().setPrettyPrinting().create().toJson(RtBenchmarkResults.timings(metrics.snapshot(),leanFrames)));
        Files.writeString(directory.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        Files.writeString(directory.resolve(name+".txt"),"start\n"+startStatus+"\nend\n"+VoxelLightClient.probe().status()+"\n");
        feedback.accept("VoxelLight: "+name+" captured "+batches+" GPU batches; "+(result.valid()?"workload checks passed":String.join("; ",result.reasons())));
    }
    private void finish(String reason){
        // Restore before export/compression; every success/error/cancel path releases observers.
        RenderPassProfile.unobserve(gpuObserver);RenderPassProfile.unobserveWork(workObserver);original.apply();RenderPassProfile.setEnabled(originalProfile);running=null;
        try{
            if(reason!=null&&!blockExported&&first!=Long.MAX_VALUE){invalid.add("interrupted: "+reason);if(last==Long.MAX_VALUE)last=Math.max(first,currentFrame-1);completeBlock();}
            var comparisons=new ArrayList<RtBenchmarkResults.Comparison>();for(String name:plan.blocks().stream().map(RtBenchmarkPlan.Block::comparison).distinct().toList())comparisons.add(RtBenchmarkResults.compare(name,results));
            var report=new LinkedHashMap<String,Object>();report.put("schema",8);report.put("comparisonMetrics",Map.of("blas","vulkan_rt_scene_commit","others","transport batch"));report.put("timingPolicy","GPU timestamps on counter-free frames; counters/replay every eighth frame; raw CSV includes both, see counter_frames.json");report.put("initializationLimitSeconds",RtBenchmarkWarmup.INITIALIZATION_LIMIT_SECONDS);report.put("terrainPolicy","fixed nearest resident terrain snapshot within 60 MiB; 4 MiB dynamic headroom; exact count/signature required before sampling; dynamic models and lighting remain live");report.put("terrainSnapshotSignature",expectedTerrainSignature);report.put("terrainSnapshotSections",terrain.size());report.put("terrainSnapshotBytes",terrain.stream().mapToLong(section->section.triangles().length).sum());report.put("completed",reason==null);report.put("interruption",reason);report.put("sampleSeconds",seconds);report.put("warmupMinimumSeconds",4);report.put("drainSeconds",2);report.put("originalControls",original);report.put("skipped",plan.skipped());report.put("comparisons",comparisons);report.put("blocks",results);
            report.put("device",com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().toString());report.put("version",FabricLoader.getInstance().getModContainer("voxellight").orElseThrow().getMetadata().getVersion().getFriendlyString());
            report.put("limitations",List.of("current-version execution controls only; not alpha.26 vs alpha.28 speedup","completed GPU timestamps; replay benchmarks outside transport batch","two ABBA rounds are descriptive, not a statistical confidence interval","alive drift threshold 5 percentage points for execution controls; realtime algorithm comparisons permit intentional path reduction; dynamic geometry/light changes may remain","no automatic image correctness, L1/L2 traffic or runtime spill validation"));
            Files.writeString(directory.resolve("warmup.json"),new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(warmupDiagnostics));
            Files.writeString(directory.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            StringBuilder text=new StringBuilder("VoxelLight automatic RT benchmark\nSettings restored. "+(reason==null?"Completed.":"Interrupted: "+reason)+"\n");
            for(var c:comparisons)text.append(c.name()).append(": ").append(c.verdict()).append("; improvement %=").append(c.improvementPercent()).append("; repeat variation %=").append(c.repeatVariationPercent()).append("\n");
            text.append("Positive improvement means lower candidate GPU batch time. No gain is claimed inside repeat variation.\nSpill/L1/L2 and image correctness require separate verification.\n");Files.writeString(directory.resolve("summary.txt"),text);
            com.voxellight.rt.vulkan.VulkanPipelineDiagnostics.export(directory.resolve("pipelines.csv"));
            com.voxellight.rt.vulkan.VulkanPipelineDiagnostics.exportStatus(directory.resolve("pipelines-status.json"));
            var archive=directory.resolveSibling(directory.getFileName()+".zip");try(var zip=new ZipOutputStream(Files.newOutputStream(archive));var files=Files.list(directory)){for(var file:files.sorted().toList()){zip.putNextEntry(new ZipEntry(file.getFileName().toString()));Files.copy(file,zip);zip.closeEntry();}}
            feedback.accept("VoxelLight: RT benchmark "+(reason==null?"complete":"interrupted: "+reason)+"; settings restored; exported benchmark-results/voxellight/"+archive.getFileName());
            for(var c:comparisons)feedback.accept("VoxelLight: "+c.name()+" = "+c.verdict()+ (c.improvementPercent()==null?"":String.format(java.util.Locale.ROOT," (%.2f%%; variation %.2f%%)",c.improvementPercent(),c.repeatVariationPercent())));
        }catch(IOException|RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").error("RT benchmark export failed; settings restored",error);feedback.accept("VoxelLight: benchmark export failed; settings restored; see client log");}
    }
}
