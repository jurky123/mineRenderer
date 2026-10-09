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
    static String last="";
    static final String[] suites=System.getProperty("voxellight.debug.suites","production").split(",");
    static void log(String text) {
        System.out.println("[LOCAL-DEBUG] "+text);
        try { Files.writeString(root.resolve("agent-events.log"),Instant.now()+" "+text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); } catch(Exception ignored) {}
    }
    public static void premain(String args, Instrumentation inst) {
        instrumentation=inst;root=Path.of(args);log("Driver attached; no bytecode transformations; suites="+Arrays.toString(suites));
        Thread worker=new Thread(()->{
            while(stage<9) {
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
                Object commands=call(server,"getCommands"), source=call(server,"createCommandSourceStack");
                Method run=commands.getClass().getMethod("performPrefixedCommand",type("net.minecraft.commands.CommandSourceStack"),String.class);
                List<String> inputs=new ArrayList<>(Files.readAllLines(root.resolve("fixture/setup.mcfunction")));
                inputs.add("gamemode creative @a");inputs.add("tp @a 3 100 -3 0 0");
                for(String input:inputs)if(!input.isBlank())run.invoke(commands,source,input);
                log("Isolated fixture commands submitted");
            } catch(Throwable t){log("World setup ERROR "+t);stage=9;}
        });
    }
    static void step(Object client)throws Exception {
        if(minecraft.getField("level").get(client)==null || minecraft.getField("player").get(client)==null) {
            if(!last.equals("world")){log("Waiting for Quick Play local world");last="world";}return;
        }
        long now=System.nanoTime();
        if(stage==0){setupWorld(client);since=now;stage=1;return;}
        if(stage==1 && now-since>10_000_000_000L){
            String[] controls={"rt_backend vulkan_pt","rt_mode realtime","rt_reconstruction dlss","rt_spp 1","rt_transport full","rt_integrator wavefront","rt_shadow exact","rt_realtime full","rt_world exclusive","rt_primary monolithic","rt_guides endpoint","rt_roughness linear","rt_scene_update optimized","rt_accumulate freeze off"};
            for(String control:controls)if(!command(client,"voxellight "+control))throw new IllegalStateException("Command not handled: "+control);
            since=now;stage=2;log("Renderer configured; 60s prewarm");return;
        }
        if(stage==2 && now-since>60_000_000_000L){
            if(!(boolean)call(client,"isWindowActive")){if(!last.equals("focus")){log("Waiting for real window focus; benchmark focus gate retained");last="focus";}return;}
            command(client,"voxellight stats");
            if(!command(client,"voxellight rt_benchmark "+suites[suite]))throw new IllegalStateException("Benchmark command unhandled");
            since=now;stage=3;hadRun=false;return;
        }
        if(stage==3){
            String status=(String)type("com.voxellight.adapter.RtBenchmarkRunner").getMethod("status").invoke(null);
            if(!status.equals(last)){log(status);last=status;}
            if(!status.contains("no RT benchmark"))hadRun=true;
            else if(hadRun || now-since>10_000_000_000L){
                command(client,"voxellight stats");
                log("Suite finished; inspect exported validity, interruptions and actual backend");
                if(++suite<suites.length){since=now;stage=2;last="";}else {
                    command(client,"voxellight rt_guides endpoint");since=now;stage=4;log("Performance finished; settling ENDPOINT for separate image capture");
                }
            }
        }
        if(stage==4 && now-since>10_000_000_000L){
            command(client,"voxellight rt_capture_guides");
            type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
            since=now;stage=5;log("ENDPOINT HDR/Guide capture and native final screenshot requested outside benchmark");return;
        }
        if(stage==5 && now-since>8_000_000_000L){command(client,"voxellight rt_guides endpoint_fast");since=now;stage=6;return;}
        if(stage==6 && now-since>10_000_000_000L){
            command(client,"voxellight rt_capture_guides");
            type("net.minecraft.client.Screenshot").getMethod("grab",minecraft,boolean.class).invoke(null,client,false);
            since=now;stage=7;log("ENDPOINT_FAST HDR/Guide capture and native final screenshot requested");return;
        }
        if(stage==7 && now-since>8_000_000_000L){
            command(client,"voxellight rt_guides endpoint");stage=9;log("Driver completed; stopping dedicated client normally");minecraft.getMethod("stop").invoke(client);
        }
    }
}
