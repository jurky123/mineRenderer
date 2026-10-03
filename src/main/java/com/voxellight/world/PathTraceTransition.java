package com.voxellight.world;

/** Display-time batch transition. Freeze holds the blend, including through resume. */
public final class PathTraceTransition {
    public static final long DURATION_NS = 150_000_000L;
    private long started, frozenAt;
    private boolean active, frozen;

    public void begin(long now) { started=now; active=true; if(frozen)frozenAt=now; }
    public float blend(long now) {
        if(!active)return 1;
        float t=Math.clamp((float)((frozen?frozenAt:now)-started)/DURATION_NS,0,1);
        return t*t*(3-2*t);
    }
    public boolean ready(long now) { return blend(now)>=1; }
    public void freeze(boolean value,long now) {
        if(value==frozen)return;
        if(value)frozenAt=now;
        else if(active)started+=now-frozenAt;
        frozen=value;
    }
    public void reset() { active=false; }
}
