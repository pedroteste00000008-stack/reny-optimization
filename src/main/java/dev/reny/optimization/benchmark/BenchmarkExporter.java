package dev.reny.optimization.benchmark;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import dev.reny.optimization.benchmark.BenchmarkEnvironment.ModInfo;
import dev.reny.optimization.profiler.DurationSeriesSnapshot;
import dev.reny.optimization.profiler.ProfilerSnapshot;

/** Durable local-only benchmark export in versioned JSON/CSV formats. */
public final class BenchmarkExporter {

    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;

    private BenchmarkExporter() {}

    public static File export(BenchmarkResult result, File outputRoot) {
        File scenario = new File(
            outputRoot,
            result.getScenario()
                .getId());
        File commit = new File(
            scenario,
            safePath(
                result.getEnvironment()
                    .getRenyCommitSha()));
        File directory = new File(commit, safePath(result.getRunId()));
        if (directory.exists() || !directory.mkdirs()) {
            throw new IllegalStateException(
                "Benchmark run directory already exists or cannot be created: " + directory);
        }
        try {
            writeEnvironment(result, new File(directory, "environment.json"));
            writeSummary(result, new File(directory, "summary.json"));
            writeSeries(result.getFrames(), new File(directory, "frames.csv"), "frame_id", "tick_id");
            writeSeries(result.getTicks(), new File(directory, "ticks.csv"), "tick_id", "frame_id");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to export benchmark result", exception);
        }
        return directory;
    }

    private static void writeEnvironment(BenchmarkResult result, File file) throws IOException {
        BenchmarkEnvironment environment = result.getEnvironment();
        BenchmarkContext context = environment.getContext();
        PrintWriter out = writer(file);
        try {
            out.println("{");
            out.println("  \"schema_version\": " + BenchmarkResult.SCHEMA_VERSION + ',');
            out.println("  \"run_id\": " + quote(result.getRunId()) + ',');
            out.println(
                "  \"benchmark_id\": " + quote(
                    result.getScenario()
                        .getId())
                    + ',');
            out.println("  \"reny\": {");
            out.println("    \"version\": " + quote(environment.getRenyVersion()) + ',');
            out.println("    \"commit_sha\": " + quote(environment.getRenyCommitSha()));
            out.println("  },");
            out.println("  \"minecraft\": {");
            out.println("    \"version\": " + quote(environment.getMinecraftVersion()) + ',');
            out.println("    \"forge_version\": " + quote(environment.getForgeVersion()) + ',');
            out.println("    \"mods\": [");
            writeMods(out, environment.getMods());
            out.println("    ]");
            out.println("  },");
            out.println("  \"java\": {");
            out.println("    \"vendor\": " + quote(environment.getJavaVendor()) + ',');
            out.println("    \"version\": " + quote(environment.getJavaVersion()) + ',');
            out.println("    \"vm\": " + quote(environment.getJavaVm()) + ',');
            out.println("    \"jvm_args\": [");
            writeStrings(out, environment.getJvmArguments(), "      ");
            out.println("    ]");
            out.println("  },");
            out.println("  \"os\": {");
            out.println("    \"name\": " + quote(environment.getOsName()) + ',');
            out.println("    \"version\": " + quote(environment.getOsVersion()) + ',');
            out.println("    \"arch\": " + quote(environment.getOsArch()) + ',');
            out.println("    \"kernel\": " + quote(environment.getKernel()));
            out.println("  },");
            out.println("  \"hardware\": {");
            out.println("    \"cpu\": " + quote(environment.getCpu()) + ',');
            out.println("    \"available_processors\": " + environment.getAvailableProcessors() + ',');
            out.println("    \"physical_ram_bytes\": " + environment.getPhysicalRamBytes() + ',');
            out.println("    \"gpu_vendor\": " + quote(environment.getGpuVendor()) + ',');
            out.println("    \"gpu_renderer\": " + quote(environment.getGpuRenderer()) + ',');
            out.println("    \"driver\": " + quote(environment.getDriver()) + ',');
            out.println("    \"opengl_version\": " + quote(environment.getOpenGlVersion()));
            out.println("  },");
            out.println("  \"display\": {");
            out.println("    \"width\": " + nullable(context.getDisplayWidth()) + ',');
            out.println("    \"height\": " + nullable(context.getDisplayHeight()) + ',');
            out.println("    \"render_distance_chunks\": " + nullable(context.getRenderDistanceChunks()) + ',');
            out.println("    \"vsync\": " + nullable(context.getVsync()) + ',');
            out.println("    \"fps_cap\": " + nullable(context.getFpsCap()));
            out.println("  },");
            out.println("  \"shader\": {");
            out.println("    \"name\": " + quote(context.getShaderName()) + ',');
            out.println("    \"version\": " + quote(context.getShaderVersion()) + ',');
            out.println("    \"preset\": " + quote(context.getShaderPreset()));
            out.println("  },");
            out.println("  \"world\": {");
            out.println("    \"seed\": " + quote(context.getWorldSeed()) + ',');
            out.println("    \"descriptor\": " + quote(context.getWorldDescriptor()) + ',');
            out.println("    \"route\": " + quote(context.getPlayerRoute()) + ',');
            out.println("    \"time_weather\": " + quote(context.getTimeWeather()) + ',');
            out.println("    \"config_hash\": " + quote(context.getConfigHash()));
            out.println("  },");
            out.println("  \"extras\": {");
            writeMap(out, context.getExtras());
            out.println("  },");
            out.println("  \"detailed_samples\": {");
            out.println("    \"frames\": \"frames.csv\",");
            out.println("    \"ticks\": \"ticks.csv\",");
            out.println("    \"summary\": \"summary.json\"");
            out.println("  }");
            out.println("}");
        } finally {
            out.close();
        }
    }

    private static void writeSummary(BenchmarkResult result, File file) throws IOException {
        BenchmarkStatistics frame = BenchmarkStatistics.from(result.getFrames(), true);
        BenchmarkStatistics tick = BenchmarkStatistics.from(result.getTicks(), false);
        ProfilerSnapshot.RuntimeSnapshot start = result.getRuntimeStart();
        ProfilerSnapshot.RuntimeSnapshot end = result.getRuntimeEnd();
        PrintWriter out = writer(file);
        try {
            out.println("{");
            out.println("  \"schema_version\": " + BenchmarkResult.SCHEMA_VERSION + ',');
            out.println("  \"run_id\": " + quote(result.getRunId()) + ',');
            out.println(
                "  \"benchmark_id\": " + quote(
                    result.getScenario()
                        .getId())
                    + ',');
            out.println(
                "  \"benchmark_name\": " + quote(
                    result.getScenario()
                        .getDisplayName())
                    + ',');
            out.println("  \"warmup\": {");
            out.println("    \"started_at_ms\": " + result.getWarmupStartedAtMillis() + ',');
            out.println("    \"configured_ms\": " + result.getConfiguredWarmupMillis() + ',');
            out.println("    \"actual_ms\": " + millis(result.getActualWarmupNanos()));
            out.println("  },");
            out.println("  \"measurement\": {");
            out.println("    \"started_at_ms\": " + result.getMeasurementStartedAtMillis() + ',');
            out.println("    \"completed_at_ms\": " + result.getCompletedAtMillis() + ',');
            out.println("    \"configured_ms\": " + result.getConfiguredMeasurementMillis() + ',');
            out.println("    \"actual_ms\": " + millis(result.getActualMeasurementNanos()));
            out.println("  },");
            out.println("  \"capture\": {");
            out.println("    \"complete\": " + result.isCaptureComplete() + ',');
            out.println("    \"truncated\": false,");
            out.println(
                "    \"frame_samples\": " + result.getFrames()
                    .size() + ',');
            out.println(
                "    \"tick_samples\": " + result.getTicks()
                    .size());
            out.println("  },");
            writeFrameSummary(out, frame);
            out.println(',');
            writeTickSummary(out, tick);
            out.println(',');
            out.println("  \"runtime_delta\": {");
            out.println("    \"heap_start_bytes\": " + start.getHeapUsedBytes() + ',');
            out.println("    \"heap_end_bytes\": " + end.getHeapUsedBytes() + ',');
            out.println("    \"heap_delta_bytes\": " + (end.getHeapUsedBytes() - start.getHeapUsedBytes()) + ',');
            out.println("    \"gc_count\": " + nonNegativeDelta(end.getGcCount(), start.getGcCount()) + ',');
            out.println(
                "    \"gc_time_ms\": " + nonNegativeDelta(end.getGcTimeMillis(), start.getGcTimeMillis()) + ',');
            out.println(
                "    \"submitted_tasks\": " + nonNegativeDelta(end.getSubmittedTasks(), start.getSubmittedTasks())
                    + ',');
            out.println(
                "    \"completed_tasks\": " + nonNegativeDelta(end.getCompletedTasks(), start.getCompletedTasks()));
            out.println("  },");
            out.println("  \"files\": {");
            out.println("    \"environment\": \"environment.json\",");
            out.println("    \"frames\": \"frames.csv\",");
            out.println("    \"ticks\": \"ticks.csv\"");
            out.println("  }");
            out.println("}");
        } finally {
            out.close();
        }
    }

    private static void writeFrameSummary(PrintWriter out, BenchmarkStatistics statistics) {
        out.println("  \"frame\": {");
        writeCommonStatistics(out, statistics, "    ");
        out.println("    \"average_fps\": " + statistics.getAverageFps() + ',');
        out.println("    \"fps_1_percent_low\": " + statistics.getOnePercentLowFps() + ',');
        out.println("    \"fps_0_1_percent_low\": " + statistics.getPointOnePercentLowFps() + ',');
        out.println("    \"thresholds\": {");
        writeThreshold(out, statistics, 0, "gt_16_67_ms", true);
        writeThreshold(out, statistics, 1, "gt_33_33_ms", true);
        writeThreshold(out, statistics, 2, "gt_50_ms", true);
        writeThreshold(out, statistics, 3, "gt_100_ms", true);
        writeThreshold(out, statistics, 4, "gt_250_ms", false);
        out.println("    }");
        out.print("  }");
    }

    private static void writeTickSummary(PrintWriter out, BenchmarkStatistics statistics) {
        out.println("  \"tick\": {");
        writeCommonStatistics(out, statistics, "    ");
        out.println("    \"effective_tps\": " + statistics.getEffectiveTps());
        out.print("  }");
    }

    private static void writeCommonStatistics(PrintWriter out, BenchmarkStatistics statistics, String indent) {
        out.println(indent + "\"sample_count\": " + statistics.getSampleCount() + ',');
        out.println(indent + "\"min_ms\": " + millis(statistics.getMinNanos()) + ',');
        out.println(indent + "\"mean_ms\": " + millis(statistics.getMeanNanos()) + ',');
        out.println(indent + "\"max_ms\": " + millis(statistics.getMaxNanos()) + ',');
        out.println(indent + "\"stddev_ms\": " + millis(statistics.getStandardDeviationNanos()) + ',');
        out.println(indent + "\"p50_ms\": " + millis(statistics.getP50Nanos()) + ',');
        out.println(indent + "\"p90_ms\": " + millis(statistics.getP90Nanos()) + ',');
        out.println(indent + "\"p95_ms\": " + millis(statistics.getP95Nanos()) + ',');
        out.println(indent + "\"p99_ms\": " + millis(statistics.getP99Nanos()) + ',');
        out.println(indent + "\"p99_9_ms\": " + millis(statistics.getP999Nanos()) + ',');
    }

    private static void writeThreshold(PrintWriter out, BenchmarkStatistics statistics, int index, String name,
        boolean trailingComma) {
        out.println(
            "      " + quote(name)
                + ": {\"count\": "
                + statistics.getFrameThresholdCount(index)
                + ", \"rate\": "
                + statistics.getFrameThresholdRate(index)
                + '}'
                + (trailingComma ? "," : ""));
    }

    private static void writeSeries(DurationSeriesSnapshot series, File file, String idName, String correlationName)
        throws IOException {
        PrintWriter out = writer(file);
        try {
            out.println(idName + ',' + correlationName + ",duration_ns,duration_ms");
            for (int i = 0; i < series.size(); i++) {
                long duration = series.getDurationNanos(i);
                out.println(
                    series.getId(i) + "," + series.getCorrelationId(i) + "," + duration + "," + millis(duration));
            }
        } finally {
            out.close();
        }
    }

    private static void writeMods(PrintWriter out, List<ModInfo> mods) {
        for (int i = 0; i < mods.size(); i++) {
            ModInfo mod = mods.get(i);
            out.print(
                "      {\"id\": " + quote(mod.getId())
                    + ", \"name\": "
                    + quote(mod.getName())
                    + ", \"version\": "
                    + quote(mod.getVersion())
                    + '}');
            out.println(i + 1 < mods.size() ? "," : "");
        }
    }

    private static void writeStrings(PrintWriter out, List<String> values, String indent) {
        for (int i = 0; i < values.size(); i++) {
            out.print(indent + quote(values.get(i)));
            out.println(i + 1 < values.size() ? "," : "");
        }
    }

    private static void writeMap(PrintWriter out, Map<String, String> values) {
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.print("    " + quote(entry.getKey()) + ": " + quote(entry.getValue()));
            out.println(++index < values.size() ? "," : "");
        }
    }

    private static PrintWriter writer(File file) throws IOException {
        return new PrintWriter(new BufferedWriter(new FileWriter(file)));
    }

    private static String nullable(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        escaped.append(character);
                    }
                    break;
            }
        }
        return escaped.append('"')
            .toString();
    }

    private static String safePath(String value) {
        if (value == null || value.trim()
            .isEmpty()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static long nonNegativeDelta(long end, long start) {
        return Math.max(0L, end - start);
    }

    private static double millis(long nanos) {
        return nanos / NANOS_PER_MILLISECOND;
    }

    private static double millis(double nanos) {
        return nanos / NANOS_PER_MILLISECOND;
    }
}
