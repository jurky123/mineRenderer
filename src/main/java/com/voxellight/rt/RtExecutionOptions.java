package com.voxellight.rt;

/** Execution and realtime algorithm A/B controls. Defaults leave optional OMM/SER disabled until measured. */
public final class RtExecutionOptions {
    public enum Realtime {FULL(0),CACHE(1),SPARSE(2),CACHE_SPARSE(3);private final int flags;Realtime(int flags){this.flags=flags;}public int flags(boolean realtime){return realtime?flags:0;}}
    public enum Shader {CLEAN,RUNTIME}
    private static Shader shader=Shader.CLEAN;
    public static Shader shader(){return shader;}
    public static void shader(Shader value){if(shader!=value){shader=value;revision++;}}
    public static String stage(String base,boolean realtime,boolean query,boolean ser){
        if(shader==Shader.RUNTIME)return base+(base.equals("material_resolve")?"":(query?"_query":"")+(ser?"_ser":""));
        String family=realtime&&RtExecutionOptions.realtime()!=Realtime.FULL?"realtime":"full";
        if(base.equals("material_resolve"))return base+"_"+family;
        return base+"_"+family+(direct==Direct.LEGACY?"_legacy":"")+(query?"_query":"")+(ser?"_ser":"");
    }
    private static Realtime realtime=Realtime.FULL;
    public static Realtime realtime(){return realtime;}
    public static void realtime(Realtime value){if(realtime!=value){realtime=value;revision++;}}
    public enum SceneUpdate {LEGACY,OPTIMIZED}
    private static SceneUpdate sceneUpdate=SceneUpdate.OPTIMIZED;
    public static SceneUpdate sceneUpdate(){return sceneUpdate;}
    public static void sceneUpdate(SceneUpdate value){if(sceneUpdate!=value){sceneUpdate=value;revision++;}}
    public enum Direct {LEGACY,RIS}
    private static Direct direct=Direct.RIS;
    public static Direct direct(){return direct;}
    public static void direct(Direct value){if(direct!=value){direct=value;revision++;}}
    public enum Visibility {LEGACY,TRACE,QUERY}
    public enum Integrator {WAVEFRONT,ITERATIVE}
    private static Integrator integrator=Integrator.WAVEFRONT;
    public static Integrator integrator(){return integrator;}
    public static void integrator(Integrator value){if(integrator!=value){integrator=value;revision++;}}
    public enum World {COMPOSITE,EXCLUSIVE}
    private static World world=World.EXCLUSIVE;
    public static World world(){return world;}
    public static void world(World value){world=value;}
    public enum Shadow {EXACT,FAST}
    private static Shadow shadow=Shadow.FAST;
    public static Shadow shadow(){return shadow;}
    public static void shadow(Shadow value){if(shadow!=value){shadow=value;revision++;}}
    public enum Queue {AUTO,FIXED,COMPACT,HYBRID}
    private static Visibility visibility=Visibility.TRACE;
    private static Queue queue=Queue.AUTO;
    private static boolean omm,ser;
    private static long revision;
    private RtExecutionOptions(){}
    public static Visibility visibility(){return visibility;}
    public static Queue queue(){return queue;}
    public static boolean omm(){return omm;}public static boolean ser(){return ser;}
    public static long revision(){return revision;}
    public static void visibility(Visibility value){if(visibility!=value){visibility=value;revision++;}}
    public static void queue(Queue value){queue=value;}
    public static void omm(boolean value){if(omm!=value){omm=value;revision++;}}
    public static void ser(boolean value){if(ser!=value){ser=value;revision++;}}
}
