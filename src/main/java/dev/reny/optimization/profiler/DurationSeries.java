package dev.reny.optimization.profiler;

/**
 * Single-writer fixed-size ring buffer for duration samples.
 *
 * <p>
 * Recording allocates nothing. Snapshots allocate and retry when a writer is publishing or changes the window while
 * data is copied.
 * </p>
 */
final class DurationSeries {

    private final long[] ids;
    private final long[] correlationIds;
    private final long[] durationsNanos;
    private int cursor;
    private int size;
    private long totalSamples;
    private long droppedSamples;
    private volatile long publishedVersion;

    DurationSeries(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        ids = new long[capacity];
        correlationIds = new long[capacity];
        durationsNanos = new long[capacity];
    }

    void record(long id, long correlationId, long durationNanos) {
        long writingVersion = publishedVersion + 1L;
        publishedVersion = writingVersion;

        int index = cursor;
        ids[index] = id;
        correlationIds[index] = correlationId;
        durationsNanos[index] = durationNanos;
        cursor = (index + 1) % durationsNanos.length;
        if (size < durationsNanos.length) {
            size++;
        } else {
            droppedSamples++;
        }
        totalSamples++;

        publishedVersion = writingVersion + 1L;
    }

    DurationSeriesSnapshot snapshot() {
        for (int attempt = 0; attempt < 3; attempt++) {
            long before = publishedVersion;
            if ((before & 1L) != 0L) {
                continue;
            }

            int localCursor = cursor;
            int localSize = size;
            long localTotalSamples = totalSamples;
            long localDroppedSamples = droppedSamples;
            DurationSeriesSnapshot snapshot = copy(localCursor, localSize, localTotalSamples, localDroppedSamples);
            long after = publishedVersion;
            if (before == after && (after & 1L) == 0L) {
                return snapshot;
            }
        }

        while (true) {
            long before = publishedVersion;
            if ((before & 1L) != 0L) {
                Thread.yield();
                continue;
            }
            int localCursor = cursor;
            int localSize = size;
            long localTotalSamples = totalSamples;
            long localDroppedSamples = droppedSamples;
            DurationSeriesSnapshot snapshot = copy(localCursor, localSize, localTotalSamples, localDroppedSamples);
            long after = publishedVersion;
            if (before == after && (after & 1L) == 0L) {
                return snapshot;
            }
        }
    }

    private DurationSeriesSnapshot copy(int localCursor, int localSize, long localTotalSamples,
        long localDroppedSamples) {
        long[] copiedIds = new long[localSize];
        long[] copiedCorrelations = new long[localSize];
        long[] copiedDurations = new long[localSize];
        int start = localCursor - localSize;
        if (start < 0) {
            start += durationsNanos.length;
        }

        for (int i = 0; i < localSize; i++) {
            int source = (start + i) % durationsNanos.length;
            copiedIds[i] = ids[source];
            copiedCorrelations[i] = correlationIds[source];
            copiedDurations[i] = durationsNanos[source];
        }
        return new DurationSeriesSnapshot(
            localTotalSamples,
            localDroppedSamples,
            copiedIds,
            copiedCorrelations,
            copiedDurations);
    }
}
