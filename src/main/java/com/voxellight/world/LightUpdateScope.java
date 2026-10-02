package com.voxellight.world;

/** Classify only the section-rebuild call made by vanilla's light-packet handler. */
public final class LightUpdateScope {
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();

    public void run(Runnable update) {
        Integer previous = depth.get();
        depth.set(previous == null ? 1 : previous + 1);
        try { update.run(); }
        finally { if (previous == null) depth.remove(); else depth.set(previous); }
    }

    public int sectionReason(int reason) {
        return reason == WorldSceneBridge.GEOMETRY && depth.get() != null ? WorldSceneBridge.LIGHT : reason;
    }
}
