package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtModelSlotsTest {
    @Test void churnReusesStorageWithoutReusingSceneIdentity(){
        var slots=new RtModelSlots();var original=slots.acquire();slots.release(original);var current=slots.acquire();
        assertEquals(original.slot(),current.slot());assertNotEquals(original.keyZ(false),current.keyZ(false));assertNotEquals(current.keyZ(false),current.keyZ(true));
        assertThrows(IllegalArgumentException.class,()->slots.release(original));slots.release(current);
        for(int i=0;i<10000;i++){var handle=slots.acquire();slots.release(handle);}assertEquals(1,slots.capacity());
    }
    @Test void liveModelsNeverShareSlotsAndDoubleReleaseFails(){
        var slots=new RtModelSlots();var a=slots.acquire();var b=slots.acquire();assertNotEquals(a.slot(),b.slot());slots.release(a);assertThrows(IllegalArgumentException.class,()->slots.release(a));
        var c=slots.acquire();assertEquals(a.slot(),c.slot());assertNotEquals(b.slot(),c.slot());
    }
}
