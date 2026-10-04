package com.voxellight.world;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
class PathTraceSurfaceCacheTest {
    @Test void cacheStoresLinearIrradianceAndSeparatesOpposingFaces(){
        var cache=new PathTraceSurfaceCache();
        cache.add(4,7,8,0,1,0,.2f,.3f,.4f,.5f,.5f,.5f);
        cache.add(4,7,8,0,-1,0,.1f,.1f,.1f,.5f,.5f,.5f);
        var tags=ByteBuffer.allocate(PathTraceSurfaceCache.SLOTS*16).order(ByteOrder.nativeOrder());
        var light=ByteBuffer.allocate(tags.capacity()).order(ByteOrder.nativeOrder());cache.write(tags,light);
        int j=PathTraceSurfaceCache.slot(2,3,4,2)*16;
        assertEquals(.4f,light.getFloat(j),1e-6);assertEquals(.6f,light.getFloat(j+4),1e-6);
        assertEquals(3,Math.floor(tags.getFloat(j+12)));
        assertNotEquals(PathTraceSurfaceCache.slot(2,3,4,2),PathTraceSurfaceCache.slot(2,3,4,3));
        assertTrue(cache.populated());cache.clear();assertFalse(cache.populated());
        tags.clear();light.clear();cache.write(tags,light);assertEquals(0,light.getFloat(j+12));
    }
    @Test void rejectsNonAxisGuidesAndInvalidEnergyAndOverwritesCollisionTags(){
        var cache=new PathTraceSurfaceCache();cache.add(0,0,0,.7f,.7f,0,1,1,1,1,1,1);
        cache.add(0,0,0,0,1,0,Float.NaN,1,1,1,1,1);assertFalse(cache.populated());
        cache.add(0,0,0,0,1,0,1,1,1,1,1,1);
        int collide=1;while(PathTraceSurfaceCache.slot(collide,0,0,2)!=PathTraceSurfaceCache.slot(0,0,0,2))collide++;
        cache.add(collide*2.,0,0,0,1,0,2,2,2,1,1,1);
        var tags=ByteBuffer.allocate(PathTraceSurfaceCache.SLOTS*16).order(ByteOrder.nativeOrder());var light=ByteBuffer.allocate(tags.capacity()).order(ByteOrder.nativeOrder());cache.write(tags,light);
        int j=PathTraceSurfaceCache.slot(0,0,0,2)*16;assertEquals(collide,tags.getFloat(j));assertEquals(2,light.getFloat(j));
    }
}
