package com.voxellight.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtGeometryAllocatorTest {
    @Test void removalDoesNotRelocateSurvivorsAndAdjacentHolesMerge(){
        var allocator=new RtGeometryAllocator(100);
        int a=allocator.allocate(20),b=allocator.allocate(30),c=allocator.allocate(50);
        assertEquals(0,a);assertEquals(20,b);assertEquals(50,c);assertEquals(-1,allocator.allocate(1));
        allocator.release(a,20);assertEquals(0,allocator.allocate(10));
        allocator.release(b,30);
        assertEquals(10,allocator.allocate(40));assertEquals(50,c);
        assertThrows(IllegalArgumentException.class,()->allocator.release(75,30));
        allocator.clear();assertEquals(0,allocator.allocate(100));
    }
    @Test void overlapAndOutOfRangeCannotCorruptTheFreeList(){
        var allocator=new RtGeometryAllocator(10);assertEquals(0,allocator.allocate(5));
        assertThrows(IllegalStateException.class,()->allocator.release(4,2));
        assertThrows(IllegalArgumentException.class,()->allocator.release(9,2));
        allocator.release(0,5);assertThrows(IllegalStateException.class,()->allocator.release(0,5));assertEquals(0,allocator.allocate(10));
    }
}
