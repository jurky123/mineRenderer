package com.voxellight.rt;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtAlpha49Test {
 @Test void guidesCompareAllModesForBothPrimaryPaths(){var p=RtBenchmarkPlan.guides();assertEquals(32,p.blocks().size());for(var b:p.blocks()){assertEquals(RtExecutionOptions.Transport.FULL,b.config().transport());assertEquals(RtExecutionOptions.Realtime.FULL,b.config().realtimePolicy());}for(var primary:RtExecutionOptions.Primary.values())for(var guide:RtExecutionOptions.Guides.values())assertTrue(p.blocks().stream().anyMatch(b->b.config().primary()==primary&&b.config().guides()==guide));}
 @Test void geometryLoadChangesPositionsAndKeepsMaterialTopology(){byte[] source=new byte[120];var b=ByteBuffer.wrap(source).order(ByteOrder.nativeOrder());for(int i=0;i<3;i++){b.putFloat(i*40,i);b.putInt(i*40+32,0xffaabbcc);b.putInt(i*40+36,1);}var a=RtSceneLoad.geometry(source,1,0);var c=RtSceneLoad.geometry(source,2,0);assertEquals(128*120,a.length);assertArrayEquals(a,RtSceneLoad.geometry(source,1,0));assertFalse(java.util.Arrays.equals(a,c));var v=ByteBuffer.wrap(a).order(ByteOrder.nativeOrder());for(int i=0;i<384;i++){assertEquals(0xffaabbcc,v.getInt(i*40+32));assertEquals(1,v.getInt(i*40+36));}assertEquals(0xffaabbcc,b.getInt(32));}
}
