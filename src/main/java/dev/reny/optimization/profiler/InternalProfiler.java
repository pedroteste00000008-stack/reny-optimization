package dev.reny.optimization.profiler;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Allocation-conscious internal profiler used by Forge hooks and future patch modules. */
public final class InternalProfiler {

    public static final long DISABLED_TOKEN = Long.MIN_VALUE;
    private static final long[] FRAME_THRESHOLDS_NANOS = { 16_670_000L, 33_330_000L, 50_000_000L, 100_000_000L,
        250_000_000L };

    private static final InternalProfiler INSTANCE = new InternalProfiler(8192, 2048);

    private final ProfilerConfig config = new ProfilerConfig();
    private final DurationSeries frames;
    private final DurationSeries ticks;
    private final LongAdder[] frameThresholdCounts = new LongAdder[FRAME_THRESHOLDS_NANOS.length];
    private final SectionAccumulator[] sections = new SectionAccumulator[ProfilerSection.values().length];
    private final AtomicLong queuedTasks = new AtomicLong();
    private final AtomicLong activeTasks = new AtomicLong();
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final int captureMaxSamples;
    private final Object captureMonitor = new Object();

    private long frameSequence;
    private long tickSequence;
    private volatile long currentFrameId;
    private volatile long currentTickId;
    private volatile File automaticExportDirectory;
    private volatile boolean shutdownHookInstalled;
    private volatile ProfilerCapture activeCapture;

    public InternalProfiler(int frameCapacity, int tickCapacity) {
        this(frameCapacity, tickCapacity, ProfilerCapture.DEFAULT_MAX_SAMPLES);
    }

    InternalProfiler(int frameCapacity, int tickCapacity, int captureMaxSamples) {
        frames = new DurationSeries(frameCapacity);
        ticks = new DurationSeries(tickCapacity);
        if (captureMaxSamples <= 0) {
            throw new IllegalArgumentException("captureMaxSamples must be positive");
        }
        this.captureMaxSamples = captureMaxSamples;
        for (int i = 0; i < frameThresholdCounts.length; i++) {
            frameThresholdCounts[i] = new LongAdder();
        }
        for (ProfilerSection section : ProfilerSection.values()) {
            sections[section.ordinal()] = new SectionAccumulator();
        }
    }

    public static InternalProfiler get() {
        return INSTANCE;
    }

    public ProfilerConfig getConfig() {
        return config;
    }

    public void initialize(File automaticExportDirectory) {
        this.automaticExportDirectory = automaticExportDirectory;
        installShutdownHook();
    }

    /** Starts the append-only capture used by a formal benchmark session. */
    public ProfilerCapture beginBenchmarkCapture() {
        synchronized (captureMonitor) {
            if (activeCapture != null) {
                throw new IllegalStateException("A benchmark profiler capture is already active");
            }
            ProfilerCapture capture = new ProfilerCapture(captureMaxSamples);
            activeCapture = capture;
            return capture;
        }
    }

    /** Stops a formal capture and returns every retained sample, subject to its explicit overflow gate. */
    public ProfilerCapture.Snapshot finishBenchmarkCapture(ProfilerCapture capture) {
        synchronized (captureMonitor) {
            if (capture == null || activeCapture != capture) {
                throw new IllegalStateException("Benchmark profiler capture is not active");
            }
            activeCapture = null;
            return capture.closeAndSnapshot();
        }
    }

    /** Discards a capture when a benchmark is cancelled or fails before export. */
    public void discardBenchmarkCapture(ProfilerCapture capture) {
        synchronized (captureMonitor) {
            if (activeCapture == capture) {
                activeCapture = null;
                if (capture != null) {
                    capture.close();
                }
            }
        }
    }

    public long beginFrame() {
        if (!config.isGroupEnabled(ProfilerGroup.FRAME)) {
            return DISABLED_TOKEN;
        }
        currentFrameId = ++frameSequence;
        return System.nanoTime();
    }

    public void endFrame(long startNanos) {
        if (startNanos == DISABLED_TOKEN) {
            return;
        }
        long duration = elapsedSince(startNanos);
        recordFrameDurationNanos(currentFrameId, currentTickId, duration);
    }

    public long beginTick() {
        if (!config.isGroupEnabled(ProfilerGroup.TICK)) {
            return DISABLED_TOKEN;
        }
        currentTickId = ++tickSequence;
        return System.nanoTime();
    }

    public void endTick(long startNanos) {
        if (startNanos == DISABLED_TOKEN) {
            return;
        }
        long duration = elapsedSince(startNanos);
        recordTickDurationNanos(currentTickId, currentFrameId, duration);
    }

    public long startSection(ProfilerSection section) {
        if (!config.isGroupEnabled(section.getGroup())) {
            return DISABLED_TOKEN;
        }
        return System.nanoTime();
    }

    public void endSection(ProfilerSection section, long startNanos) {
        if (startNanos == DISABLED_TOKEN) {
            return;
        }
        sections[section.ordinal()].record(elapsedSince(startNanos));
    }

    public long getCurrentFrameId() {
        return currentFrameId;
    }

    public long getCurrentTickId() {
        return currentTickId;
    }

    public void setQueuedTasks(long count) {
        if (config.isGroupEnabled(ProfilerGroup.TASK)) {
            queuedTasks.set(count);
        }
    }

    public void setActiveTasks(long count) {
        if (config.isGroupEnabled(ProfilerGroup.TASK)) {
            activeTasks.set(count);
        }
    }

    public void taskSubmitted() {
        if (config.isGroupEnabled(ProfilerGroup.TASK)) {
            submittedTasks.increment();
        }
    }

    public void taskCompleted() {
        if (config.isGroupEnabled(ProfilerGroup.TASK)) {
            completedTasks.increment();
        }
    }

    public ProfilerSnapshot snapshot() {
        ProfilerSnapshot.SectionSnapshot[] sectionSnapshots = new ProfilerSnapshot.SectionSnapshot[ProfilerSection
            .values().length];
        for (ProfilerSection section : ProfilerSection.values()) {
            SectionAccumulator accumulator = sections[section.ordinal()];
            sectionSnapshots[section.ordinal()] = new ProfilerSnapshot.SectionSnapshot(
                section,
                accumulator.calls.sum(),
                accumulator.totalNanos.sum(),
                accumulator.maxNanos.get());
        }

        long[] thresholds = new long[frameThresholdCounts.length];
        for (int i = 0; i < thresholds.length; i++) {
            thresholds[i] = frameThresholdCounts[i].sum();
        }

        return new ProfilerSnapshot(
            System.currentTimeMillis(),
            config.isEnabled(),
            currentFrameId,
            currentTickId,
            frames.snapshot(),
            ticks.snapshot(),
            thresholds,
            sectionSnapshots,
            captureRuntime());
    }

    public File exportNow(File directory) {
        ProfilerSnapshot snapshot = snapshot();
        File output = new File(directory, "snapshot-" + snapshot.getCreatedAtMillis());
        ProfilerExporter.export(snapshot, output);
        return output;
    }

    boolean recordFrameDurationNanos(long frameId, long tickId, long durationNanos) {
        frames.record(frameId, tickId, durationNanos);
        ProfilerCapture capture = activeCapture;
        boolean captured = false;
        if (capture != null) {
            synchronized (captureMonitor) {
                if (activeCapture == capture) {
                    captured = capture.recordFrame(frameId, tickId, durationNanos);
                }
            }
        }
        for (int i = 0; i < FRAME_THRESHOLDS_NANOS.length; i++) {
            if (durationNanos > FRAME_THRESHOLDS_NANOS[i]) {
                frameThresholdCounts[i].increment();
            }
        }
        return captured;
    }

    boolean recordTickDurationNanos(long tickId, long frameId, long durationNanos) {
        ticks.record(tickId, frameId, durationNanos);
        ProfilerCapture capture = activeCapture;
        boolean captured = false;
        if (capture != null) {
            synchronized (captureMonitor) {
                if (activeCapture == capture) {
                    captured = capture.recordTick(tickId, frameId, durationNanos);
                }
            }
        }
        return captured;
    }

    private static long elapsedSince(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private ProfilerSnapshot.RuntimeSnapshot captureRuntime() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage();
        long gcCount = 0L;
        long gcTimeMillis = 0L;
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count >= 0L) {
                gcCount += count;
            }
            if (time >= 0L) {
                gcTimeMillis += time;
            }
        }
        return new ProfilerSnapshot.RuntimeSnapshot(
            heap.getUsed(),
            heap.getCommitted(),
            gcCount,
            gcTimeMillis,
            queuedTasks.get(),
            activeTasks.get(),
            submittedTasks.sum(),
            completedTasks.sum());
    }

    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        synchronized (this) {
            if (shutdownHookInstalled) {
                return;
            }
            Runtime.getRuntime()
                .addShutdownHook(new Thread(new Runnable() {

                    @Override
                    public void run() {
                        File directory = automaticExportDirectory;
                        if (directory == null || !config.isEnabled()) {
                            return;
                        }
                        try {
                            exportNow(directory);
                        } catch (RuntimeException ignored) {
                            // Shutdown export is best effort; never prevent JVM termination.
                        }
                    }
                }, "reny-profiler-export"));
            shutdownHookInstalled = true;
        }
    }

    private static final class SectionAccumulator {

        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long durationNanos) {
            calls.increment();
            totalNanos.add(durationNanos);
            long observed = maxNanos.get();
            while (durationNanos > observed && !maxNanos.compareAndSet(observed, durationNanos)) {
                observed = maxNanos.get();
            }
        }
    }
}
