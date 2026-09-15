package dev.reny.optimization.profiler;

import java.util.Arrays;

/**
 * Append-only sample storage for one formal benchmark capture.
 *
 * <p>
 * Growth happens only when a capture crosses a capacity boundary; recording within an allocated block remains
 * allocation-free. A hard limit turns an out-of-memory-sized workload into an explicit invalid capture instead of a
 * silently truncated distribution.
 * </p>
 */
final class DurationSeriesCapture {

    private static final int INITIAL_CAPACITY = 2_048;

    private final int maxSamples;
    private long[] ids = new long[INITIAL_CAPACITY];
    private long[] correlationIds = new long[INITIAL_CAPACITY];
    private long[] durationsNanos = new long[INITIAL_CAPACITY];
    private int size;
    private long totalSamples;
    private long droppedSamples;

    DurationSeriesCapture(int maxSamples) {
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("maxSamples must be positive");
        }
        this.maxSamples = maxSamples;
        if (ids.length > maxSamples) {
            ids = new long[maxSamples];
            correlationIds = new long[maxSamples];
            durationsNanos = new long[maxSamples];
        }
    }

    void record(long id, long correlationId, long durationNanos) {
        totalSamples++;
        if (size >= maxSamples) {
            droppedSamples++;
            return;
        }
        ensureCapacity(size + 1);
        ids[size] = id;
        correlationIds[size] = correlationId;
        durationsNanos[size] = durationNanos;
        size++;
    }

    DurationSeriesSnapshot snapshot() {
        return new DurationSeriesSnapshot(
            totalSamples,
            droppedSamples,
            Arrays.copyOf(ids, size),
            Arrays.copyOf(correlationIds, size),
            Arrays.copyOf(durationsNanos, size));
    }

    private void ensureCapacity(int required) {
        if (required <= ids.length) {
            return;
        }
        int next = ids.length * 2;
        if (next < required) {
            next = required;
        }
        if (next > maxSamples) {
            next = maxSamples;
        }
        ids = Arrays.copyOf(ids, next);
        correlationIds = Arrays.copyOf(correlationIds, next);
        durationsNanos = Arrays.copyOf(durationsNanos, next);
    }
}
