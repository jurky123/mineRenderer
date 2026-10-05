package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RtEmitterTableTest {
    @Test void nativeEmissionPackingRetainsGlobalTriangleAndInstanceAndWorldCoordinates(){
        var b=ByteBuffer.allocate(240).order(ByteOrder.LITTLE_ENDIAN);
        for(int t=0;t<2;t++){b.putFloat(t*120+40,2);b.putFloat(t*120+84,2);b.putInt(t*120+36,t==0?15<<16:0);}
        var triangles=RtEmitterTable.extract(b.array());assertEquals(1,triangles.size());assertEquals(2,triangles.getFirst().area());
        var world=RtEmitterTable.world(triangles.getFirst(),47,5,-2,3,4);
        var packed=RtEmitterTable.pack(List.of(world),0,0,0);assertEquals(64,packed.remaining());assertEquals(-32,packed.getFloat(0));assertEquals(48,packed.getFloat(4));assertEquals(64,packed.getFloat(8));
        assertEquals(30,packed.getFloat(12));assertEquals(2,packed.getFloat(28));assertEquals(47,packed.getInt(48));assertEquals(5,packed.getInt(52));
    }
    @Test void boundedTableChoosesNearestButKeepsSortedIdsAndCumulativeMass(){
        var triangles=new ArrayList<RtEmitterTable.Triangle>();for(int i=0;i<RtEmitterTable.LIMIT+1;i++)triangles.add(new RtEmitterTable.Triangle(i,0,new float[]{i,0,0,i,1,0,i,0,1},.5f,1));
        var b=RtEmitterTable.pack(triangles,0,0,0);assertEquals(RtEmitterTable.LIMIT*64,b.remaining());assertEquals(RtEmitterTable.LIMIT,b.getFloat(b.limit()-52));assertEquals(RtEmitterTable.LIMIT-1,b.getInt(b.limit()-16));
    }
}
