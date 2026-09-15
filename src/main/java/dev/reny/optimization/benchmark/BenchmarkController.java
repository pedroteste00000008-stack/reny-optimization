package dev.reny.optimization.benchmark;

import java.io.File;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.reny.optimization.command.RenyBenchmarkCommand;
import dev.reny.optimization.profiler.InternalProfiler;

/** Lightweight runtime driver for the benchmark session state machine. */
public final class BenchmarkController {

    public static final long DEFAULT_WARMUP_MILLIS = 60_000L;
    public static final long DEFAULT_MEASUREMENT_MILLIS = 120_000L;

    private static final Logger LOG = LogManager.getLogger("renyoptimization");

    private final InternalProfiler profiler;
    private final File profileRoot;
    private final File outputRoot;
    private final BenchmarkClock clock;
    private final Object monitor = new Object();

    private BenchmarkSession activeSession;
    private ICommandSender operator;
    private File lastOutput;
    private String lastRunId;
    private String lastFailure;

    public BenchmarkController(File profileRoot) {
        this(InternalProfiler.get(), profileRoot, new File(profileRoot, "benchmarks/results"), SystemClock.INSTANCE);
    }

    BenchmarkController(InternalProfiler profiler, File profileRoot, File outputRoot, BenchmarkClock clock) {
        if (profiler == null || profileRoot == null || outputRoot == null || clock == null) {
            throw new IllegalArgumentException("benchmark controller arguments must not be null");
        }
        this.profiler = profiler;
        this.profileRoot = profileRoot;
        this.outputRoot = outputRoot;
        this.clock = clock;
    }

    public void registerServerCommand(FMLServerStartingEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("server-starting event must not be null");
        }
        event.registerServerCommand(new RenyBenchmarkCommand(this, new BenchmarkContextProvider() {

            @Override
            public BenchmarkContext create(ICommandSender sender, BenchmarkScenario scenario) {
                return BenchmarkContexts.fromServer(sender, scenario, profileRoot);
            }
        }, 2));
        LOG.info("Registered /reny benchmark command on the server command surface");
    }

    public void start(ICommandSender sender, BenchmarkScenario scenario, BenchmarkContext context) {
        if (scenario == null || context == null) {
            throw new IllegalArgumentException("benchmark scenario and context must not be null");
        }
        synchronized (monitor) {
            if (activeSession != null) {
                throw new IllegalStateException("A benchmark is already active: " + activeSession.getRunId());
            }
            BenchmarkSession session = new BenchmarkSession(
                profiler,
                scenario,
                context,
                DEFAULT_WARMUP_MILLIS,
                DEFAULT_MEASUREMENT_MILLIS,
                outputRoot,
                clock);
            String commit = session.getEnvironment()
                .getRenyCommitSha();
            if ("unknown".equalsIgnoreCase(commit)) {
                throw new IllegalStateException(
                    "Reny commit SHA is unknown; launch with -Dreny.commit.sha=<full-commit-sha>");
            }
            session.startWarmup();
            activeSession = session;
            operator = sender;
            lastOutput = null;
            lastRunId = session.getRunId();
            lastFailure = null;
            LOG.info(
                "benchmark {} state CREATED -> WARMUP scenario={} commit={} warmup_ms={} measurement_ms={}",
                session.getRunId(),
                scenario.getId(),
                commit,
                Long.valueOf(DEFAULT_WARMUP_MILLIS),
                Long.valueOf(DEFAULT_MEASUREMENT_MILLIS));
        }
    }

    public void advance() {
        synchronized (monitor) {
            if (activeSession == null) {
                return;
            }
            try {
                if (activeSession.getState() == BenchmarkSession.State.WARMUP
                    && activeSession.shouldBeginMeasurement()) {
                    activeSession.beginMeasurement();
                    LOG.info("benchmark {} state WARMUP -> MEASURING", activeSession.getRunId());
                    notifyOperator("Reny benchmark " + activeSession.getRunId() + " entered MEASURING");
                } else if (activeSession.getState() == BenchmarkSession.State.MEASURING
                    && activeSession.shouldFinishMeasurement()) {
                        File output = activeSession.finish();
                        lastOutput = output;
                        LOG.info(
                            "benchmark {} state MEASURING -> COMPLETE output={}",
                            activeSession.getRunId(),
                            output.getAbsolutePath());
                        notifyOperator("Reny benchmark COMPLETE: " + output.getAbsolutePath());
                        activeSession = null;
                        operator = null;
                    }
            } catch (RuntimeException exception) {
                String runId = activeSession.getRunId();
                lastFailure = exception.getMessage() == null ? exception.getClass()
                    .getName() : exception.getMessage();
                LOG.error("benchmark {} failed while advancing", runId, exception);
                notifyOperator("Reny benchmark FAILED: " + lastFailure);
                activeSession.abort();
                activeSession = null;
                operator = null;
            }
        }
    }

    public boolean cancel() {
        synchronized (monitor) {
            if (activeSession == null) {
                return false;
            }
            String runId = activeSession.getRunId();
            LOG.warn("benchmark {} cancelled by operator", runId);
            lastFailure = "cancelled by operator: " + runId;
            notifyOperator("Reny benchmark cancelled: " + runId);
            activeSession.abort();
            activeSession = null;
            operator = null;
            return true;
        }
    }

    public String statusLine() {
        synchronized (monitor) {
            if (activeSession != null) {
                return "Reny benchmark " + activeSession.getState()
                    + " scenario="
                    + activeSession.getScenario()
                        .getId()
                    + " run="
                    + activeSession.getRunId()
                    + " elapsed_ms="
                    + activeSession.getCurrentPhaseElapsedMillis();
            }
            if (lastOutput != null) {
                return "Reny benchmark COMPLETE run=" + lastRunId + " output=" + lastOutput.getAbsolutePath();
            }
            if (lastFailure != null) {
                return "Reny benchmark FAILED: " + lastFailure;
            }
            return "Reny benchmark IDLE";
        }
    }

    public boolean isActive() {
        synchronized (monitor) {
            return activeSession != null;
        }
    }

    public BenchmarkSession.State getActiveState() {
        synchronized (monitor) {
            return activeSession == null ? null : activeSession.getState();
        }
    }

    public File getLastOutput() {
        synchronized (monitor) {
            return lastOutput;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            advance();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            advance();
        }
    }

    private void notifyOperator(String message) {
        if (operator == null) {
            return;
        }
        try {
            operator.addChatMessage(new ChatComponentText(message));
        } catch (Throwable ignored) {
            // A disconnected operator must not interrupt a benchmark transition.
        }
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
