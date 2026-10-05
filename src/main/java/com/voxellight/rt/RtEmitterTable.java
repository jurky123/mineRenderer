package com.voxellight.rt;

import java.nio.*;
import java.util.*;

/** Native emissive triangles only; compact CPU metadata, never a frame readback. */
public final class RtEmitterTable {
    public static final int LIMIT=8192, STRIDE=64;
    public record Triangle(int index,int instance,float[] vertices,float area,float mass) {}
    public static List<Triangle> extract(byte[] geometry){
        var source=ByteBuffer.wrap(geometry).order(ByteOrder.LITTLE_ENDIAN);var result=new ArrayList<Triangle>();
        for(int offset=0;offset+120<=geometry.length;offset+=120){
            int emission=(source.getInt(offset+36)>>16)&15;if(emission==0)continue;
            float[] v=new float[9];for(int i=0;i<3;i++)for(int j=0;j<3;j++)v[i*3+j]=source.getFloat(offset+i*40+j*4);
            float x=v[3]-v[0],y=v[4]-v[1],z=v[5]-v[2],u=v[6]-v[0],w=v[7]-v[1],t=v[8]-v[2];
            float a=y*t-z*w,b=z*u-x*t,c=x*w-y*u,area=(float)Math.sqrt(a*a+b*b+c*c)*.5f;
            if(Float.isFinite(area)&&area>1e-10)result.add(new Triangle(offset/120,0,v,area,area*emission));
        }return result;
    }
    public static Triangle world(Triangle t,int base,int instance,int x,int y,int z){
        float[] v=t.vertices.clone();for(int i=0;i<3;i++){v[i*3]+=x*16f;v[i*3+1]+=y*16f;v[i*3+2]+=z*16f;}
        return new Triangle(base+t.index,instance,v,t.area,t.mass);
    }
    public static ByteBuffer pack(List<Triangle> source,double x,double y,double z){
        var selected=new ArrayList<>(source);selected.sort(Comparator.comparingDouble(t->distance(t,x,y,z)));
        if(selected.size()>LIMIT)selected.subList(LIMIT,selected.size()).clear();selected.sort(Comparator.comparingInt(Triangle::index));
        var data=ByteBuffer.allocateDirect(selected.size()*STRIDE).order(ByteOrder.LITTLE_ENDIAN);float cdf=0;
        for(var t:selected){cdf+=t.mass;for(int i=0;i<3;i++){for(int j=0;j<3;j++)data.putFloat(t.vertices[i*3+j]);data.putFloat(i==0?cdf:i==1?t.area:t.mass);}data.putInt(t.index).putInt(t.instance).putInt(0).putInt(0);}
        return data.flip();
    }
    private static double distance(Triangle t,double x,double y,double z){double a=t.vertices[0]-x,b=t.vertices[1]-y,c=t.vertices[2]-z;return a*a+b*b+c*c;}
    private RtEmitterTable(){}
}
