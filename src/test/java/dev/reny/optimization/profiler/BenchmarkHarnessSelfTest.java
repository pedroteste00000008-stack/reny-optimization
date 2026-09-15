package dev.reny.optimization.profiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import dev.reny.optimization.benchmark.BenchmarkClock;
import dev.reny.optimization.benchmark.BenchmarkContext;
import dev.reny.optimization.benchmark.BenchmarkScenario;
import dev.reny.optimization.benchmark.BenchmarkSession;
import dev.reny.optimization.benchmark.BenchmarkStatistics;

/** Dependency-free verification for benchmark phase isolation, schema, and local export. */
public final class BenchmarkHarnessSelfTest {

    private int passed;

    public static void main(String[] args) throws Exception {
        new BenchmarkHarnessSelfTest().run();
    }

    private void run() throws Exception {
        System.setProperty("reny.commit.sha", "test-commit");
        testScenarioCatalog();
        testMeasuredStatistics();
        testWarmupIsolationAndExport();
        testRepeatedRunsDoNotOverwrite();
        testStateAndDurationValidation();
        testCaptureOverflowIsRejected();
        System.out.println("BenchmarkHarnessSelfTest: " + passed + " tests passed");
    }

    private void testScenarioCatalog() {
        equal(9L, BenchmarkScenario.values().length, "scenario count");
        check(BenchmarkScenario.byId("BENCH-01") == BenchmarkScenario.STATIONARY_RENDER, "BENCH-01 lookup");
        check(BenchmarkScenario.byId("bench-99") == BenchmarkScenario.MONSTER, "case-insensitive BENCH-99 lookup");
        pass();
    }

    private void testMeasuredStatistics() {
        DurationSeries series = new DurationSeries(8);
        series.record(1L, 0L, 10_000_000L);
        series.record(2L, 0L, 20_000_000L);
        series.record(3L, 0L, 40_000_000L);
        series.record(4L, 0L, 120_000_000L);
        BenchmarkStatistics statistics = BenchmarkStatistics.from(series.snapshot(), true);
        equal(4L, statistics.getSampleCount(), "statistics sample count");
        equal(20_000_000L, statistics.getP50Nanos(), "statistics p50");
        equal(120_000_000L, statistics.getP99Nanos(), "statistics p99");
        equal(3L, statistics.getFrameThresholdCount(0), ">16.67 ms measured count");
        equal(1L, statistics.getFrameThresholdCount(3), ">100 ms measured count");
        pass();
    }

    private void testWarmupIsolationAndExport() throws Exception {
        InternalProfiler profiler = new InternalProfiler(32, 32);
        ManualClock clock = new ManualClock(1_000L);
        File root = Files.createTempDirectory("reny-benchmark-warmup")
            .toFile();
        BenchmarkSession session = new BenchmarkSession(
            profiler,
            BenchmarkScenario.STATIONARY_RENDER,
            context(),
            1_000L,
            2_000L,
            root,
            clock);

        session.startWarmup();
        long warmupFrame = profiler.beginFrame();
        profiler.endFrame(warmupFrame);
        long warmupTick = profiler.beginTick();
        profiler.endTick(warmupTick);
        check(!session.shouldBeginMeasurement(), "warmup must not finish early");
        clock.advanceMillis(1_000L);
        check(session.shouldBeginMeasurement(), "configured warmup should become ready");
        session.beginMeasurement();

        long measuredFrameId = profiler.getCurrentFrameId() + 1L;
        long measuredTickId = profiler.getCurrentTickId() + 1L;
        profiler.recordFrameDurationNanos(measuredFrameId, measuredTickId, 20_000_000L);
        profiler.recordTickDurationNanos(measuredTickId, measuredFrameId, 30_000_000L);
        check(!session.shouldFinishMeasurement(), "measurement must not finish early");
        clock.advanceMillis(2_000L);
        check(session.shouldFinishMeasurement(), "configured measurement should become ready");

        File output = session.finish();
        File environment = new File(output, "environment.json");
        File summary = new File(output, "summary.json");
        File frames = new File(output, "frames.csv");
        File ticks = new File(output, "ticks.csv");
        check(environment.isFile() && summary.isFile() && frames.isFile() && ticks.isFile(), "required export files");

        String frameText = read(frames);
        String tickText = read(ticks);
        String summaryText = read(summary);
        String environmentText = read(environment);
        check(frameText.contains(measuredFrameId + "," + measuredTickId + ",20000000"), "measured frame exported");
        check(!frameText.contains("1,1,"), "warmup frame excluded");
        check(tickText.contains(measuredTickId + "," + measuredFrameId + ",30000000"), "measured tick exported");
        check(summaryText.contains("\"sample_count\": 1"), "summary uses measured window");
        check(summaryText.contains("\"schema_version\": 2"), "summary schema version");
        check(summaryText.contains("\"complete\": true"), "formal capture completeness");
        check(summaryText.contains("\"actual_ms\": 1000.0"), "actual warmup persisted");
        check(summaryText.contains("\"actual_ms\": 2000.0"), "actual measurement persisted");
        check(environmentText.contains("\"commit_sha\": \"test-commit\""), "commit SHA persisted");
        check(environmentText.contains("\"vsync\": false"), "VSync persisted");
        pass();
    }

    private void testRepeatedRunsDoNotOverwrite() throws Exception {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        ManualClock clock = new ManualClock(5_000L);
        File root = Files.createTempDirectory("reny-benchmark-unique")
            .toFile();
        BenchmarkSession first = new BenchmarkSession(
            profiler,
            BenchmarkScenario.ENTITY_STRESS,
            context(),
            0L,
            1L,
            root,
            clock);
        first.startWarmup();
        first.beginMeasurement();
        clock.advanceMillis(1L);
        File firstOutput = first.finish();

        BenchmarkSession second = new BenchmarkSession(
            profiler,
            BenchmarkScenario.ENTITY_STRESS,
            context(),
            0L,
            1L,
            root,
            clock);
        second.startWarmup();
        second.beginMeasurement();
        clock.advanceMillis(1L);
        File secondOutput = second.finish();

        check(
            !first.getRunId()
                .equals(second.getRunId()),
            "run IDs must be unique");
        check(!firstOutput.equals(secondOutput), "repeated runs must use different directories");
        check(firstOutput.isDirectory() && secondOutput.isDirectory(), "both repeated runs preserved");
        pass();
    }

    private void testStateAndDurationValidation() throws Exception {
        ManualClock clock = new ManualClock(10_000L);
        BenchmarkSession session = new BenchmarkSession(
            new InternalProfiler(8, 8),
            BenchmarkScenario.LIGHTING_TORTURE,
            context(),
            10L,
            20L,
            Files.createTempDirectory("reny-benchmark-state")
                .toFile(),
            clock);

        boolean wrongState = false;
        try {
            session.beginMeasurement();
        } catch (IllegalStateException expected) {
            wrongState = true;
        }
        check(wrongState, "measurement cannot begin before warmup");

        session.startWarmup();
        boolean earlyWarmup = false;
        try {
            session.beginMeasurement();
        } catch (IllegalStateException expected) {
            earlyWarmup = true;
        }
        check(earlyWarmup, "configured warmup duration must be enforced");

        clock.advanceMillis(10L);
        session.beginMeasurement();
        boolean earlyMeasurement = false;
        try {
            session.finish();
        } catch (IllegalStateException expected) {
            earlyMeasurement = true;
        }
        check(earlyMeasurement, "configured measurement duration must be enforced");
        pass();
    }

    private void testCaptureOverflowIsRejected() throws Exception {
        ManualClock clock = new ManualClock(20_000L);
        InternalProfiler profiler = new InternalProfiler(8, 8, 2);
        BenchmarkSession session = new BenchmarkSession(
            profiler,
            BenchmarkScenario.STATIONARY_RENDER,
            context(),
            0L,
            1L,
            Files.createTempDirectory("reny-benchmark-overflow")
                .toFile(),
            clock);
        session.startWarmup();
        session.beginMeasurement();
        profiler.recordFrameDurationNanos(1L, 1L, 1_000L);
        profiler.recordFrameDurationNanos(2L, 2L, 1_000L);
        profiler.recordFrameDurationNanos(3L, 3L, 1_000L);
        profiler.recordTickDurationNanos(1L, 1L, 1_000L);
        profiler.recordTickDurationNanos(2L, 2L, 1_000L);
        profiler.recordTickDurationNanos(3L, 3L, 1_000L);
        clock.advanceMillis(1L);
        boolean rejected = false;
        try {
            session.finish();
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage()
                .contains("overflow/truncation");
        } finally {
            session.abort();
        }
        check(rejected, "capture overflow must invalidate the benchmark");
        pass();
    }

    private static BenchmarkContext context() {
        return BenchmarkContext.builder()
            .display(1280, 720, 8, false, 0)
            .shader("none", "none", "none")
            .world("12345", "self-test", "stationary", "day-clear")
            .configHash("self-test-config")
            .build();
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void pass() {
        passed++;
    }

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class ManualClock implements BenchmarkClock {

        private long nanos;
        private long millis;

        private ManualClock(long initialMillis) {
            millis = initialMillis;
            nanos = initialMillis * 1_000_000L;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long currentTimeMillis() {
            return millis;
        }

        private void advanceMillis(long amount) {
            millis += amount;
            nanos += amount * 1_000_000L;
        }
    }
}
