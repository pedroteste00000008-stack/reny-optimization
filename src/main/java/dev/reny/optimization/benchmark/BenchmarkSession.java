package dev.reny.optimization.benchmark;

import java.io.File;
import java.util.UUID;

import dev.reny.optimization.profiler.DurationSeriesSnapshot;
import dev.reny.optimization.profiler.InternalProfiler;
import dev.reny.optimization.profiler.ProfilerCapture;
import dev.reny.optimization.profiler.ProfilerSnapshot;

/** Explicit warmup/measurement benchmark state machine backed by the internal profiler. */
public final class BenchmarkSession {

    private final InternalProfiler profiler;
    private final BenchmarkScenario scenario;
    private final BenchmarkEnvironment environment;
    private final long configuredWarmupMillis;
    private final long configuredMeasurementMillis;
    private final File outputRoot;
    private final BenchmarkClock clock;
    private final String runId;

    private State state = State.CREATED;
    private long warmupStartNanos;
    private long warmupStartedAtMillis;
    private long measurementStartNanos;
    private long measurementStartedAtMillis;
    private long frameCutoffId;
    private long tickCutoffId;
    private ProfilerSnapshot.RuntimeSnapshot runtimeStart;
    private ProfilerCapture capture;

    public BenchmarkSession(InternalProfiler profiler, BenchmarkScenario scenario, BenchmarkContext context,
        long configuredWarmupMillis, long configuredMeasurementMillis, File outputRoot) {
        this(
            profiler,
            scenario,
            context,
            configuredWarmupMillis,
            configuredMeasurementMillis,
            outputRoot,
            SystemClock.INSTANCE);
    }

    public BenchmarkSession(InternalProfiler profiler, BenchmarkScenario scenario, BenchmarkContext context,
        long configuredWarmupMillis, long configuredMeasurementMillis, File outputRoot, BenchmarkClock clock) {
        if (profiler == null || scenario == null || context == null || outputRoot == null || clock == null) {
            throw new IllegalArgumentException("benchmark arguments must not be null");
        }
        if (configuredWarmupMillis < 0L || configuredMeasurementMillis <= 0L) {
            throw new IllegalArgumentException("warmup must be >= 0 and measurement must be > 0");
        }
        this.profiler = profiler;
        this.scenario = scenario;
        environment = BenchmarkEnvironment.capture(context);
        this.configuredWarmupMillis = configuredWarmupMillis;
        this.configuredMeasurementMillis = configuredMeasurementMillis;
        this.outputRoot = outputRoot;
        this.clock = clock;
        runId = "run-" + clock.currentTimeMillis()
            + '-'
            + UUID.randomUUID()
                .toString()
                .substring(0, 8);
    }

    public void startWarmup() {
        require(State.CREATED);
        warmupStartedAtMillis = clock.currentTimeMillis();
        warmupStartNanos = clock.nanoTime();
        state = State.WARMUP;
    }

    public boolean shouldBeginMeasurement() {
        return state == State.WARMUP && elapsedNanos(warmupStartNanos) >= configuredWarmupMillis * 1_000_000L;
    }

    public void beginMeasurement() {
        require(State.WARMUP);
        if (!shouldBeginMeasurement()) {
            throw new IllegalStateException("Configured benchmark warmup has not completed");
        }
        ProfilerCapture startedCapture = profiler.beginBenchmarkCapture();
        try {
            ProfilerSnapshot boundary = profiler.snapshot();
            frameCutoffId = boundary.getCurrentFrameId();
            tickCutoffId = boundary.getCurrentTickId();
            runtimeStart = boundary.getRuntime();
            measurementStartedAtMillis = clock.currentTimeMillis();
            measurementStartNanos = clock.nanoTime();
            capture = startedCapture;
            state = State.MEASURING;
        } catch (RuntimeException exception) {
            profiler.discardBenchmarkCapture(startedCapture);
            throw exception;
        }
    }

    public boolean shouldFinishMeasurement() {
        return state == State.MEASURING
            && elapsedNanos(measurementStartNanos) >= configuredMeasurementMillis * 1_000_000L;
    }

    public File finish() {
        require(State.MEASURING);
        if (!shouldFinishMeasurement()) {
            throw new IllegalStateException("Configured benchmark measurement window has not completed");
        }
        long completedNanos = clock.nanoTime();
        long completedAtMillis = clock.currentTimeMillis();
        ProfilerSnapshot end = profiler.snapshot();
        ProfilerCapture.Snapshot captured = profiler.finishBenchmarkCapture(capture);
        capture = null;
        if (!captured.isComplete()) {
            throw new IllegalStateException(
                "Benchmark capture overflow/truncation: frame_dropped=" + captured.getFrames()
                    .getDroppedSamples()
                    + " tick_dropped="
                    + captured.getTicks()
                        .getDroppedSamples());
        }
        DurationSeriesSnapshot frames = captured.getFrames()
            .afterId(frameCutoffId);
        DurationSeriesSnapshot ticks = captured.getTicks()
            .afterId(tickCutoffId);
        BenchmarkResult result = new BenchmarkResult(
            runId,
            scenario,
            environment,
            warmupStartedAtMillis,
            measurementStartedAtMillis,
            completedAtMillis,
            configuredWarmupMillis,
            configuredMeasurementMillis,
            measurementStartNanos - warmupStartNanos,
            Math.max(0L, completedNanos - measurementStartNanos),
            frames,
            ticks,
            runtimeStart,
            end.getRuntime(),
            true);
        File directory = BenchmarkExporter.export(result, outputRoot);
        state = State.COMPLETE;
        return directory;
    }

    /** Releases a formal capture when the controller abandons this session. */
    public void abort() {
        ProfilerCapture current = capture;
        capture = null;
        if (current != null) {
            profiler.discardBenchmarkCapture(current);
        }
    }

    public String getRunId() {
        return runId;
    }

    public State getState() {
        return state;
    }

    public BenchmarkScenario getScenario() {
        return scenario;
    }

    public BenchmarkEnvironment getEnvironment() {
        return environment;
    }

    public long getCurrentPhaseElapsedMillis() {
        long start;
        if (state == State.WARMUP) {
            start = warmupStartNanos;
        } else if (state == State.MEASURING) {
            start = measurementStartNanos;
        } else {
            return 0L;
        }
        return Math.max(0L, (clock.nanoTime() - start) / 1_000_000L);
    }

    private long elapsedNanos(long startNanos) {
        return Math.max(0L, clock.nanoTime() - startNanos);
    }

    private void require(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected benchmark state " + expected + " but was " + state);
        }
    }

    public enum State {
        CREATED,
        WARMUP,
        MEASURING,
        COMPLETE
    }

    private enum SystemClock implements BenchmarkClock {

        INSTANCE;

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }
}
