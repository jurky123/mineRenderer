package com.voxellight.adapter;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.voxellight.debug.PassMetrics;

/** Never waits for GPU results or overwrites an in-flight query pair. */
final class GpuPassTimer implements AutoCloseable {
    private static final int SLOTS = 4;
    private final GpuQueryPool pool;
    private final float period;
    private final long[] frames = new long[SLOTS];
    private final boolean[] pending = new boolean[SLOTS];
    private long skipped;

    GpuPassTimer(GpuDevice device) {
        period = device.getDeviceInfo().timestampPeriod();
        if (!Float.isFinite(period) || period <= 0) {
            throw new UnsupportedOperationException("No valid GPU timestamp period");
        }
        pool = device.createTimestampQueryPool(SLOTS * 2);
    }

    void poll(long frame, PassMetrics metrics) {
        for (int i = 0; i < SLOTS; i++) {
            if (!pending[i] || frame - frames[i] < 2) {
                continue;
            }
            var values = pool.getValues(i * 2, 2);
            if (values[0].isPresent() && values[1].isPresent()) {
                long ticks = values[1].getAsLong() - values[0].getAsLong();
                // Timestamp valid bits are not exposed by Blaze3D. Reject a wrap rather than invent a duration.
                if (ticks >= 0) {
                    double nanos = ticks * (double) period;
                    if (Double.isFinite(nanos) && nanos < Long.MAX_VALUE) {
                        metrics.completeGpu(frames[i], Math.round(nanos));
                    }
                }
                pending[i] = false;
            }
        }
    }

    int begin(CommandEncoder encoder, long frame) {
        for (int i = 0; i < SLOTS; i++) {
            if (!pending[i]) {
                frames[i] = frame;
                pending[i] = true;
                encoder.writeTimestamp(pool, i * 2);
                return i;
            }
        }
        skipped++;
        return -1;
    }

    void end(CommandEncoder encoder, int slot) {
        if (slot >= 0) {
            encoder.writeTimestamp(pool, slot * 2 + 1);
        }
    }

    long skipped() {
        return skipped;
    }

    @Override
    public void close() {
        pool.close();
    }
}
