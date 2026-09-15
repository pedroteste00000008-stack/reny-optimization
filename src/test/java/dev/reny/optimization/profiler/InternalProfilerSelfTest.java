package dev.reny.optimization.profiler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free verification for the profiler core. */
public final class InternalProfilerSelfTest {

    private int passed;

    public static void main(String[] args) throws Exception {
        new InternalProfilerSelfTest().run();
    }

    private void run() throws Exception {
        testDurationPercentiles();
        testRingKeepsNewestSamples();
        testFormalCaptureRetainsCompleteWindow();
        testConcurrentCaptureCloseHandoff();
        testFrameTickCorrelation();
        testThresholdCounters();
        testDisablePaths();
        testSectionAndTaskMetrics();
        testExport();
        System.out.println("InternalProfilerSelfTest: " + passed + " tests passed");
    }

    private void testDurationPercentiles() {
        DurationStatistics statistics = DurationStatistics.from(new long[] { 1L, 2L, 3L, 4L, 100L });
        equal(5L, statistics.getSampleCount(), "sample count");
        equal(3L, statistics.getP50Nanos(), "p50");
        equal(100L, statistics.getP95Nanos(), "p95");
        equal(100L, statistics.getP99Nanos(), "p99");
        pass();
    }

    private void testRingKeepsNewestSamples() {
        DurationSeries series = new DurationSeries(3);
        series.record(1L, 10L, 100L);
        series.record(2L, 20L, 200L);
        series.record(3L, 30L, 300L);
        series.record(4L, 40L, 400L);
        DurationSeriesSnapshot snapshot = series.snapshot();
        equal(4L, snapshot.getTotalSamples(), "total ring samples");
        equal(3L, snapshot.size(), "ring size");
        equal(2L, snapshot.getId(0), "oldest retained id");
        equal(4L, snapshot.getId(2), "newest retained id");
        equal(1L, snapshot.getDroppedSamples(), "ring dropped sample count");
        pass();
    }

    private void testFormalCaptureRetainsCompleteWindow() {
        InternalProfiler profiler = new InternalProfiler(2, 2, 32);
        ProfilerCapture capture = profiler.beginBenchmarkCapture();
        for (long id = 1L; id <= 20L; id++) {
            profiler.recordFrameDurationNanos(id, id, id * 1_000L);
        }
        for (long id = 1L; id <= 12L; id++) {
            profiler.recordTickDurationNanos(id, id, id * 2_000L);
        }
        ProfilerCapture.Snapshot snapshot = profiler.finishBenchmarkCapture(capture);
        check(snapshot.isComplete(), "formal capture must not truncate within its limit");
        equal(
            20L,
            snapshot.getFrames()
                .size(),
            "complete captured frame count");
        equal(
            12L,
            snapshot.getTicks()
                .size(),
            "complete captured tick count");
        equal(
            0L,
            snapshot.getFrames()
                .getDroppedSamples(),
            "captured frame drops");
        equal(
            0L,
            snapshot.getTicks()
                .getDroppedSamples(),
            "captured tick drops");
        ProfilerCapture hookCapture = profiler.beginBenchmarkCapture();
        long frameStart = profiler.beginFrame();
        profiler.endFrame(frameStart);
        long tickStart = profiler.beginTick();
        profiler.endTick(tickStart);
        ProfilerCapture.Snapshot hookSnapshot = profiler.finishBenchmarkCapture(hookCapture);
        equal(
            1L,
            hookSnapshot.getFrames()
                .size(),
            "hook frame capture");
        equal(
            1L,
            hookSnapshot.getTicks()
                .size(),
            "hook tick capture");
        pass();
    }

    private void testFrameTickCorrelation() {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        profiler.recordTickDurationNanos(7L, 20L, 4_000_000L);
        profiler.recordFrameDurationNanos(21L, 7L, 8_000_000L);
        ProfilerSnapshot snapshot = profiler.snapshot();
        equal(
            7L,
            snapshot.getFrames()
                .getCorrelationId(0),
            "frame -> tick correlation");
        equal(
            20L,
            snapshot.getTicks()
                .getCorrelationId(0),
            "tick -> frame correlation");
        pass();
    }

    private void testConcurrentCaptureCloseHandoff() throws Exception {
        final InternalProfiler profiler = new InternalProfiler(64, 64, 1_000_000);
        final ProfilerCapture capture = profiler.beginBenchmarkCapture();
        final AtomicInteger acceptedFrames = new AtomicInteger();
        final AtomicInteger acceptedTicks = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch firstSample = new CountDownLatch(1);

        Thread frameWriter = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    ready.countDown();
                    start.await();
                    for (long id = 1L; id <= 100_000L; id++) {
                        if (profiler.recordFrameDurationNanos(id, id, 1_000L)) {
                            acceptedFrames.incrementAndGet();
                        }
                        if (id == 1L) {
                            firstSample.countDown();
                        }
                    }
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            }
        }, "reny-profiler-frame-writer");
        Thread tickWriter = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    ready.countDown();
                    start.await();
                    for (long id = 1L; id <= 100_000L; id++) {
                        if (profiler.recordTickDurationNanos(id, id, 2_000L)) {
                            acceptedTicks.incrementAndGet();
                        }
                        if (id == 1L) {
                            firstSample.countDown();
                        }
                    }
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                }
            }
        }, "reny-profiler-tick-writer");

        frameWriter.start();
        tickWriter.start();
        check(ready.await(10L, TimeUnit.SECONDS), "concurrent writers become ready");
        start.countDown();
        check(firstSample.await(10L, TimeUnit.SECONDS), "concurrent writers record first sample");
        ProfilerCapture.Snapshot snapshot = profiler.finishBenchmarkCapture(capture);
        frameWriter.join(10_000L);
        tickWriter.join(10_000L);
        check(!frameWriter.isAlive() && !tickWriter.isAlive(), "concurrent writers terminate");
        if (failure.get() != null) {
            throw new AssertionError("concurrent capture writer failed", failure.get());
        }
        check(snapshot.isComplete(), "concurrent capture remains complete");
        equal(
            acceptedFrames.get(),
            snapshot.getFrames()
                .getTotalSamples(),
            "captured frame count matches accepted writes");
        equal(
            acceptedTicks.get(),
            snapshot.getTicks()
                .getTotalSamples(),
            "captured tick count matches accepted writes");
        equal(
            acceptedFrames.get(),
            snapshot.getFrames()
                .size(),
            "all accepted frames are retained");
        equal(
            acceptedTicks.get(),
            snapshot.getTicks()
                .size(),
            "all accepted ticks are retained");
        pass();
    }

    private void testThresholdCounters() {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        profiler.recordFrameDurationNanos(1L, 1L, 17_000_000L);
        profiler.recordFrameDurationNanos(2L, 1L, 60_000_000L);
        ProfilerSnapshot snapshot = profiler.snapshot();
        equal(2L, snapshot.getFrameThresholdCount(0), ">16.67 ms");
        equal(1L, snapshot.getFrameThresholdCount(1), ">33.33 ms");
        equal(1L, snapshot.getFrameThresholdCount(2), ">50 ms");
        equal(0L, snapshot.getFrameThresholdCount(3), ">100 ms");
        pass();
    }

    private void testDisablePaths() {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        profiler.getConfig()
            .setEnabled(false);
        equal(InternalProfiler.DISABLED_TOKEN, profiler.beginFrame(), "global disable");
        profiler.getConfig()
            .setEnabled(true);
        profiler.getConfig()
            .setGroupEnabled(ProfilerGroup.TICK, false);
        equal(InternalProfiler.DISABLED_TOKEN, profiler.beginTick(), "group disable");
        pass();
    }

    private void testSectionAndTaskMetrics() {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        long section = profiler.startSection(ProfilerSection.CHUNK);
        profiler.endSection(ProfilerSection.CHUNK, section);
        profiler.setQueuedTasks(5L);
        profiler.setActiveTasks(2L);
        profiler.taskSubmitted();
        profiler.taskCompleted();
        ProfilerSnapshot snapshot = profiler.snapshot();
        equal(1L, snapshot.getSections()[ProfilerSection.CHUNK.ordinal()].getCallCount(), "section calls");
        equal(
            5L,
            snapshot.getRuntime()
                .getQueuedTasks(),
            "queued tasks");
        equal(
            2L,
            snapshot.getRuntime()
                .getActiveTasks(),
            "active tasks");
        equal(
            1L,
            snapshot.getRuntime()
                .getSubmittedTasks(),
            "submitted tasks");
        pass();
    }

    private void testExport() throws Exception {
        InternalProfiler profiler = new InternalProfiler(8, 8);
        profiler.recordFrameDurationNanos(9L, 4L, 12_000_000L);
        profiler.recordTickDurationNanos(4L, 9L, 6_000_000L);
        File root = Files.createTempDirectory("reny-profiler-test")
            .toFile();
        File output = profiler.exportNow(root);
        File summary = new File(output, "summary.json");
        File frames = new File(output, "frames.csv");
        File ticks = new File(output, "ticks.csv");
        File sections = new File(output, "sections.csv");
        check(summary.isFile() && frames.isFile() && ticks.isFile() && sections.isFile(), "export files");
        String summaryText = new String(Files.readAllBytes(summary.toPath()), StandardCharsets.UTF_8);
        String frameText = new String(Files.readAllBytes(frames.toPath()), StandardCharsets.UTF_8);
        check(summaryText.contains("\"p99_ms\""), "summary percentile field");
        check(frameText.contains("frame_id,tick_id,duration_ns,duration_ms"), "frame csv header");
        pass();
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
}
