package com.voxellight.world;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CasterVolumeTest {
    private final SectionKey center=new SectionKey(0,0,0);
    private static ShadowLight sun(double angle){return new ShadowLight(ShadowLight.Source.SUN,ShadowLight.quantize(angle),.45f,angle);}

    @Test void lowSunFindsUpstreamCastersBeyondTheOldCube() {
        var low=CasterVolume.select(center,sun(-1.3),true);
        assertEquals(48,low.extrusion());
        assertTrue(low.candidates().contains(new SectionKey(5,0,0)));
        assertTrue(CasterVolume.admit(low,-100,100,key->true).sections().contains(new SectionKey(5,0,0)), "The upstream caster must survive the scene cap");
        assertFalse(low.candidates().contains(new SectionKey(-5,0,0)));
        assertFalse(CasterVolume.select(center,sun(0),true).candidates().contains(new SectionKey(5,0,0)));
        assertEquals(16,CasterVolume.select(center,sun(0),true).extrusion());
        var mirrored=CasterVolume.select(center,sun(1.3),true);
        assertTrue(mirrored.candidates().contains(new SectionKey(-5,0,0)));
        assertFalse(mirrored.candidates().contains(new SectionKey(5,0,0)));
    }

    @Test void nearbyMaterialAndLocalLightWindowAlwaysComesFirst() {
        for(double angle:new double[]{-1.3,0,1.3}) {
            var window=CasterVolume.select(center,sun(angle),true,7.9,-7.9,7.9);
            var local=new HashSet<>(window.candidates().subList(0,125));
            for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=2;z++)assertTrue(local.contains(new SectionKey(x,y,z)));
        }
    }

    @Test void noCelestialLightAndComparisonRestoreTheOldCube() {
        for(var window:List.of(CasterVolume.select(center,sun(-1.3),false),CasterVolume.select(center,ShadowLight.none(),true))) {
            assertEquals(343,window.candidates().size());
            assertEquals(0,window.extrusion());
            assertFalse(window.candidates().contains(new SectionKey(5,0,0)));
        }
    }

    @Test void admissionFiltersMissingChunksBeforeTheCapAndReportsLostCoverage() {
        var keys=new ArrayList<SectionKey>();
        for(int i=0;i<600;i++)keys.add(new SectionKey(i,0,0));
        var window=new CasterVolume.Window(keys,48);
        var sparse=CasterVolume.admit(window,-4,19,key->key.x()>=400);
        assertEquals(200,sparse.sections().size());
        assertEquals(400,sparse.sections().getFirst().x());
        assertEquals(0,sparse.deferred());
        var full=CasterVolume.admit(window,-4,19,key->true);
        assertEquals(384,full.sections().size());
        assertEquals(600,full.eligible());
        assertEquals(216,full.deferred());
        assertTrue(CasterVolume.admit(window,1,19,key->true).sections().isEmpty());
    }

    @Test void actualCameraOffsetAndLargeWorldCoordinatesPreserveSelection() {
        var east=CasterVolume.select(center,sun(0),true,7.9,0,0);
        var west=CasterVolume.select(center,sun(0),true,-7.9,0,0);
        assertTrue(east.candidates().contains(new SectionKey(3,0,1)));
        assertFalse(west.candidates().contains(new SectionKey(3,0,1)));
        var remote=new SectionKey(1_875_000,-4,-1_875_000);
        var translated=CasterVolume.select(remote,sun(0),true,7.9,0,0);
        assertEquals(east.candidates(),translated.candidates().stream().map(key->new SectionKey(key.x()-remote.x(),key.y()-remote.y(),key.z()-remote.z())).toList());
    }

    @Test void sweptSphereRejectsCornersAndDistanceMatchesIndependentSampling() {
        assertFalse(CasterVolume.select(center,sun(0),true).candidates().contains(new SectionKey(3,0,3)));
        var random=new Random(17);
        for(int trial=0;trial<100;trial++) {
            int x=random.nextInt(13)-6,y=random.nextInt(13)-6,z=random.nextInt(13)-6;
            var direction=new Vector3f(random.nextFloat()-.5f,random.nextFloat()-.5f,random.nextFloat()-.5f).normalize();
            double exact=CasterVolume.distanceSquared(x,y,z,direction,48),sampled=Double.POSITIVE_INFINITY;
            for(int step=0;step<=4096;step++) {
                double t=48.0*step/4096;
                double dx=Math.max(0,Math.abs(direction.x*t-x*16)-8);
                double dy=Math.max(0,Math.abs(direction.y*t-y*16)-8);
                double dz=Math.max(0,Math.abs(direction.z*t-z*16)-8);
                sampled=Math.min(sampled,dx*dx+dy*dy+dz*dz);
            }
            assertTrue(exact<=sampled+1e-7);
            assertEquals(sampled,exact,.0001);
        }
    }
}
