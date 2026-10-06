package com.voxellight.rt;

/** Actual renderer state, never inferred from the requested controls or log text. */
public record RtBenchmarkState(int width,int height,int spp,boolean realtime,boolean frozen,
        boolean querySupported,boolean compactSupported,boolean ommSupported,boolean serSupported,
        RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,
        boolean opacityValid,boolean hasOpaque,long terrainSignature,long sceneGeneration,int sections,long sceneBytes,RtExecutionOptions.Direct direct){
    public RtBenchmarkState(int width,int height,int spp,boolean realtime,boolean frozen,boolean querySupported,boolean compactSupported,boolean ommSupported,boolean serSupported,RtExecutionOptions.Visibility visibility,RtExecutionOptions.Queue queue,boolean omm,boolean ser,boolean opacityValid,boolean hasOpaque,long terrainSignature,long sceneGeneration,int sections,long sceneBytes){this(width,height,spp,realtime,frozen,querySupported,compactSupported,ommSupported,serSupported,visibility,queue,omm,ser,opacityValid,hasOpaque,terrainSignature,sceneGeneration,sections,sceneBytes,RtExecutionOptions.Direct.LEGACY);}
    public boolean matches(RtBenchmarkPlan.Config config){return config.direct()==direct&&config.visibility()==visibility&&config.queue()==queue&&config.omm()==omm&&config.ser()==ser&&(!config.omm()||opacityValid);}
    public boolean sameWorkload(RtBenchmarkState other){return width==other.width&&height==other.height&&spp==other.spp&&realtime==other.realtime&&terrainSignature==other.terrainSignature;}
}
