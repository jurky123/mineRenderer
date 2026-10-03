package com.voxellight.world;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShadowEpochsTest {
    private ShadowLight sun(double angle){return new ShadowLight(ShadowLight.Source.SUN,ShadowLight.quantize(angle),.45f,angle);}
    @Test void continuousMotionKeepsProjectionKeysFixedAndBlendsOnlyReadyMaps() {
        var epochs=new ShadowEpochs();var initial=epochs.update(sun(1),true,false);
        assertTrue(initial.reset());assertEquals(0,initial.weight());
        for(int i=1;i<90;i++) {
            var update=epochs.update(sun(1+ShadowEpochs.STEP*i/100),true,i>12);
            assertEquals(initial.first().key(),update.first().key());assertEquals(initial.next().key(),update.next().key());
            assertFalse(update.reset());assertFalse(update.rotate());
            if(i<=12)assertEquals(0,update.weight());
            else assertTrue(update.weight()>0 && update.weight()<=i/100f+.00001);
        }
    }
    @Test void completedEndpointIsReusedAcrossRotationAndAngleWrap() {
        var epochs=new ShadowEpochs();double start=Math.PI*2-ShadowEpochs.STEP*.5;
        var initial=epochs.update(sun(start),true,false);
        for(int i=1;i<100;i++)epochs.update(sun((start+ShadowEpochs.STEP*i/100)%(Math.PI*2)),true,true);
        var rotated=epochs.update(sun((start+ShadowEpochs.STEP*1.01)%(Math.PI*2)),true,true);
        assertTrue(rotated.rotate());assertFalse(rotated.reset());assertEquals(initial.next().key(),rotated.first().key());
        assertEquals(0,rotated.weight());assertEquals(1,epochs.rotations());
    }
    @Test void unfinishedFutureAndTimeOrSourceJumpsResetRatherThanSampleStaleMaps() {
        for(double delta:new double[]{ShadowEpochs.STEP*1.2,ShadowEpochs.STEP*3,-ShadowEpochs.STEP*.1}) {
            var epochs=new ShadowEpochs();epochs.update(sun(1),true,false);
            var update=epochs.update(sun(1+delta),true,false);
            assertTrue(update.reset());assertFalse(update.rotate());assertEquals(0,update.weight());assertEquals(sun(1+delta).key(),update.first().key());
        }
        var epochs=new ShadowEpochs();epochs.update(sun(1),true,true);
        var moon=new ShadowLight(ShadowLight.Source.MOON,0,.2f,0);
        assertTrue(epochs.update(moon,true,true).reset());
        assertFalse(epochs.update(ShadowLight.fixed(),true,true).active());
        assertFalse(epochs.update(sun(1),false,true).active());
    }
    @Test void futureCompletionRampsVisibilityInsteadOfJumping() {
        var epochs=new ShadowEpochs();epochs.update(sun(1),true,false);
        assertEquals(0,epochs.update(sun(1+ShadowEpochs.STEP*.8),true,false).weight());
        for(int i=1;i<=16;i++)assertEquals(Math.min(.8f,i*.05f),epochs.update(sun(1+ShadowEpochs.STEP*.8),true,true).weight(),.00001);
    }
    @Test void prebuiltLookaheadKeepsContinuousVisibilityAcrossRollover() {
        var epochs=new ShadowEpochs();var first=epochs.update(sun(1),true,false,false);
        for(int frame=1;frame<100;frame++)epochs.update(sun(1+frame*ShadowEpochs.STEP/100),true,true,true);
        var rotated=epochs.update(sun(1+ShadowEpochs.STEP*1.01),true,true,true);
        assertTrue(rotated.rotate());assertEquals(first.next().key(),rotated.first().key());
        assertEquals(first.future().key(),rotated.next().key());
        assertEquals(.01,rotated.weight(),.00001,"A ready lookahead must not introduce an endpoint construction pause");
    }
    @Test void staticCelestialCacheUsesFarFewerPagesWithoutFreezingLightDirection() {
        var epochs=new ShadowEpochs();
        var current=maps();var next=maps();var future=maps();
        var anchor=new ShadowMapCache.Anchor(0,64,0);boolean ready=false,futureReady=false;long pages=0;int rotations=0;
        for(int frame=0;frame<600;frame++) {
            var update=epochs.update(sun(1+frame*ShadowEpochs.STEP/100),true,ready,futureReady);
            boolean buildFuture=ready && !update.reset() && (!update.rotate() || futureReady);
            if(update.rotate()){var old=current;current=next;next=future;future=old;for(var cache:future)cache.resetValidity();rotations++;}
            for(int i=0;i<3;i++) {
                var a=current[i].plan(anchor,update.first(),true,List.of());
                var b=next[i].plan(anchor,update.next(),true,List.of(),ShadowEpochs.pageBudget(i));
                pages+=a.pages()+b.pages();a.regions().forEach(current[i]::rendered);b.regions().forEach(next[i]::rendered);
                if(buildFuture) {
                    var c=future[i].plan(anchor,update.future(),true,List.of(),ShadowEpochs.pageBudget(i));
                    pages+=c.pages();c.regions().forEach(future[i]::rendered);
                }
            }
            ready=true;for(var cache:next)ready &= !cache.hasPending();
            if(buildFuture){futureReady=true;for(var cache:future)futureReady &= !cache.hasPending();}else futureReady=false;
            if(frame>100)assertEquals((float)(Math.IEEEremainder(sun(1+frame*ShadowEpochs.STEP/100).angleRadians()-update.first().angleRadians(),Math.PI*2)/ShadowEpochs.STEP),update.weight(),.00001);
        }
        assertTrue(rotations>=5);assertTrue(pages<600*96/10,"Stationary static scene must not redraw all pages for every sun increment");
        assertNotEquals(sun(1).direction(),sun(1+ShadowEpochs.STEP*5).direction());
    }
    private ShadowMapCache[] maps(){return new ShadowMapCache[]{new ShadowMapCache(0),new ShadowMapCache(1),new ShadowMapCache(2)};}
}
