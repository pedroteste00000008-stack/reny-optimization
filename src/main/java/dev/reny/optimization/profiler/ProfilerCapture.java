package dev.reny.optimization.profiler;

/** Complete frame/tick capture owned by one formal benchmark session. */
public final class ProfilerCapture {

    public static final int DEFAULT_MAX_SAMPLES = 1_048_576;

    private final DurationSeriesCapture frames;
    private final DurationSeriesCapture ticks;
    private volatile boolean open = true;

    ProfilerCapture(int maxSamples) {
        frames = new DurationSeriesCapture(maxSamples);
        ticks = new DurationSeriesCapture(maxSamples);
    }

    synchronized boolean recordFrame(long frameId, long tickId, long durationNanos) {
        if (!open) {
            return false;
        }
        frames.record(frameId, tickId, durationNanos);
        return true;
    }

    synchronized boolean recordTick(long tickId, long frameId, long durationNanos) {
        if (!open) {
            return false;
        }
        ticks.record(tickId, frameId, durationNanos);
        return true;
    }

    synchronized void close() {
        open = false;
    }

    synchronized Snapshot closeAndSnapshot() {
        open = false;
        return snapshotUnsafe();
    }

    synchronized Snapshot snapshot() {
        return snapshotUnsafe();
    }

    private Snapshot snapshotUnsafe() {
        return new Snapshot(frames.snapshot(), ticks.snapshot());
    }

    /** Immutable result of a formal capture, including an explicit completeness gate. */
    public static final class Snapshot {

        private final DurationSeriesSnapshot frames;
        private final DurationSeriesSnapshot ticks;

        private Snapshot(DurationSeriesSnapshot frames, DurationSeriesSnapshot ticks) {
            this.frames = frames;
            this.ticks = ticks;
        }

        public DurationSeriesSnapshot getFrames() {
            return frames;
        }

        public DurationSeriesSnapshot getTicks() {
            return ticks;
        }

        public boolean isComplete() {
            return !frames.isTruncated() && !ticks.isTruncated();
        }
    }
}
