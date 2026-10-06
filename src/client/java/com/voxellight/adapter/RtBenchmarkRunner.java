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
    private enum Phase {WARMUP,SAMPLE,DRAIN}
    private static RtBenchmarkRunner running;
    private final RtBenchmarkPlan.Config original=RtBenchmarkPlan.Config.current();
    private final boolean originalProfile=RenderPassProfile.enabled();
    private final Object level=Minecraft.getInstance().level;
    private final long resources=VoxelLightClient.scene().bridge().stats().resourceGeneration();
    private final RtBenchmarkPlan.Plan plan;
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
    private long phaseStart,stableSince,terrainSignature;
    private long first=Long.MAX_VALUE,last=Long.MAX_VALUE,querySkips;
    private RtBenchmarkState measuredState;
    private String startStatus;
    private boolean blockExported;
    private long currentFrame;
    private PassMetrics metrics=new PassMetrics(32000);
    private RtWorkMetrics rays=new RtWorkMetrics();
    private final long[] alive=new long[6];
    private long shadow,anyHit,opaque,replay,mismatches,scopeCount;
    private final LinkedHashSet<String> invalid=new LinkedHashSet<>();
    private RtBenchmarkRunner(int seconds,Consumer<String> feedback)throws IOException{
        this.seconds=seconds;this.feedback=feedback;
        plan=RtBenchmarkPlan.create(initial.querySupported(),initial.compactSupported(),initial.ommSupported()&&initial.opacityValid(),initial.serSupported());
        directory=FabricLoader.getInstance().getGameDir().resolve("benchmark-results/voxellight/rt-suite-"+System.currentTimeMillis());Files.createDirectories(directory);
    }
    public static boolean start(int seconds,Consumer<String> feedback){
        if(running!=null){feedback.accept("VoxelLight: RT benchmark already running; use rt_benchmark status / stop");return false;}
        var client=Minecraft.getInstance();var state=VoxelLightClient.probe().benchmarkState();
        if(client.level==null||state==null||!state.hasOpaque()||state.frozen()){feedback.accept("VoxelLight: enter a loaded Vulkan PT world with opaque geometry and rt_accumulate freeze off first");return false;}
        if(seconds<4||seconds>30)throw new IllegalArgumentException("sample seconds must be 4..30");
        try{
            var suite=new RtBenchmarkRunner(seconds,feedback);running=suite;
            RenderPassProfile.observe(suite.gpuObserver);RenderPassProfile.observeWork(suite.workObserver);RenderPassProfile.setEnabled(true);
            suite.begin();feedback.accept("VoxelLight: automatic RT A/B started; stay still and close menus. "+suite.plan.blocks().size()+" blocks, about "+Math.ceil(suite.plan.blocks().size()*(seconds+6)/60.0)+" minutes; stop restores settings");return true;
        }catch(IOException|RuntimeException error){if(running!=null)running.finish("start failed: "+error.getMessage());else feedback.accept("VoxelLight: benchmark start failed: "+error.getMessage());return false;}
    }
    public static String status(){return running==null?"VoxelLight: no RT benchmark running":running.progress();}
    public static void stop(){if(running!=null)running.finish("cancelled by user");}
    public static void nextFrame(long frame){if(running!=null)try{running.advance(frame);}catch(IOException|RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").error("Automatic RT benchmark failed",error);running.finish("error: "+error.getMessage());}}
    private String progress(){var block=plan.blocks().get(index);return "VoxelLight: RT benchmark "+(index+1)+"/"+plan.blocks().size()+" "+block.comparison()+" "+(block.candidate()?"B":"A")+" round "+(block.round()+1)+" "+phase+"; elapsed "+(System.nanoTime()-start)/1_000_000_000L+"s";}
    private static double[] pose(){var client=Minecraft.getInstance();if(client.player==null)return null;var p=client.gameRenderer.mainCamera().position();return new double[]{p.x,p.y,p.z,client.player.getXRot(),client.player.getYRot()};}
    private boolean moved(){var p=pose();if(p==null||pose==null)return true;for(int i=0;i<5;i++)if(Math.abs(p[i]-pose[i])>(i<3?.03:.1))return true;return false;}
    private void begin(){
        plan.blocks().get(index).config().apply();phase=Phase.WARMUP;phaseStart=stableSince=System.nanoTime();terrainSignature=Long.MIN_VALUE;first=last=Long.MAX_VALUE;measuredState=null;blockExported=false;invalid.clear();
        metrics=new PassMetrics(32000);rays=new RtWorkMetrics();Arrays.fill(alive,0);shadow=anyHit=opaque=replay=mismatches=scopeCount=0;
        feedback.accept(progress());
    }
    private void advance(long frame)throws IOException{
        currentFrame=frame;long now=System.nanoTime();var client=Minecraft.getInstance();
        if(client.level!=level||VoxelLightClient.scene().bridge().stats().resourceGeneration()!=resources){finish("world/resources changed");return;}
        if(now-start>2_000_000_000L&&(moved()||!client.isWindowActive())){finish("camera moved or window lost focus; stationary comparison interrupted");return;}
        var state=VoxelLightClient.probe().benchmarkState();var block=plan.blocks().get(index);
        if(!RtBenchmarkPlan.Config.current().equals(block.config())||!RenderPassProfile.enabled()){finish("execution controls/profiling changed manually");return;}
        if(phase==Phase.WARMUP){
            if(now-phaseStart>30_000_000_000L){finish("warmup did not settle / actual RT configuration unavailable");return;}
            if(state==null||!state.matches(block.config())||state.frozen())return;
            if(state.width()!=initial.width()||state.height()!=initial.height()||state.spp()!=initial.spp()||state.realtime()!=initial.realtime()){finish("resolution/spp/render mode changed");return;}
            if(terrainSignature!=state.terrainSignature()){terrainSignature=state.terrainSignature();stableSince=now;}
            if(now-phaseStart<4_000_000_000L||now-stableSince<1_000_000_000L)return;
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
        if(!owns(sample.frame())||sample.gpuNanos()==null)return;
        metrics.recordScope(sample.scopeId(),sample.frame(),sample.parentScopeId(),sample.mode(),sample.width(),sample.height(),sample.spp(),sample.sceneGeneration(),sample.cpuNanos());metrics.completeGpu(sample.scopeId(),sample.gpuNanos());scopeCount++;
        if(sample.mode().startsWith("vulkan_rt_batch")&&(sample.width()!=measuredState.width()||sample.height()!=measuredState.height()||sample.spp()!=measuredState.spp()))invalid.add("submitted batch dimensions/spp differ from block metadata");
    }
    private void work(RtWorkMetrics.Sample sample){
        if(!owns(sample.frame()))return;
        if(sample.width()!=measuredState.width()||sample.height()!=measuredState.height()||sample.spp()!=measuredState.spp())invalid.add("ray counter dimensions/spp differ from block metadata");
        rays.record(sample.frame(),sample.width(),sample.height(),sample.spp(),sample.scene(),sample.active(),sample.shadow(),sample.anyHit(),sample.opaqueVisibility(),sample.visibilitySamples(),sample.visibilityMismatches());
        for(int i=0;i<6;i++)alive[i]+=sample.active()[i];shadow+=sample.shadow();anyHit+=sample.anyHit();opaque+=sample.opaqueVisibility();replay+=sample.visibilitySamples();mismatches+=sample.visibilityMismatches();
    }
    private String blockName(){var b=plan.blocks().get(index);return String.format(java.util.Locale.ROOT,"%02d-%s-r%d-p%d-%s",index+1,b.comparison(),b.round()+1,b.position()+1,b.candidate()?"B":"A");}
    private void completeBlock()throws IOException{
        var grouped=new LinkedHashMap<String,List<Long>>();for(var sample:metrics.snapshot())if(sample.gpuNanos()!=null)grouped.computeIfAbsent(sample.mode(),ignored->new ArrayList<>()).add(sample.gpuNanos());
        var times=new LinkedHashMap<String,RtBenchmarkResults.Timing>();grouped.forEach((name,values)->times.put(name,RtBenchmarkResults.timing(values)));
        long frames=last-first+1,batches=metrics.snapshot().stream().filter(s->s.mode().startsWith("vulkan_rt_batch")).count();
        if(batches<30||batches<frames*.8)invalid.add("GPU batch timestamp coverage below 80% or fewer than 30 samples");
        if(scopeCount>metrics.size())invalid.add("raw GPU sample ring overflow");
        var fractions=alive[0]==0?new double[0]:new double[6];for(int i=0;i<fractions.length;i++)fractions[i]=alive[i]/(double)alive[0];
        var result=new RtBenchmarkResults.Block(plan.blocks().get(index),first,last,measuredState,invalid.isEmpty(),List.copyOf(invalid),times,fractions,alive[0]==0?null:anyHit/(double)alive[0],replay,mismatches,RenderPassProfile.skippedQueries()-querySkips);
        results.add(result);blockExported=true;String name=blockName();metrics.export(directory.resolve(name+".passes.csv"));rays.export(directory.resolve(name+".rays.csv"));
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
            var report=new LinkedHashMap<String,Object>();report.put("schema",1);report.put("completed",reason==null);report.put("interruption",reason);report.put("sampleSeconds",seconds);report.put("warmupMinimumSeconds",4);report.put("drainSeconds",2);report.put("originalControls",original);report.put("skipped",plan.skipped());report.put("comparisons",comparisons);report.put("blocks",results);
            report.put("device",com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().toString());report.put("version",FabricLoader.getInstance().getModContainer("voxellight").orElseThrow().getMetadata().getVersion().getFriendlyString());
            report.put("limitations",List.of("current-version execution controls only; not alpha.26 vs alpha.28 speedup","completed GPU timestamps; replay benchmarks outside transport batch","two ABBA rounds are descriptive, not a statistical confidence interval","alive drift threshold 5 percentage points; dynamic geometry/light changes may remain","no automatic image correctness, L1/L2 traffic or runtime spill validation"));
            Files.writeString(directory.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
            StringBuilder text=new StringBuilder("VoxelLight automatic RT benchmark\nSettings restored. "+(reason==null?"Completed.":"Interrupted: "+reason)+"\n");
            for(var c:comparisons)text.append(c.name()).append(": ").append(c.verdict()).append("; improvement %=").append(c.improvementPercent()).append("; repeat variation %=").append(c.repeatVariationPercent()).append("\n");
            text.append("Positive improvement means lower candidate GPU batch time. No gain is claimed inside repeat variation.\nSpill/L1/L2 and image correctness require separate verification.\n");Files.writeString(directory.resolve("summary.txt"),text);
            com.voxellight.rt.vulkan.VulkanPipelineDiagnostics.export(directory.resolve("pipelines.csv"));
            var archive=directory.resolveSibling(directory.getFileName()+".zip");try(var zip=new ZipOutputStream(Files.newOutputStream(archive));var files=Files.list(directory)){for(var file:files.sorted().toList()){zip.putNextEntry(new ZipEntry(file.getFileName().toString()));Files.copy(file,zip);zip.closeEntry();}}
            feedback.accept("VoxelLight: RT benchmark "+(reason==null?"complete":"interrupted: "+reason)+"; settings restored; exported benchmark-results/voxellight/"+archive.getFileName());
            for(var c:comparisons)feedback.accept("VoxelLight: "+c.name()+" = "+c.verdict()+ (c.improvementPercent()==null?"":String.format(java.util.Locale.ROOT," (%.2f%%; variation %.2f%%)",c.improvementPercent(),c.repeatVariationPercent())));
        }catch(IOException|RuntimeException error){org.slf4j.LoggerFactory.getLogger("VoxelLight").error("RT benchmark export failed; settings restored",error);feedback.accept("VoxelLight: benchmark export failed; settings restored; see client log");}
    }
}
