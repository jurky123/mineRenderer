package com.voxellight.world;

import org.joml.Vector3f;
import java.util.*;
import java.util.function.Predicate;

/** Section AABBs intersecting a receiver sphere swept toward its celestial light. */
public final class CasterVolume {
    public static final int MAX_SECTIONS=384,LOCAL_RADIUS=2;
    public static final float MIN_EXTRUSION=16,MAX_EXTRUSION=48;
    public record Window(List<SectionKey> candidates,float extrusion) { }
    public record Admission(List<SectionKey> sections,int eligible,int deferred) { }
    private CasterVolume() { }

    public static Window select(SectionKey center,ShadowLight light,boolean lightAware) {
        return select(center,light,lightAware,0,0,0);
    }

    /** Camera offset relative to its section midpoint, calculated in double precision. */
    public static Window select(SectionKey center,ShadowLight light,boolean lightAware,double ox,double oy,double oz) {
        if(!Double.isFinite(ox)||!Double.isFinite(oy)||!Double.isFinite(oz))throw new IllegalArgumentException("Invalid camera offset");
        if(!lightAware || light.source()==ShadowLight.Source.NONE) {
            var cube=new ArrayList<SectionKey>();
            for(int x=-3;x<=3;x++)for(int y=-3;y<=3;y++)for(int z=-3;z<=3;z++)
                cube.add(new SectionKey(center.x()+x,center.y()+y,center.z()+z));
            cube.sort(CasterResidency.priority(center));
            return new Window(List.copyOf(cube),0);
        }
        var direction=light.direction();
        float extrusion=Math.clamp((1-Math.abs(direction.y))*80,MIN_EXTRUSION,MAX_EXTRUSION);
        double radius=48; // Independent near-field bridge; distant casters borrow native geometry.
        // Section centers sit at multiples of 16 relative to the receiver's section center.
        int minX=lower(ox,direction.x*extrusion,radius),maxX=upper(ox,direction.x*extrusion,radius);
        int minY=lower(oy,direction.y*extrusion,radius),maxY=upper(oy,direction.y*extrusion,radius);
        int minZ=lower(oz,direction.z*extrusion,radius),maxZ=upper(oz,direction.z*extrusion,radius);
        var candidates=new ArrayList<SectionKey>();
        for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++) {
            if(local(x,y,z) || distanceSquared(x,y,z,direction,extrusion,ox,oy,oz)<=radius*radius)
                candidates.add(new SectionKey(center.x()+x,center.y()+y,center.z()+z));
        }
        candidates.sort(Comparator.<SectionKey>comparingInt(key->{
            int x=key.x()-center.x(),y=key.y()-center.y(),z=key.z()-center.z();
            return local(x,y,z)?0:distanceSquared(x,y,z,direction,0,ox,oy,oz)<=radius*radius?1:2;
        }).thenComparing(CasterResidency.priority(center)));
        return new Window(List.copyOf(candidates),extrusion);
    }
    /** Filter unavailable sections before applying the fixed bridge cap. Never generates chunks. */
    public static Admission admit(Window window,int minY,int maxY,Predicate<SectionKey> loaded) {
        var sections=new ArrayList<SectionKey>(MAX_SECTIONS);
        int eligible=0;
        for(var key:window.candidates()) {
            if(key.y()<minY || key.y()>maxY || !loaded.test(key))continue;
            eligible++;
            if(sections.size()<MAX_SECTIONS)sections.add(key);
        }
        return new Admission(List.copyOf(sections),eligible,Math.max(0,eligible-MAX_SECTIONS));
    }
    private static boolean local(int x,int y,int z){return Math.abs(x)<=LOCAL_RADIUS && Math.abs(y)<=LOCAL_RADIUS && Math.abs(z)<=LOCAL_RADIUS;}
    private static int lower(double offset,double end,double radius){return (int)Math.ceil((offset+Math.min(0,end)-radius-8)/16);}
    private static int upper(double offset,double end,double radius){return (int)Math.floor((offset+Math.max(0,end)+radius+8)/16);}

    /** Exact convex piecewise-quadratic distance from a segment to an axis-aligned section box. */
    static double distanceSquared(int x,int y,int z,Vector3f direction,double length) {
        return distanceSquared(x,y,z,direction,length,0,0,0);
    }
    private static double distanceSquared(int x,int y,int z,Vector3f direction,double length,double ox,double oy,double oz) {
        double[] low={x*16.0-8-ox,y*16.0-8-oy,z*16.0-8-oz};
        double[] high={low[0]+16,low[1]+16,low[2]+16};
        double[] d={direction.x,direction.y,direction.z};
        double[] breaks=new double[8];int count=2;breaks[0]=0;breaks[1]=length;
        for(int axis=0;axis<3;axis++)if(Math.abs(d[axis])>1e-12) {
            for(double boundary:new double[]{low[axis],high[axis]}) {
                double t=boundary/d[axis];
                if(t>0 && t<length)breaks[count++]=t;
            }
        }
        Arrays.sort(breaks,0,count);
        double best=pointDistance(low,high,d,0);
        for(int i=1;i<count;i++) {
            double left=breaks[i-1],right=breaks[i],middle=(left+right)*.5;
            double quadratic=0,linear=0;
            for(int axis=0;axis<3;axis++) {
                double p=d[axis]*middle;
                if(p>=low[axis] && p<=high[axis])continue;
                double boundary=p<low[axis]?low[axis]:high[axis];
                quadratic+=d[axis]*d[axis];linear-=d[axis]*boundary;
            }
            double t=quadratic>0?Math.clamp(-linear/quadratic,left,right):left;
            best=Math.min(best,Math.min(pointDistance(low,high,d,t),pointDistance(low,high,d,right)));
        }
        return best;
    }
    private static double pointDistance(double[] low,double[] high,double[] direction,double t) {
        double distance=0;
        for(int axis=0;axis<3;axis++) {
            double p=direction[axis]*t;
            double delta=p<low[axis]?low[axis]-p:p>high[axis]?p-high[axis]:0;
            distance+=delta*delta;
        }
        return distance;
    }
}
