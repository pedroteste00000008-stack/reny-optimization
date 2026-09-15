package dev.reny.optimization.command;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import dev.reny.optimization.benchmark.BenchmarkContext;
import dev.reny.optimization.benchmark.BenchmarkContextProvider;
import dev.reny.optimization.benchmark.BenchmarkController;
import dev.reny.optimization.benchmark.BenchmarkScenario;

/** Operator command for starting and observing one automatic benchmark session. */
public final class RenyBenchmarkCommand extends CommandBase {

    private static final String USAGE = "/reny benchmark <start <BENCH-ID>|status|cancel>";

    private final BenchmarkController controller;
    private final BenchmarkContextProvider contextProvider;
    private final int requiredPermissionLevel;

    public RenyBenchmarkCommand(BenchmarkController controller, BenchmarkContextProvider contextProvider) {
        this(controller, contextProvider, 0);
    }

    public RenyBenchmarkCommand(BenchmarkController controller, BenchmarkContextProvider contextProvider,
        int requiredPermissionLevel) {
        if (controller == null || contextProvider == null) {
            throw new IllegalArgumentException("benchmark command arguments must not be null");
        }
        if (requiredPermissionLevel < 0) {
            throw new IllegalArgumentException("required permission level must not be negative");
        }
        this.controller = controller;
        this.contextProvider = contextProvider;
        this.requiredPermissionLevel = requiredPermissionLevel;
    }

    @Override
    public String getCommandName() {
        return "reny";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return requiredPermissionLevel;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2 || !"benchmark".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(USAGE, new Object[0]);
        }
        String action = args[1];
        if ("status".equalsIgnoreCase(action) && args.length == 2) {
            sender.addChatMessage(new ChatComponentText(controller.statusLine()));
            return;
        }
        if ("cancel".equalsIgnoreCase(action) && args.length == 2) {
            sender.addChatMessage(
                new ChatComponentText(
                    controller.cancel() ? "Reny benchmark cancellation requested" : "Reny benchmark IDLE"));
            return;
        }
        if ("start".equalsIgnoreCase(action) && args.length == 3) {
            BenchmarkScenario scenario;
            try {
                scenario = BenchmarkScenario.byId(args[2]);
            } catch (IllegalArgumentException exception) {
                throw new WrongUsageException("Unknown benchmark ID: " + args[2], new Object[0]);
            }
            BenchmarkContext context = contextProvider.create(sender, scenario);
            controller.start(sender, scenario, context);
            sender.addChatMessage(new ChatComponentText(controller.statusLine()));
            return;
        }
        throw new WrongUsageException(USAGE, new Object[0]);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "benchmark");
        }
        if (args.length == 2 && "benchmark".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "start", "status", "cancel");
        }
        if (args.length == 3 && "benchmark".equalsIgnoreCase(args[0]) && "start".equalsIgnoreCase(args[1])) {
            List<String> scenarios = new ArrayList<String>();
            for (BenchmarkScenario scenario : BenchmarkScenario.values()) {
                scenarios.add(scenario.getId());
            }
            return getListOfStringsFromIterableMatchingLastWord(args, scenarios);
        }
        return null;
    }
}
