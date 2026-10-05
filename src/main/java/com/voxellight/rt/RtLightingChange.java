package com.voxellight.rt;

/** Invalidate large lighting changes while permitting gradual live daylight updates. */
public final class RtLightingChange {
    private float[] anchor;
    // signature: sun direction xyz, irradiance rgb, held position/on/intensity (8), rain, underwater.
    public boolean changed(float[] value){
        if(value.length!=16)throw new IllegalArgumentException("Lighting signature needs 16 values");
        boolean changed=anchor==null;
        if(anchor!=null){
            float angle=0;for(int i=0;i<3;i++)angle+=(value[i]-anchor[i])*(value[i]-anchor[i]);
            changed=angle>.0004f; // About one degree from the history's anchor.
            for(int i=3;i<6;i++)changed|=Math.abs(value[i]-anchor[i])>Math.max(.002f,Math.abs(anchor[i])*.05f);
            for(int i=6;i<9;i++)changed|=Math.abs(value[i]-anchor[i])>.02f;
            for(int i=9;i<14;i++)changed|=Math.abs(value[i]-anchor[i])>1e-5f;
            changed|=Math.abs(value[14]-anchor[14])>.02f||value[15]!=anchor[15];
        }
        if(changed)anchor=value.clone();return changed;
    }
    public void reset(){anchor=null;}
}
