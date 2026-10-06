package com.voxellight.rt;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtGeometryRangesTest {
    @Test void sortsWholeTrianglesStablyAndRetainsCutoutTransmission(){
        var input=ByteBuffer.allocate(600).order(ByteOrder.nativeOrder());
        for(int i=0;i<5;i++)for(int v=0;v<3;v++){input.putFloat(i*120+v*40,i);input.putInt(i*120+v*40+36,i==0||i==3?1:0);}
        byte[] before=input.array().clone();var ranges=RtGeometryRanges.split(input.array(),offset->offset==120||offset==360);
        assertArrayEquals(new int[]{2,2,1},ranges.counts());assertEquals(0,ranges.base(0));assertEquals(2,ranges.base(1));assertEquals(4,ranges.base(2));
        var output=ByteBuffer.wrap(ranges.triangles()).order(ByteOrder.nativeOrder());int[] order={2,4,0,3,1};
        for(int i=0;i<5;i++)for(int v=0;v<3;v++){assertEquals(order[i],output.getFloat(i*120+v*40));assertEquals(order[i]==1||order[i]==3,(output.getInt(i*120+v*40+36)&4)!=0);}
        assertArrayEquals(before,input.array());
    }
    @Test void handlesEmptyCategoriesAndRejectsPartialTriangles(){
        assertArrayEquals(new int[]{0,0,0},RtGeometryRanges.split(new byte[0],offset->false).counts());
        assertArrayEquals(new int[]{0,0,2},RtGeometryRanges.split(new byte[240],offset->true).counts());
        assertThrows(IllegalArgumentException.class,()->RtGeometryRanges.split(new byte[121],offset->false));
    }
}
