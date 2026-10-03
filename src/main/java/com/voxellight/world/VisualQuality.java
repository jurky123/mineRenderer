package com.voxellight.world;

/** Explicit sampling budgets; no frame-time feedback or automatic quality oscillation. */
public enum VisualQuality {
    FAST(8, 16), BALANCED(16, 24), HIGH(32, 32);
    private final int volumeSteps, reflectionSteps;
    VisualQuality(int volumeSteps, int reflectionSteps) { this.volumeSteps = volumeSteps; this.reflectionSteps = reflectionSteps; }
    public int volumeSteps() { return volumeSteps; }
    public int reflectionSteps() { return reflectionSteps; }
}
