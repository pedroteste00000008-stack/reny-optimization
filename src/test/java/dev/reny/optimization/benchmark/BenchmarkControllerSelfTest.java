package dev.reny.optimization.benchmark;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import dev.reny.optimization.command.RenyBenchmarkCommand;
import dev.reny.optimization.profiler.InternalProfiler;

/** Dependency-free verification that the runtime controller advances and exports automatically. */
public final class BenchmarkControllerSelfTest {

    private int passed;

    public static void main(String[] args) throws Exception {
        new BenchmarkControllerSelfTest().run();
    }

    private void run() throws Exception {
        testAutomaticTransitionsAndExport();
        testBusyAndUnknownCommitGuards();
        testCancel();
        testCommandPermissionSurfaces();
        System.out.println("BenchmarkControllerSelfTest: " + passed + " tests passed");
    }

    private void testAutomaticTransitionsAndExport() throws Exception {
        System.setProperty("reny.commit.sha", "controller-test-commit");
        ManualClock clock = new ManualClock(1_000L);
        File profile = Files.createTempDirectory("reny-controller-profile")
            .toFile();
        File outputRoot = new File(profile, "benchmarks/results");
        BenchmarkController controller = new BenchmarkController(
            new InternalProfiler(16, 16),
            profile,
            outputRoot,
            clock);
        RecordingSender sender = new RecordingSender();
        controller.start(sender, BenchmarkScenario.STATIONARY_RENDER, context());
        check(controller.getActiveState() == BenchmarkSession.State.WARMUP, "controller starts in warmup");
        clock.advanceMillis(BenchmarkController.DEFAULT_WARMUP_MILLIS);
        controller.advance();
        check(
            controller.getActiveState() == BenchmarkSession.State.MEASURING,
            "controller enters measurement automatically");
        clock.advanceMillis(BenchmarkController.DEFAULT_MEASUREMENT_MILLIS);
        controller.advance();
        check(!controller.isActive(), "controller clears completed session");
        check(
            controller.getLastOutput() != null && controller.getLastOutput()
                .isDirectory(),
            "completed output exists");
        check(new File(controller.getLastOutput(), "environment.json").isFile(), "environment exported");
        check(new File(controller.getLastOutput(), "summary.json").isFile(), "summary exported");
        check(sender.messages.size() >= 2, "operator receives transition messages");
        pass();
    }

    private void testBusyAndUnknownCommitGuards() throws Exception {
        System.setProperty("reny.commit.sha", "controller-test-commit");
        ManualClock clock = new ManualClock(2_000L);
        File profile = Files.createTempDirectory("reny-controller-busy")
            .toFile();
        BenchmarkController controller = new BenchmarkController(
            new InternalProfiler(8, 8),
            profile,
            new File(profile, "results"),
            clock);
        controller.start(new RecordingSender(), BenchmarkScenario.CHUNK_TRAVERSAL, context());
        boolean busy = false;
        try {
            controller.start(new RecordingSender(), BenchmarkScenario.CHUNK_TRAVERSAL, context());
        } catch (IllegalStateException expected) {
            busy = true;
        }
        check(busy, "controller rejects overlapping sessions");
        controller.cancel();

        System.setProperty("reny.commit.sha", "unknown");
        boolean unknown = false;
        try {
            controller.start(new RecordingSender(), BenchmarkScenario.CHUNK_TRAVERSAL, context());
        } catch (IllegalStateException expected) {
            unknown = true;
        }
        check(unknown, "controller rejects untraceable commit identity");
        System.setProperty("reny.commit.sha", "controller-test-commit");
        pass();
    }

    private void testCancel() throws Exception {
        System.setProperty("reny.commit.sha", "controller-test-commit");
        File profile = Files.createTempDirectory("reny-controller-cancel")
            .toFile();
        BenchmarkController controller = new BenchmarkController(profile);
        controller.start(new RecordingSender(), BenchmarkScenario.SHADER_TORTURE, context());
        check(controller.cancel(), "active controller can be cancelled");
        check(!controller.isActive(), "cancel clears active session");
        check(
            controller.statusLine()
                .contains("FAILED"),
            "cancel is visible in status");
        pass();
    }

    private void testCommandPermissionSurfaces() throws Exception {
        BenchmarkController controller = new BenchmarkController(
            new InternalProfiler(8, 8),
            Files.createTempDirectory("reny-controller-permission")
                .toFile(),
            Files.createTempDirectory("reny-controller-permission-results")
                .toFile(),
            new ManualClock(4_000L));
        BenchmarkContextProvider provider = new BenchmarkContextProvider() {

            @Override
            public BenchmarkContext create(ICommandSender sender, BenchmarkScenario scenario) {
                return context();
            }
        };
        RenyBenchmarkCommand clientCommand = new RenyBenchmarkCommand(controller, provider);
        RenyBenchmarkCommand serverCommand = new RenyBenchmarkCommand(controller, provider, 2);
        check(clientCommand.getRequiredPermissionLevel() == 0, "client command remains available in singleplayer");
        check(serverCommand.getRequiredPermissionLevel() == 2, "server command requires operator permission");
        pass();
    }

    private static BenchmarkContext context() {
        return BenchmarkContext.builder()
            .display(1280, 720, 8, false, 0)
            .shader("none", "none", "none")
            .world("12345", "controller-test", "fixed", "day-clear")
            .configHash("controller-test-config")
            .build();
    }

    private void pass() {
        passed++;
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

    private static final class RecordingSender implements ICommandSender {

        private final List<String> messages = new ArrayList<String>();

        @Override
        public String getCommandSenderName() {
            return "controller-test";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return new ChatComponentText(getCommandSenderName());
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message.getUnformattedText());
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 64, 0);
        }

        @Override
        public World getEntityWorld() {
            return null;
        }
    }
}
