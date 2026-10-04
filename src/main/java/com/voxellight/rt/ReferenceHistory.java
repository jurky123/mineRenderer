package com.voxellight.rt;
import com.voxellight.world.VisualQuality;
/** Reference accumulation is reset by changes, never by unchanged per-frame configuration. */
public final class ReferenceHistory {
    private boolean dirty=true;
    private VisualQuality quality=VisualQuality.BALANCED;
    private float[] camera;
    public static boolean samePose(float[] a,float[] b){if(a==null||b==null||a.length!=16||b.length!=16)return false;for(int i=0;i<16;i++)if(!Float.isFinite(a[i])||!Float.isFinite(b[i])||Math.abs(a[i]-b[i])>.0001f)return false;return true;}
    public void invalidate(){dirty=true;}
    public void quality(VisualQuality value){if(quality!=value){quality=value;invalidate();}}
    public boolean consume(float[] pose,boolean observeCamera){
        if(pose.length!=16)throw new IllegalArgumentException("Expected a 4x4 camera matrix");
        if(observeCamera){
            if(camera==null)dirty=true;
            else for(int i=0;i<16;i++)if(Math.abs(camera[i]-pose[i])>0.0001f){dirty=true;break;}
            // Compare with the accumulated pose so sub-threshold motion cannot drift indefinitely.
            if(dirty)camera=pose.clone();
        }else camera=null;
        boolean reset=dirty;dirty=false;return reset;
    }
}
