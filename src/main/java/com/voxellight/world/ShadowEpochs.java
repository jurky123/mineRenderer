package com.voxellight.world;

/** Two fixed light directions bracket smooth celestial motion. Never blends depth values. */
public final class ShadowEpochs {
    public static final double STEP = Math.toRadians(.5);
    public static final int NEXT_PAGES = 8; // Per frame across the three maps: 4 + 2 + 2.
    public record Update(ShadowLight first, ShadowLight next, ShadowLight future, float weight, boolean reset, boolean rotate, boolean active) { }
    private ShadowLight first, next;
    private float displayedWeight;
    private long resets, rotations;

    public Update update(ShadowLight current, boolean enabled, boolean nextReady) {
        return update(current,enabled,nextReady,false);
    }
    public Update update(ShadowLight current, boolean enabled, boolean nextReady, boolean futureReady) {
        boolean active=enabled && (current.source()==ShadowLight.Source.SUN || current.source()==ShadowLight.Source.MOON);
        if(!active){clearPair();return new Update(current,current,current,0,false,false,false);}
        boolean reset=false,rotate=false;
        double delta=first==null?0:Math.IEEEremainder(current.angleRadians()-first.angleRadians(),Math.PI*2);
        if(first==null || first.source()!=current.source() || delta < -1e-8 || delta >= STEP*2 || (delta >= STEP && !nextReady)) {
            first=current;next=at(current,current.angleRadians()+STEP);displayedWeight=0;reset=true;resets++;
        } else if(delta>=STEP) {
            first=next;next=at(current,first.angleRadians()+STEP);displayedWeight=0;rotate=true;rotations++;
        }
        delta=Math.IEEEremainder(current.angleRadians()-first.angleRadians(),Math.PI*2);
        float target=(rotate?futureReady:nextReady) && !reset ? (float)Math.clamp(delta/STEP,0,1):0;
        // Bootstrap/background completion cannot introduce a one-frame visibility jump.
        displayedWeight=Math.min(target,displayedWeight+.05f);
        return new Update(first,next,at(next,next.angleRadians()+STEP),displayedWeight,reset,rotate,true);
    }
    private static ShadowLight at(ShadowLight current,double angle) {
        double normalized=((angle%(Math.PI*2))+Math.PI*2)%(Math.PI*2);
        return new ShadowLight(current.source(),ShadowLight.quantize(normalized),current.strength(),normalized);
    }
    public static int pageBudget(int cascade){if(cascade<0 || cascade>=3)throw new IllegalArgumentException("Invalid cascade");return cascade==0?4:2;}
    public long resets(){return resets;}
    public long rotations(){return rotations;}
    private void clearPair(){first=next=null;displayedWeight=0;}
    public void clear(){clearPair();resets=rotations=0;}
}
