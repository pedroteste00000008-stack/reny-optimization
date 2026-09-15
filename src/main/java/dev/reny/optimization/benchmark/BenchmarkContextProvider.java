package dev.reny.optimization.benchmark;

import net.minecraft.command.ICommandSender;

/** Supplies runtime-specific metadata for a benchmark command invocation. */
public interface BenchmarkContextProvider {

    BenchmarkContext create(ICommandSender sender, BenchmarkScenario scenario);
}
