package dev.reny.optimization.benchmark;

import dev.reny.optimization.profiler.DurationSeriesSnapshot;
import dev.reny.optimization.profiler.ProfilerSnapshot;

/** Immutable measured benchmark result ready for local export. */
public final class BenchmarkResult {

    public static final int SCHEMA_VERSION = 2;

    private final String runId;
    private final BenchmarkScenario scenario;
    private final BenchmarkEnvironment environment;
    private final long warmupStartedAtMillis;
    private final long measurementStartedAtMillis;
    private final long completedAtMillis;
    private final long configuredWarmupMillis;
    private final long configuredMeasurementMillis;
    private final long actualWarmupNanos;
    private final long actualMeasurementNanos;
    private final DurationSeriesSnapshot frames;
    private final DurationSeriesSnapshot ticks;
    private final ProfilerSnapshot.RuntimeSnapshot runtimeStart;
    private final ProfilerSnapshot.RuntimeSnapshot runtimeEnd;
    private final boolean captureComplete;

    BenchmarkResult(String runId, BenchmarkScenario scenario, BenchmarkEnvironment environment,
        long warmupStartedAtMillis, long measurementStartedAtMillis, long completedAtMillis,
        long configuredWarmupMillis, long configuredMeasurementMillis, long actualWarmupNanos,
        long actualMeasurementNanos, DurationSeriesSnapshot frames, DurationSeriesSnapshot ticks,
        ProfilerSnapshot.RuntimeSnapshot runtimeStart, ProfilerSnapshot.RuntimeSnapshot runtimeEnd,
        boolean captureComplete) {
        this.runId = runId;
        this.scenario = scenario;
        this.environment = environment;
        this.warmupStartedAtMillis = warmupStartedAtMillis;
        this.measurementStartedAtMillis = measurementStartedAtMillis;
        this.completedAtMillis = completedAtMillis;
        this.configuredWarmupMillis = configuredWarmupMillis;
        this.configuredMeasurementMillis = configuredMeasurementMillis;
        this.actualWarmupNanos = actualWarmupNanos;
        this.actualMeasurementNanos = actualMeasurementNanos;
        this.frames = frames;
        this.ticks = ticks;
        this.runtimeStart = runtimeStart;
        this.runtimeEnd = runtimeEnd;
        this.captureComplete = captureComplete;
    }

    public String getRunId() {
        return runId;
    }

    public BenchmarkScenario getScenario() {
        return scenario;
    }

    public BenchmarkEnvironment getEnvironment() {
        return environment;
    }

    public long getWarmupStartedAtMillis() {
        return warmupStartedAtMillis;
    }

    public long getMeasurementStartedAtMillis() {
        return measurementStartedAtMillis;
    }

    public long getCompletedAtMillis() {
        return completedAtMillis;
    }

    public long getConfiguredWarmupMillis() {
        return configuredWarmupMillis;
    }

    public long getConfiguredMeasurementMillis() {
        return configuredMeasurementMillis;
    }

    public long getActualWarmupNanos() {
        return actualWarmupNanos;
    }

    public long getActualMeasurementNanos() {
        return actualMeasurementNanos;
    }

    public DurationSeriesSnapshot getFrames() {
        return frames;
    }

    public DurationSeriesSnapshot getTicks() {
        return ticks;
    }

    public ProfilerSnapshot.RuntimeSnapshot getRuntimeStart() {
        return runtimeStart;
    }

    public ProfilerSnapshot.RuntimeSnapshot getRuntimeEnd() {
        return runtimeEnd;
    }

    public boolean isCaptureComplete() {
        return captureComplete;
    }
}
