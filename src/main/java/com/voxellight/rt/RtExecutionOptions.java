package com.voxellight.rt;

/** Execution and realtime algorithm A/B controls. Defaults leave optional OMM/SER disabled until measured. */
public final class RtExecutionOptions {
    public enum Realtime {FULL(0),CACHE(1),SPARSE(2),CACHE_SPARSE(3);private final int flags;Realtime(int flags){this.flags=flags;}public int flags(boolean realtime){return realtime?flags:0;}}
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
