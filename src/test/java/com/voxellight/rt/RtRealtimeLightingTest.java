package com.voxellight.rt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtRealtimeLightingTest {
    private static float[] light(){float[] v=new float[16];v[1]=1;v[3]=v[4]=v[5]=1;return v;}
    private static void angle(float[] v,double degrees){v[0]=(float)Math.sin(Math.toRadians(degrees));v[1]=(float)Math.cos(Math.toRadians(degrees));}
    @Test void gradualDaylightSurvivesOriginalThresholdAndHasFiniteDriftBound(){
        var changes=new RtRealtimeLighting();var v=light();assertTrue(changes.changed(v));assertFalse(changes.gradual());
        for(int i=1;i<=50;i++){angle(v,i*.1);assertFalse(changes.changed(v));assertTrue(changes.gradual());}
        angle(v,6.1);assertTrue(changes.changed(v));assertEquals("sun drift bound",changes.reason());
    }
    @Test void suddenLightJumpAndHeldMovementInvalidateImmediately(){
        var changes=new RtRealtimeLighting();var v=light();changes.changed(v);angle(v,2);assertTrue(changes.changed(v));assertEquals("sun jump",changes.reason());
        v[9]=1;assertTrue(changes.changed(v));assertEquals("held state",changes.reason());v[6]=.03f;assertTrue(changes.changed(v));assertEquals("held position",changes.reason());
        v[3]=2;assertTrue(changes.changed(v));assertEquals("irradiance jump",changes.reason());
    }
    @Test void slowIrradianceAndWeatherAreBoundedAndMediumIsHardReset(){
        var changes=new RtRealtimeLighting();var v=light();changes.changed(v);
        for(int i=1;i<=20;i++){v[3]=1+i*.01f;assertFalse(changes.changed(v));assertTrue(changes.gradual());}
        for(int i=21;i<=26;i++){v[3]=1+i*.01f;boolean reset=changes.changed(v);assertEquals(i==26,reset);}
        v[14]=.03f;assertTrue(changes.changed(v));assertEquals("weather",changes.reason());v[15]=1;assertTrue(changes.changed(v));assertEquals("medium",changes.reason());
        assertThrows(IllegalArgumentException.class,()->changes.changed(new float[15]));
    }
}
