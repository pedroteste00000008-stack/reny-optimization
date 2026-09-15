package dev.reny.optimization.profiler;

import java.util.Arrays;

/** Immutable chronological copy of a fixed-size duration window. */
public final class DurationSeriesSnapshot {

    private final long totalSamples;
    private final long droppedSamples;
    private final long[] ids;
    private final long[] correlationIds;
    private final long[] durationsNanos;
    private final DurationStatistics statistics;

    DurationSeriesSnapshot(long totalSamples, long[] ids, long[] correlationIds, long[] durationsNanos) {
        this(totalSamples, 0L, ids, correlationIds, durationsNanos);
    }

    DurationSeriesSnapshot(long totalSamples, long droppedSamples, long[] ids, long[] correlationIds,
        long[] durationsNanos) {
        this.totalSamples = totalSamples;
        this.droppedSamples = droppedSamples;
        this.ids = ids;
        this.correlationIds = correlationIds;
        this.durationsNanos = durationsNanos;
        this.statistics = DurationStatistics.from(durationsNanos);
    }

    public long getTotalSamples() {
        return totalSamples;
    }

    /** Returns the number of samples discarded before this snapshot was taken. */
    public long getDroppedSamples() {
        return droppedSamples;
    }

    /** Returns whether this snapshot cannot represent every recorded sample. */
    public boolean isTruncated() {
        return droppedSamples > 0L;
    }

    public int size() {
        return durationsNanos.length;
    }

    public long getId(int index) {
        return ids[index];
    }

    public long getCorrelationId(int index) {
        return correlationIds[index];
    }

    public long getDurationNanos(int index) {
        return durationsNanos[index];
    }

    public DurationStatistics getStatistics() {
        return statistics;
    }

    /**
     * Returns only retained samples whose monotonic ID is greater than {@code exclusiveId}.
     *
     * <p>
     * This is primarily used by benchmark sessions to exclude warmup samples without resetting the global profiler.
     * </p>
     */
    public DurationSeriesSnapshot afterId(long exclusiveId) {
        int first = 0;
        while (first < ids.length && ids[first] <= exclusiveId) {
            first++;
        }
        if (first == 0) {
            return this;
        }
        int retained = ids.length - first;
        return new DurationSeriesSnapshot(
            retained,
            droppedSamples,
            Arrays.copyOfRange(ids, first, ids.length),
            Arrays.copyOfRange(correlationIds, first, correlationIds.length),
            Arrays.copyOfRange(durationsNanos, first, durationsNanos.length));
    }
}
