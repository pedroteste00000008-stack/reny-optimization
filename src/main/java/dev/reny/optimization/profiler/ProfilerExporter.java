package dev.reny.optimization.profiler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Writes profiler snapshots to stable JSON/CSV files without third-party dependencies. */
public final class ProfilerExporter {

    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;

    private ProfilerExporter() {}

    public static void export(ProfilerSnapshot snapshot, File directory) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create profiler export directory: " + directory);
        }
        try {
            writeSummary(snapshot, new File(directory, "summary.json"));
            writeSeries(snapshot.getFrames(), new File(directory, "frames.csv"), "frame_id", "tick_id");
            writeSeries(snapshot.getTicks(), new File(directory, "ticks.csv"), "tick_id", "frame_id");
            writeSections(snapshot, new File(directory, "sections.csv"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to export profiler snapshot", exception);
        }
    }

    private static void writeSummary(ProfilerSnapshot snapshot, File file) throws IOException {
        PrintWriter out = writer(file);
        try {
            DurationStatistics frameStats = snapshot.getFrames()
                .getStatistics();
            DurationStatistics tickStats = snapshot.getTicks()
                .getStatistics();
            ProfilerSnapshot.RuntimeSnapshot runtime = snapshot.getRuntime();
            out.println("{");
            out.println("  \"created_at_ms\": " + snapshot.getCreatedAtMillis() + ',');
            out.println("  \"enabled\": " + snapshot.isEnabled() + ',');
            out.println("  \"current_frame_id\": " + snapshot.getCurrentFrameId() + ',');
            out.println("  \"current_tick_id\": " + snapshot.getCurrentTickId() + ',');
            out.println("  \"frame\": {");
            writeStatistics(out, snapshot.getFrames(), frameStats, "    ");
            out.println("  },");
            out.println("  \"tick\": {");
            writeStatistics(out, snapshot.getTicks(), tickStats, "    ");
            out.println("  },");
            out.println("  \"frame_threshold_counts\": {");
            out.println("    \"gt_16_67_ms\": " + snapshot.getFrameThresholdCount(0) + ',');
            out.println("    \"gt_33_33_ms\": " + snapshot.getFrameThresholdCount(1) + ',');
            out.println("    \"gt_50_ms\": " + snapshot.getFrameThresholdCount(2) + ',');
            out.println("    \"gt_100_ms\": " + snapshot.getFrameThresholdCount(3) + ',');
            out.println("    \"gt_250_ms\": " + snapshot.getFrameThresholdCount(4));
            out.println("  },");
            out.println("  \"runtime\": {");
            out.println("    \"heap_used_bytes\": " + runtime.getHeapUsedBytes() + ',');
            out.println("    \"heap_committed_bytes\": " + runtime.getHeapCommittedBytes() + ',');
            out.println("    \"gc_count\": " + runtime.getGcCount() + ',');
            out.println("    \"gc_time_ms\": " + runtime.getGcTimeMillis() + ',');
            out.println("    \"queued_tasks\": " + runtime.getQueuedTasks() + ',');
            out.println("    \"active_tasks\": " + runtime.getActiveTasks() + ',');
            out.println("    \"submitted_tasks\": " + runtime.getSubmittedTasks() + ',');
            out.println("    \"completed_tasks\": " + runtime.getCompletedTasks());
            out.println("  }");
            out.println("}");
        } finally {
            out.close();
        }
    }

    private static void writeStatistics(PrintWriter out, DurationSeriesSnapshot series, DurationStatistics statistics,
        String indent) {
        out.println(indent + "\"sample_count\": " + statistics.getSampleCount() + ',');
        out.println(indent + "\"total_recorded_samples\": " + series.getTotalSamples() + ',');
        out.println(indent + "\"dropped_samples\": " + series.getDroppedSamples() + ',');
        out.println(indent + "\"min_ms\": " + millis(statistics.getMinNanos()) + ',');
        out.println(indent + "\"mean_ms\": " + millis(statistics.getMeanNanos()) + ',');
        out.println(indent + "\"max_ms\": " + millis(statistics.getMaxNanos()) + ',');
        out.println(indent + "\"p50_ms\": " + millis(statistics.getP50Nanos()) + ',');
        out.println(indent + "\"p95_ms\": " + millis(statistics.getP95Nanos()) + ',');
        out.println(indent + "\"p99_ms\": " + millis(statistics.getP99Nanos()) + ',');
        out.println(indent + "\"p99_9_ms\": " + millis(statistics.getP999Nanos()));
    }

    private static void writeSeries(DurationSeriesSnapshot series, File file, String idName, String correlationName)
        throws IOException {
        PrintWriter out = writer(file);
        try {
            out.println(idName + ',' + correlationName + ",duration_ns,duration_ms");
            for (int i = 0; i < series.size(); i++) {
                long durationNanos = series.getDurationNanos(i);
                out.println(
                    series.getId(
                        i) + "," + series.getCorrelationId(i) + "," + durationNanos + "," + millis(durationNanos));
            }
        } finally {
            out.close();
        }
    }

    private static void writeSections(ProfilerSnapshot snapshot, File file) throws IOException {
        PrintWriter out = writer(file);
        try {
            out.println("section,call_count,total_ns,max_ns,total_ms,max_ms");
            for (ProfilerSnapshot.SectionSnapshot section : snapshot.getSections()) {
                out.println(
                    section.getSection()
                        .name() + ","
                        + section.getCallCount()
                        + ","
                        + section.getTotalNanos()
                        + ","
                        + section.getMaxNanos()
                        + ","
                        + millis(section.getTotalNanos())
                        + ","
                        + millis(section.getMaxNanos()));
            }
        } finally {
            out.close();
        }
    }

    private static PrintWriter writer(File file) throws IOException {
        return new PrintWriter(new BufferedWriter(new FileWriter(file)));
    }

    private static double millis(long nanos) {
        return nanos / NANOS_PER_MILLISECOND;
    }

    private static double millis(double nanos) {
        return nanos / NANOS_PER_MILLISECOND;
    }
}
