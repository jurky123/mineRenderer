package com.voxellight.rt;

import java.nio.*;
import java.util.*;

/** Frame-owned exact-PMF hierarchical aliases. Local cells retain a global support mixture. */
public final class RtLightRuntime {
    // At most two direct events per path/bounce; 6-bit feedback cannot overflow the 1 GiB path budget.
    public static final int ADAPTIVE_SCALE=63;
    public static final int WIDTH=9,HEIGHT=5,DEPTH=9,SLOTS=16,HEADER=64,MAGIC=0x4c525432;
    public record Key(int x,int y,int z){}
    private record Node(Key key,List<Integer> lights,float mass){}
    public record Alias(float[] threshold,int[] alternate,float[] pmf){
        public int sample(double u){double v=Math.min(Math.nextDown((double)threshold.length),Math.max(0,u)*threshold.length);int slot=(int)v;return v-slot<threshold[slot]?slot:alternate[slot];}
    }
    public static Alias alias(double[] weights){
        int n=weights.length;float[] p=new float[n],threshold=new float[n];int[] alternate=new int[n];if(n==0)return new Alias(threshold,alternate,p);
        double sum=0;for(double w:weights){if(!Double.isFinite(w)||w<=0)throw new IllegalArgumentException("positive finite proposal weight");sum+=w;}
        double[] scaled=new double[n];var small=new ArrayDeque<Integer>();var large=new ArrayDeque<Integer>();
        for(int i=0;i<n;i++){p[i]=(float)(weights[i]/sum);scaled[i]=weights[i]*n/sum;(scaled[i]<1?small:large).add(i);alternate[i]=i;}
        while(!small.isEmpty()&&!large.isEmpty()){int a=small.removeLast(),b=large.removeLast();threshold[a]=(float)scaled[a];alternate[a]=b;scaled[b]-=1-scaled[a];(scaled[b]<1?small:large).add(b);}
        for(int i:small)threshold[i]=1;for(int i:large)threshold[i]=1;return new Alias(threshold,alternate,p);
    }
    public static List<RtEmitterTable.Triangle> sources(ByteBuffer emitters,ByteBuffer flames){
        var result=new ArrayList<RtEmitterTable.Triangle>();for(var original:List.of(emitters,flames)){
            var data=original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            while(data.remaining()>=64){int offset=data.position();float[] v=new float[9];for(int i=0;i<3;i++)for(int j=0;j<3;j++)v[i*3+j]=data.getFloat(offset+i*16+j*4);
                result.add(new RtEmitterTable.Triangle(data.getInt(offset+48),data.getInt(offset+52),v,data.getFloat(offset+28),data.getFloat(offset+44),data.getInt(offset+56)));data.position(offset+64);}
        }result.sort(Comparator.comparingInt(RtEmitterTable.Triangle::index));return List.copyOf(result);
    }
    private static Key owner(RtEmitterTable.Triangle t){float[] v=t.vertices();float x=v[0],y=v[1],z=v[2];if(t.kind()==0){x=(x+v[3]+v[6])/3;y=(y+v[4]+v[7])/3;z=(z+v[5]+v[8])/3;}return new Key((int)Math.floor(x/16),(int)Math.floor(y/16),(int)Math.floor(z/16));}
    public static int bucket(Key key){return Math.floorMod(Objects.hash(key.x,key.y,key.z),8);}
    private static void aliases(ByteBuffer data,int offset,Alias table,List<Integer> ids){for(int i=0;i<ids.size();i++){int a=offset+i*16;data.putFloat(a,table.threshold[i]).putInt(a+4,table.alternate[i]).putInt(a+8,ids.get(i)).putFloat(a+12,table.pmf[i]);}}
    public static ByteBuffer build(List<RtEmitterTable.Triangle> source,double x,double y,double z,float[] adaptive){
        if(adaptive.length!=8)throw new IllegalArgumentException("eight adaptive buckets");
        var lights=new ArrayList<>(source);lights.sort(Comparator.comparingInt(RtEmitterTable.Triangle::index));
        var groups=new TreeMap<Key,List<Integer>>(Comparator.comparingInt(Key::x).thenComparingInt(Key::y).thenComparingInt(Key::z));
        for(int i=0;i<lights.size();i++)groups.computeIfAbsent(owner(lights.get(i)),ignored->new ArrayList<>()).add(i);
        var nodes=new ArrayList<Node>();for(var entry:groups.entrySet()){float mass=0;for(int i:entry.getValue())mass+=Math.max(1e-8f,lights.get(i).mass());nodes.add(new Node(entry.getKey(),entry.getValue(),mass*Math.clamp(adaptive[bucket(entry.getKey())],.25f,1f)));}
        int n=lights.size(),g=nodes.size(),slots=Math.min(SLOTS,g),recordOffset=HEADER,lightAlias=recordOffset+n*64,nodeOffset=lightAlias+n*16,globalAlias=nodeOffset+g*32,gridOffset=globalAlias+g*16;
        var data=ByteBuffer.allocateDirect(gridOffset+WIDTH*HEIGHT*DEPTH*slots*16).order(ByteOrder.LITTLE_ENDIAN);
        int ox=(int)Math.floor(x/16)-WIDTH/2,oy=(int)Math.floor(y/16)-HEIGHT/2,oz=(int)Math.floor(z/16)-DEPTH/2;
        data.putInt(0,MAGIC).putInt(4,n).putInt(8,g).putInt(12,WIDTH).putInt(16,ox).putInt(20,oy).putInt(24,oz).putInt(28,HEIGHT).putInt(32,DEPTH).putInt(36,recordOffset).putInt(40,lightAlias).putInt(44,nodeOffset).putInt(48,globalAlias).putInt(52,slots).putInt(56,gridOffset);
        int cursor=0;for(int group=0;group<g;group++){var node=nodes.get(group);int offset=nodeOffset+group*32;
            data.putFloat(offset,node.key.x*16+8).putFloat(offset+4,node.key.y*16+8).putFloat(offset+8,node.key.z*16+8).putFloat(offset+12,node.mass).putInt(offset+16,cursor).putInt(offset+20,node.lights.size()).putInt(offset+24,bucket(node.key));
            double[] weights=new double[node.lights.size()];for(int i=0;i<weights.length;i++)weights[i]=Math.max(1e-8f,lights.get(node.lights.get(i)).mass());var table=alias(weights);aliases(data,lightAlias+cursor*16,table,node.lights);
            for(int i=0;i<node.lights.size();i++){int index=node.lights.get(i),a=recordOffset+index*64;var light=lights.get(index);for(int v=0;v<3;v++)for(int c=0;c<3;c++)data.putFloat(a+v*16+c*4,light.vertices()[v*3+c]);
                data.putFloat(a+12,table.pmf[i]).putFloat(a+28,light.area()).putFloat(a+44,light.mass()).putInt(a+48,light.index()).putInt(a+52,light.instance()).putInt(a+56,light.kind()).putInt(a+60,group);}
            cursor+=node.lights.size();
        }
        if(g>0){double[] weights=new double[g];var ids=new ArrayList<Integer>();for(int i=0;i<g;i++){weights[i]=nodes.get(i).mass;ids.add(i);}aliases(data,globalAlias,alias(weights),ids);
            for(int cy=0;cy<HEIGHT;cy++)for(int cz=0;cz<DEPTH;cz++)for(int cx=0;cx<WIDTH;cx++){
                final double px=(ox+cx)*16.+8,py=(oy+cy)*16.+8,pz=(oz+cz)*16.+8;
                double[] distances=new double[g];for(int i=0;i<g;i++)distances[i]=distance(nodes.get(i).key,px,py,pz);
                Comparator<Integer> order=Comparator.<Integer>comparingDouble(i->distances[i]).thenComparingInt(i->i);
                var nearest=new PriorityQueue<Integer>(slots,order.reversed());
                for(int i=0;i<g;i++){if(nearest.size()<slots)nearest.add(i);else if(distances[i]<distances[nearest.peek()]||distances[i]==distances[nearest.peek()]&&i<nearest.peek()){nearest.remove();nearest.add(i);}}
                var nearby=new ArrayList<>(nearest);nearby.sort(order);
                var local=new double[slots];for(int i=0;i<slots;i++)local[i]=nodes.get(nearby.get(i)).mass/(distance(nodes.get(nearby.get(i)).key,px,py,pz)+256);
                aliases(data,gridOffset+((cy*DEPTH+cz)*WIDTH+cx)*slots*16,alias(local),nearby);
            }
        }return data;
    }
    private static double distance(Key key,double x,double y,double z){double dx=key.x*16.+8-x,dy=key.y*16.+8-y,dz=key.z*16.+8-z;return dx*dx+dy*dy+dz*dz;}
    private RtLightRuntime(){}
}
