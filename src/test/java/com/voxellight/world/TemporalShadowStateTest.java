package com.voxellight.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TemporalShadowStateTest {
    private final TemporalShadowState.Key key=new TemporalShadowState.Key(1,1,1,1,ShadowLight.Source.SUN);
    private TemporalShadowState.Frame frame(double x,Matrix4f view,TemporalShadowState.Key key,long time) {
        var projection=new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,500,true).rotateZ(.02f).translate(.01f,0,0);
        return new TemporalShadowState.Frame(x,64,0,new Matrix4f(projection).mul(view),view,key,.3,time);
    }
    @Test void memoryBudgetSupports1440pButFallsBackAt4k() {
        assertEquals(117_964_800,TemporalShadowState.targetBytes(2560,1440));
        assertTrue(TemporalShadowState.targetBytes(2560,1440)<=TemporalShadowState.TARGET_LIMIT);
        assertTrue(TemporalShadowState.targetBytes(3840,2160)>TemporalShadowState.TARGET_LIMIT);
        assertThrows(IllegalArgumentException.class,()->TemporalShadowState.targetBytes(0,1));
        assertThrows(ArithmeticException.class,()->TemporalShadowState.targetBytes(Integer.MAX_VALUE,Integer.MAX_VALUE));
    }
    @Test void firstFrameSeedsAndSnapshotsMutableMatricesBeforeReuse() {
        var state=new TemporalShadowState();var view=new Matrix4f();var first=frame(0,view,key,1);
        var admission=state.admit(first);assertFalse(admission.reuse());state.commit(first,admission);
        var expected=new Matrix4f(first.worldToClip());view.rotateY(1);((Matrix4f)first.worldToClip()).zero();
        var second=state.admit(frame(.1,new Matrix4f().rotateY(.01f),key,16_000_001));
        assertTrue(second.reuse());assertEquals(expected,second.previousWorldToClip());assertEquals(.1f,second.cameraDelta().x,1e-7);
    }
    @Test void changesCutsAndLongFrameGapsDoNotReuseStaleVisibility() {
        for(var changed:List.of(new TemporalShadowState.Key(2,1,1,1,ShadowLight.Source.SUN),new TemporalShadowState.Key(1,2,1,1,ShadowLight.Source.SUN),
                new TemporalShadowState.Key(1,1,2,1,ShadowLight.Source.SUN),new TemporalShadowState.Key(1,1,1,2,ShadowLight.Source.SUN),
                new TemporalShadowState.Key(1,1,1,1,ShadowLight.Source.MOON))) {
            var state=seed();assertFalse(state.admit(frame(0,new Matrix4f(),changed,16_000_001)).reuse());
        }
        assertFalse(seed().admit(frame(9,new Matrix4f(),key,16_000_001)).reuse());
        assertFalse(seed().admit(frame(0,new Matrix4f().rotateY(1.2f),key,16_000_001)).reuse());
        assertFalse(seed().admit(frame(0,new Matrix4f(),key,300_000_001)).reuse());
        var state=seed();state.invalidate("toggle");assertFalse(state.admit(frame(0,new Matrix4f(),key,16_000_001)).reuse());
    }
    @Test void cameraReprojectionKeepsDoublePrecisionWorldMotionAndTheActualBobProjection() {
        var state=new TemporalShadowState();var previous=frame(29_999_000,new Matrix4f().rotateY(.2f),key,1);
        state.commit(previous,state.admit(previous));
        var current=frame(29_999_000.125,new Matrix4f().rotateY(.21f),key,16_000_001);
        var admission=state.admit(current);assertTrue(admission.reuse());assertEquals(.125f,admission.cameraDelta().x);
        var currentRelativePoint=new Vector3f(1,2,-20);
        var recovered=new Matrix4f(current.worldToClip()).invert().transformProject(new Matrix4f(current.worldToClip()).transformProject(new Vector3f(currentRelativePoint)));
        var actual=admission.previousWorldToClip().transformProject(recovered.add(admission.cameraDelta()));
        var expected=new Matrix4f(previous.worldToClip()).transformProject(new Vector3f(1.125f,2,-20));
        assertEquals(expected.x,actual.x,1e-4);assertEquals(expected.y,actual.y,1e-4);assertEquals(expected.z,actual.z,1e-4);
    }
    @Test void clippingBoundsSmallChangesAndStrongChangesRespondImmediately() {
        assertEquals(.43f,TemporalShadowState.blend(.4f,.44f,.3f,.6f),1e-6);
        assertEquals(0,TemporalShadowState.blend(0,1,0,1));
        assertEquals(1,TemporalShadowState.blend(1,0,0,1));
        assertEquals(.3375f,TemporalShadowState.blend(.3f,.99f,.2f,.35f),1e-6);
    }
    private TemporalShadowState seed(){var state=new TemporalShadowState();var frame=frame(0,new Matrix4f(),key,1);state.commit(frame,state.admit(frame));return state;}
}
