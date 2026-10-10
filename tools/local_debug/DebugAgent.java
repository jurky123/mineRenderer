import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only orchestration; never transforms game/mod classes or bypasses benchmark checks. */
public class DebugAgent {
    static Instrumentation instrumentation;
    static Path root;
    static Class<?> minecraft;
    static final AtomicBoolean queued=new AtomicBoolean();
    static long since, started=System.nanoTime();
    static int stage, suite;
    static boolean hadRun;
    static int camera;
    static final String[] cameras=System.getProperty("voxellight.debug.cameras","terrain").split(",");
    static String last="";
    static final String[] suites=System.getProperty("voxellight.debug.suites","production").split(",");
    static final boolean reference="caustica".equals(System.getProperty("voxellight.debug.renderer"));
    static boolean referenceFocusLost;
    static volatile boolean fixtureReady;
    static volatile String setupFailure;
    static void log(String text) {
        System.out.println("[LOCAL-DEBUG] "+text);
        try { Files.writeString(root.resolve("agent-events.log"),Instant.now()+" "+text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); } catch(Exception ignored) {}
    }
    public static void premain(String args, Instrumentation inst) {
        instrumentation=inst;root=Path.of(args);log("Driver attached; no bytecode transformations; suites="+Arrays.toString(suites));
        Thread worker=new Thread(()->{
            while(stage!=9) {
                try {
                    if(minecraft==null) for(Class<?> type:inst.getAllLoadedClasses()) if(type.getName().equals("net.minecraft.client.Minecraft")){minecraft=type;break;}
                    if(minecraft!=null && queued.compareAndSet(false,true)) {
                        Object client=minecraft.getMethod("getInstance").invoke(null);
                        if(client==null)queued.set(false);
                        else minecraft.getMethod("execute",Runnable.class).invoke(client,(Runnable)()->{try{step(client);}catch(Throwable t){log("ERROR "+t);t.printStackTrace();stage=9;try{minecraft.getMethod("stop").invoke(client);}catch(Exception ignored){}}finally{queued.set(false);}});
                    }
                    Thread.sleep(2000);
                    if(System.nanoTime()-started>3600_000_000_000L){log("One-hour driver limit reached");break;}
                } catch(Throwable t) {log("Poll error "+t);queued.set(false);break;}
            }
        },"voxellight-local-debug");worker.setDaemon(true);worker.start();
    }
    static Class<?> type(String name)throws Exception{return Class.forName(name,true,minecraft.getClassLoader());}
    static Object call(Object object,String name)throws Exception{return object.getClass().getMethod(name).invoke(object);}
    static boolean command(Object client,String command)throws Exception{
        Object connection=call(client,"getConnection");Object source=call(connection,"getSuggestionsProvider");
        Class<?> internals=type("net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
        for(Method m:internals.getMethods())if(m.getName().equals("executeCommand")){
            boolean result=(boolean)m.invoke(null,command,source,source);log("command "+command+" handled="+result);return result;
        }throw new IllegalStateException("Fabric client command API unavailable");
    }
    static void setupWorld(Object client)throws Exception {
        Object server=call(client,"getSingleplayerServer");
        if(server==null)throw new IllegalStateException("Requires isolated singleplayer world");
        server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()->{
            try {
                Object level=call(server,"overworld");
                // Fill rejects unloaded positions; load the complete cleanup and fixture extent first.
                Method chunk=level.getClass().getMethod("getChunk",int.class,int.class);
                for(int x=-1;x<=4;x++)for(int z=-1;z<=1;z++)chunk.invoke(level,x,z);
                Object commands=call(server,"getCommands"), source=call(server,"createCommandSourceStack");
                Method run=commands.getClass().getMethod("performPrefixedCommand",type("net.minecraft.commands.CommandSourceStack"),String.class);
                List<String> inputs=new ArrayList<>(Files.readAllLines(root.resolve("fixture/setup.mcfunction")));
                inputs.add("gamemode creative @a");inputs.add(cameraCommand());
                for(String input:inputs)if(!input.isBlank())run.invoke(commands,source,input);
                Object rules=call(server,"getGameRules");Class<?> gameRules=type("net.minecraft.world.level.gamerules.GameRules");
                Method set=gameRules.getMethod("set",type("net.minecraft.world.level.gamerules.GameRule"),Object.class,type("net.minecraft.server.MinecraftServer"));
                for(String name:new String[]{"ADVANCE_TIME","ADVANCE_WEATHER"})set.invoke(rules,gameRules.getField(name).get(null),false,server);
                Class<?> position=type("net.minecraft.core.BlockPos");
                String[] blocks={"stone","oak_leaves","gold_block","glass","iron_block"};
                Properties verified=new Properties();
                for(int i=0;i<blocks.length;i++){
                    Object pos=position.getConstructor(int.class,int.class,int.class).newInstance(i*16+3,102,6);
                    Object state=level.getClass().getMethod("getBlockState",position).invoke(level,pos);
                    String actual=(String)call(call(state,"getBlock"),"getDescriptionId");
                    verified.setProperty("wall."+i,actual);
                    if(!actual.equals("block.minecraft."+blocks[i]))throw new IllegalStateException("Fixture wall "+i+" expected "+blocks[i]+", got "+actual);
                }
                try(var writer=Files.newBufferedWriter(root.resolve("fixture-verified.properties"))){verified.store(writer,"Server block-state verification after chunk loading and fixture commands");}
                fixtureReady=true;log("Isolated fixture commands submitted; all five wall anchors verified");
            } catch(Throwable t){setupFailure=t.toString();log("World setup ERROR "+t);}
        });
    }
    static String cameraCommand(){
        int x=switch(cameras[camera]){case "terrain"->3;case "foliage"->19;case "mirror"->35;case "glass_water"->51;case "entity"->67;default->throw new IllegalArgumentException("Unknown fixture camera: "+cameras[camera]);};
        return "tp @a "+x+" 100 -3 0 0";
    }
    static void moveCamera(Object client)throws Exception {
        Object server=call(client,"getSingleplayerServer");String input=cameraCommand();
        server.getClass().getMethod("execute",Runnable.class).invoke(server,(Runnable)()->{
            try {Object commands=call(server,"getCommands"),source=call(server,"createCommandSourceStack");commands.getClass().getMethod("performPrefixedCommand",type("net.minecraft.commands.CommandSourceStack"),String.class).invoke(commands,source,input);log("Capture camera="+cameras[camera]+" "+input);}
            catch(Throwable t){log("Camera movement ERROR "+t);stage=9;}
        });
    }
    static void captureState(){
        try {Object probe=type("com.voxellight.VoxelLightClient").getMethod("probe").invoke(null);Object state=call(probe,"benchmarkState");String line=Instant.now()+" camera="+cameras[camera]+" actual="+state+"\n";Files.writeString(root.resolve("capture-states.log"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);log("Capture actual="+state);}
        catch(Exception error){log("Capture state unavailable: "+error);}
    }
    static void step(Object client)throws Exception {
        if(setupFailure!=null)throw new IllegalStateException(setupFailure);
        if(minecraft.getField("level").get(client)==null || minecraft.getField("player").get(client)==null) {
            if(!last.equals("world")){log("Waiting for Quick Play local world");last="world";}return;
        }
        long now=System.nanoTime();
        if(stage==0){setupWorld(client);since=now;stage=1;return;}
        if(stage==1 && fixtureReady && now-since>10_000_000_000L){
            if(reference){since=now;stage=2;log("Caustica reference: 60s prewarm; no VoxelLight commands");return;}
            String[] controls={"rt_backend vulkan_pt","rt_mode realtime","rt_reconstruction dlss","rt_spp 1","rt_transport full","rt_integrator wavefront","rt_shadow exact","rt_realtime full","rt_world exclusive","rt_primary monolithic","rt_guides endpoint","rt_roughness linear","rt_scene_update optimized","rt_accumulate freeze off"};
            for(String control:controls)if(!command(client,"voxellight "+control))throw new IllegalStateException("Command not handled: "+control);
            since=now;stage=2;String requestedShader=System.getProperty("voxellight.debug.shader");
                if(requestedShader!=null){Class<?> shaderType=type("com.voxellight.rt.RtExecutionOptions$Shader");Object shader=java.lang.Enum.valueOf((Class)shaderType,requestedShader);type("com.voxellight.rt.RtExecutionOptions").getMethod("shader",shaderType).invoke(null,shader);log("Explicit diagnostic shader="+requestedShader);}
                String requestedQueue=System.getProperty("voxellight.debug.queue");
                if(requestedQueue!=null){Class<?> queueType=type("com.voxellight.rt.RtExecutionOptions$Queue");Object queue=java.lang.Enum.valueOf((Class)queueType,requestedQueue);type("com.voxellight.rt.RtExecutionOptions").getMethod("queue",queueType).invoke(null,queue);log("Explicit diagnostic queue="+requestedQueue);}
                log("Renderer configured; 60s prewarm");return;
        }
        if(stage==2 && now-since>60_000_000_000L){
            if(reference){
                referenceFocusLost=!(boolean)call(client,"isWindowActive");
                if("capture".equals(suites[suite])){
                    referenceState("quality.capture");
                    type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
                    since=now;stage=10;log("Reference quality-only screenshot; camera="+cameras[camera]);return;
                }
                referenceState("sample.begin");since=now;stage=8;log("Reference 30s stationary observation; camera="+cameras[camera]);return;
            }
            if("capture".equals(suites[suite])){command(client,"voxellight rt_guides endpoint");since=now;stage=4;log("Quality-only captures; no performance benchmark requested; focused="+call(client,"isWindowActive"));return;}
            if(!(boolean)call(client,"isWindowActive")){if(!last.equals("focus")){log("Waiting for real window focus; benchmark focus gate retained");last="focus";}return;}
            command(client,"voxellight stats");
            if(!command(client,"voxellight rt_benchmark "+suites[suite]))throw new IllegalStateException("Benchmark command unhandled");
            since=now;stage=3;hadRun=false;return;
        }
        if(stage==8){
            if(!(boolean)call(client,"isWindowActive")&&!referenceFocusLost){referenceFocusLost=true;log("Reference focus lost: timing observation invalid; continuing quality capture only");}
            if(now-since>30_000_000_000L){
                referenceState("sample.end");type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
                log("Reference screenshot requested; camera="+cameras[camera]);since=now;stage=10;
            }else log("Reference observation "+cameras[camera]+" elapsed="+(now-since)/1_000_000_000L);
        }
        if(stage==10 && now-since>8_000_000_000L){
            if(++camera<cameras.length){moveCamera(client);since=now;stage=2;return;}
            stage=9;log("Reference observations completed; stopping dedicated client normally");minecraft.getMethod("stop").invoke(client);return;
        }
        if(stage==3){
            String status=(String)type("com.voxellight.adapter.RtBenchmarkRunner").getMethod("status").invoke(null);
            if(!status.equals(last)){log(status);last=status;}
            if(!status.contains("no RT benchmark"))hadRun=true;
            else if(hadRun || now-since>10_000_000_000L){
                command(client,"voxellight stats");
                log("Suite finished; inspect exported validity, interruptions and actual backend");
                if(++suite<suites.length){since=now;stage=2;last="";}else if(Boolean.getBoolean("voxellight.debug.profileOnly")){
                    if(++camera<cameras.length){moveCamera(client);suite=0;hadRun=false;last="";since=now;stage=2;log("Profile-only camera="+cameras[camera]+"; 60s settle; captures skipped");}
                    else {stage=9;log("Profile-only completed; stopping dedicated client normally");minecraft.getMethod("stop").invoke(client);}
                }else {
                    command(client,"voxellight rt_guides endpoint");since=now;stage=4;log("Performance finished; settling ENDPOINT for separate image capture");
                }
            }
        }
        if(stage==4 && now-since>10_000_000_000L){
            try {Class<?> diagnostics=type("com.voxellight.rt.vulkan.VulkanPipelineDiagnostics");diagnostics.getMethod("export",Path.class).invoke(null,root.resolve("pipeline-compiler.csv"));diagnostics.getMethod("exportStatus",Path.class).invoke(null,root.resolve("pipeline-compiler-status.json"));}catch(Exception e){log("Compiler export unavailable: "+e);}
            captureState();command(client,"voxellight rt_capture_guides");
            type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
            since=now;stage=5;log("ENDPOINT HDR/Guide capture and native final screenshot requested outside benchmark; camera="+cameras[camera]);return;
        }
        if(stage==5 && now-since>8_000_000_000L){command(client,"voxellight rt_guides endpoint_fast");since=now;stage=6;return;}
        if(stage==6 && now-since>10_000_000_000L){
            try {Class<?> diagnostics=type("com.voxellight.rt.vulkan.VulkanPipelineDiagnostics");diagnostics.getMethod("export",Path.class).invoke(null,root.resolve("pipeline-compiler.csv"));diagnostics.getMethod("exportStatus",Path.class).invoke(null,root.resolve("pipeline-compiler-status.json"));}catch(Exception e){log("Compiler export unavailable: "+e);}
            captureState();command(client,"voxellight rt_capture_guides");
            type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
            since=now;stage=7;log("ENDPOINT_FAST HDR/Guide capture and native final screenshot requested");return;
        }
        if(stage==7 && now-since>8_000_000_000L){
            command(client,"voxellight rt_guides endpoint");
            if(++camera<cameras.length){moveCamera(client);since=now;
                if(Boolean.getBoolean("voxellight.debug.profileEachCamera")&&!reference&&!Arrays.asList(suites).contains("capture")){suite=0;hadRun=false;last="";stage=2;log("Per-camera suite: "+cameras[camera]+"; 60s settle before sampling");}else stage=4;
                return;}
            stage=9;log("Driver completed; stopping dedicated client normally");minecraft.getMethod("stop").invoke(client);
        }
    }
    static void referenceState(String phase)throws Exception {
        Class<?> composite=type("dev.comfyfluffy.caustica.rt.RtComposite"),rr=type("dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr");
        Object instance=composite.getField("INSTANCE").get(null),rrInstance=rr.getField("INSTANCE").get(null);
        if(!(boolean)call(rrInstance,"isReady"))throw new IllegalStateException("Caustica RR feature not ready");
        Properties fields=new Properties();fields.setProperty("camera",cameras[camera]);fields.setProperty("phase",phase);fields.setProperty("utc",Instant.now().toString());fields.setProperty("rrReady","true");fields.setProperty("focusLostDuringObservation",Boolean.toString(referenceFocusLost));fields.setProperty("timingValid",Boolean.toString(!referenceFocusLost&&!phase.startsWith("quality.")));
        for(String name:new String[]{"renderW","renderH","displayW","displayH","renderSizeRrQuality","frameCounter"}){Field f=composite.getDeclaredField(name);f.setAccessible(true);fields.setProperty(name,String.valueOf(f.get(instance)));}
        Object profile=type("dev.comfyfluffy.caustica.rt.RtFrameStats").getField("FRAME").get(null);Field index=profile.getClass().getDeclaredField("frameIndex");index.setAccessible(true);fields.setProperty("profileFrameIndex",String.valueOf(index.get(profile)));
        for(String spec:new String[]{"Composite:SPP","Composite:MAX_BOUNCES","Composite:WATER_WAVES","Fg:ENABLED","DlssRr:QUALITY","FrameStats:ENABLED"}){String[] parts=spec.split(":");Object setting=type("dev.comfyfluffy.caustica.CausticaConfig$Rt$"+parts[0]).getField(parts[1]).get(null);fields.setProperty(spec,String.valueOf(call(setting,"get")));}
        try(var writer=Files.newBufferedWriter(root.resolve("caustica-"+cameras[camera]+"-"+phase+".properties"))){fields.store(writer,"Observed reference state; frame profile scopes differ from VoxelLight benchmark");}
    }
}
