package com.voxellight.rt;

/** Stationary history control; live bounded mean by default, frozen snapshots opt-in. */
public final class StationaryAccumulation {
    private boolean enabled=true,frozen;
    private int samples,target=64;
    private double[] pose;
    private long scene=-1;
    private int width,height;
    private String reason="initial";
    public void enabled(boolean value){if(enabled!=value){enabled=value;reset("toggle");}}
    public boolean enabled(){return enabled;}
    public boolean frozen(){return frozen;}
    public void frozen(boolean value){if(frozen!=value){frozen=value;reset("snapshot mode");}}
    public void target(int value){if(value<4||value>4096)throw new IllegalArgumentException("Samples must be 4..4096");target=value;}
    public int target(){return target;}
    public int samples(){return samples;}
    public String reason(){return reason;}
    public void reset(String why){samples=0;pose=null;reason=why;}
    public void begin(double[] current,long generation,int w,int h){
        if(!enabled){samples=0;return;}
        String changed=pose==null?reason:width!=w||height!=h?"resize":scene!=generation?"scene":!same(pose,current)?"camera":"";
        if(!changed.isEmpty()){samples=0;reason=changed;pose=current.clone();scene=generation;width=w;height=h;}
    }
    private static boolean same(double[] a,double[] b){
        if(a.length!=b.length)return false;
        for(int i=0;i<a.length;i++)if(!Double.isFinite(b[i])||Math.abs(a[i]-b[i])>1e-7)return false;
        return true;
    }
    public boolean needsSample(){return !enabled||!frozen||samples<target;}
    public void accepted(){if(enabled&&samples<target)samples++;}
}
