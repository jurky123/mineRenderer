package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** CPU history admission only; GPU performs per-pixel depth, normal, footprint and visibility rejection. */
public final class TemporalShadowState {
    public static final int PIXEL_BYTES=32,SETTINGS_BYTES=96;
    public static final long TARGET_LIMIT=128L*1024*1024;
    public static final float WEIGHT=.75f,DEPTH_ABSOLUTE=.06f,DEPTH_RELATIVE=.0015f,NORMAL_DOT=.95f;
    public record Key(long world,long resources,long sceneRevision,long casterRevision,ShadowLight.Source source) { }
    public record Frame(double x,double y,double z,Matrix4fc worldToClip,Matrix4fc viewRotation,Key key,double lightAngle,long nanos) { }
    public record Admission(boolean reuse,Matrix4f previousWorldToClip,Vector3f cameraDelta,String reason) { }
    private Frame previous;
    private String reason="first frame";
    private long seeds,reuses,resets;
    public static long targetBytes(int width,int height) {
        if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid history size");
        return Math.multiplyExact(Math.multiplyExact((long)width,height),PIXEL_BYTES);
    }
    public Admission admit(Frame current) {
        if(previous==null)return new Admission(false,new Matrix4f(),new Vector3f(),reason);
        var delta=new Vector3f((float)(current.x-previous.x),(float)(current.y-previous.y),(float)(current.z-previous.z));
        String rejection=null;
        if(!current.key.equals(previous.key))rejection="scene/caster/light change";
        else if(current.nanos<=previous.nanos || current.nanos-previous.nanos>250_000_000L)rejection="frame gap";
        else if(!delta.isFinite() || delta.lengthSquared()>64)rejection="camera cut/teleport";
        else if(Math.abs(Math.IEEEremainder(current.lightAngle-previous.lightAngle,Math.PI*2))>.01)rejection="light jump";
        else {
            var a=new Matrix4f(current.viewRotation).invert().transformDirection(0,0,-1,new Vector3f()).normalize();
            var b=new Matrix4f(previous.viewRotation).invert().transformDirection(0,0,-1,new Vector3f()).normalize();
            if(a.dot(b)<.5f)rejection="camera turn";
        }
        return new Admission(rejection==null,new Matrix4f(previous.worldToClip),delta,rejection==null?"reprojection active":rejection);
    }
    public void commit(Frame frame,Admission admission) {
        previous=new Frame(frame.x,frame.y,frame.z,new Matrix4f(frame.worldToClip),new Matrix4f(frame.viewRotation),frame.key,frame.lightAngle,frame.nanos);
        reason=admission.reason;
        if(admission.reuse)reuses++;else seeds++;
    }
    public void invalidate(String why){previous=null;reason=why;resets++;}
    public String status(){return "temporal="+reason+", temporalSeeds="+seeds+", temporalReuses="+reuses+", temporalResets="+resets;}
    public static float blend(float current,float previous,float minimum,float maximum) {
        float clipped=Math.clamp(previous,minimum,maximum);
        return Math.abs(clipped-current)>.2f?current:current*(1-WEIGHT)+clipped*WEIGHT;
    }
}
