package com.voxellight.world;
/** Optional measured GPU-world budget. Thirty over-budget observations lower quality;
* 120 comfortably under budget raise it, never above the user's selected ceiling. */
public final class AdaptiveQuality  {
    private VisualQuality ceiling=VisualQuality.BALANCED,current=VisualQuality.BALANCED;
    private int over,under;
    private double filtered;
    private double targetMillis=16.67;
    private boolean enabled;
    public void setEnabled(boolean value) {
        enabled=value;
        reset();
    }
    public void setCeiling(VisualQuality value) {
        ceiling=current=value;
        reset();
    }
    public void setTarget(double value) {
        if(!Double.isFinite(value)||value<4||value>50)throw new IllegalArgumentException("GPU target must be 4–50 ms");
        targetMillis=value;
        reset();
    }
    private void reset() {
        over=under=0;
        filtered=0;
        if(!enabled)current=ceiling;
    }
    public VisualQuality observe(long gpuNanos) {
        if(!enabled||gpuNanos<=0||gpuNanos>1_000_000_000L)return current;
        double ms=gpuNanos/1e6;
        filtered=filtered==0?ms:filtered*.9+ms*.1;
        if(filtered>targetMillis*1.08) {
            over++;
            under=0;
        }
        else if(filtered<targetMillis*.78) {
            under++;
            over=0;
        }
        else {
            over=under=0;
        }
        if(over>=30) {
            current=VisualQuality.values()[Math.max(0,current.ordinal()-1)];
            over=under=0;
        }
        if(under>=120) {
            current=VisualQuality.values()[Math.min(ceiling.ordinal(),current.ordinal()+1)];
            over=under=0;
        }
        return current;
    }
    public boolean enabled() {
        return enabled;
    }
    public VisualQuality quality() {
        return current;
    }
    public double targetMillis() {
        return targetMillis;
    }
    public double measuredMillis() {
        return filtered;
    }
}
