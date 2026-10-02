package com.voxellight.world;

/** Owned geometry limits, including temporary overlap while a replacement batch is prepared. */
public final class CasterGeometryBudget {
    public static final long RESIDENT_BYTES = 32L * 1024 * 1024;
    public static final long SECTION_BYTES = 4L * 1024 * 1024;
    public static final long STAGING_BYTES = 8L * 1024 * 1024;
    public static final int REPLACEMENT_SECTIONS = 8;

    public static final class Exceeded extends RuntimeException {
        private final long nextBytes;
        public long nextBytes() { return nextBytes; }
        public Exceeded(long nextBytes) { super("Caster geometry exceeds resident (32 MiB), section (4 MiB) or replacement staging (8 MiB) budget"); this.nextBytes = nextBytes; }
    }

    private CasterGeometryBudget() { }

    /** retiring is the complete old batch, staged is new geometry already uploaded in this transaction. */
    public static void check(long resident, long retiring, long staged, long next) {
        if (resident < 0 || retiring < 0 || retiring > resident || staged < 0 || next < 0) throw new IllegalArgumentException("Invalid geometry accounting");
        if (next > SECTION_BYTES || staged + next > STAGING_BYTES
                || resident + staged + next > RESIDENT_BYTES + STAGING_BYTES
                || resident - retiring + staged + next > RESIDENT_BYTES) throw new Exceeded(next);
    }
}
