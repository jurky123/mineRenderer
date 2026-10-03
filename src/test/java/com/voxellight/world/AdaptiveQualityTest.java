package com.voxellight.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdaptiveQualityTest {
    @Test void hysteresisPreventsOneFrameOscillationAndNeverExceedsManualCeiling(){
        var a=new AdaptiveQuality();a.setCeiling(VisualQuality.HIGH);a.setTarget(10);a.setEnabled(true);
        for(int i=0;i<29;i++)assertEquals(VisualQuality.HIGH,a.observe(20_000_000));
        assertEquals(VisualQuality.BALANCED,a.observe(20_000_000));
        for(int i=0;i<30;i++)a.observe(20_000_000);assertEquals(VisualQuality.FAST,a.quality());
        for(int i=0;i<400;i++)a.observe(4_000_000);assertEquals(VisualQuality.HIGH,a.quality());
        a.setCeiling(VisualQuality.BALANCED);for(int i=0;i<400;i++)a.observe(4_000_000);assertEquals(VisualQuality.BALANCED,a.quality());
        a.setEnabled(false);for(int i=0;i<400;i++)a.observe(20_000_000);assertEquals(VisualQuality.BALANCED,a.quality());
    }
    @Test void missingTimestampAndInvalidTargetsDoNotManufactureQualityChanges(){
        var a=new AdaptiveQuality();a.setEnabled(true);for(int i=0;i<100;i++){a.observe(-1);a.observe(0);a.observe(Long.MAX_VALUE);}assertEquals(0,a.measuredMillis());assertEquals(VisualQuality.BALANCED,a.quality());
        assertThrows(IllegalArgumentException.class,()->a.setTarget(Double.NaN));assertThrows(IllegalArgumentException.class,()->a.setTarget(2));
    }
}
