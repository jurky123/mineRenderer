package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtLightingChangeTest {
    @Test void gradualSunChangeUsesHistoryAnchorAndHeldSwitchResetsImmediately(){
        var changes=new RtLightingChange();float[] light=new float[16];light[1]=1;light[3]=1;
        assertTrue(changes.changed(light));assertFalse(changes.changed(light));
        light[0]=.01f;assertFalse(changes.changed(light));light[0]=.03f;assertTrue(changes.changed(light));
        light[9]=1;assertTrue(changes.changed(light));light[10]=20;assertTrue(changes.changed(light));
        assertFalse(changes.changed(light));light[15]=1;assertTrue(changes.changed(light));
    }
}
