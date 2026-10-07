package com.voxellight.rt;

/** Realtime-only lighting policy. Reference accumulation retains RtLightingChange. */
public final class RtRealtimeLighting {
    private float[] previous,anchor;
    private boolean gradual;
    private String reason="uninitialized";
    public boolean gradual(){return gradual;}
    public String reason(){return reason;}
    public boolean changed(float[] value){
        if(value.length!=16)throw new IllegalArgumentException("Lighting signature needs 16 values");
        gradual=false;String reset=null;
        if(previous==null)reset="initial";
        else{
            float step=0,drift=0;for(int i=0;i<3;i++){step+=square(value[i]-previous[i]);drift+=square(value[i]-anchor[i]);}
            gradual=step>1e-12f;
            if(step>.0004f)reset="sun jump";else if(drift>.011f)reset="sun drift bound"; // about six degrees
            for(int i=3;i<6;i++){
                gradual|=Math.abs(value[i]-previous[i])>1e-7f;
                if(Math.abs(value[i]-previous[i])>Math.max(.002f,Math.abs(previous[i])*.05f))reset="irradiance jump";
                else if(Math.abs(value[i]-anchor[i])>Math.max(.01f,Math.abs(anchor[i])*.25f))reset="irradiance drift bound";
            }
            for(int i=6;i<9;i++)if(Math.abs(value[i]-anchor[i])>.02f)reset="held position";
            for(int i=9;i<14;i++)if(Math.abs(value[i]-anchor[i])>1e-5f)reset="held state";
            gradual|=Math.abs(value[14]-previous[14])>1e-7f;
            if(Math.abs(value[14]-previous[14])>.02f||Math.abs(value[14]-anchor[14])>.2f)reset="weather";
            if(value[15]!=anchor[15])reset="medium";
        }
        previous=value.clone();if(reset!=null){anchor=value.clone();reason=reset;gradual=false;return true;}return false;
    }
    private static float square(float x){return x*x;}
}
