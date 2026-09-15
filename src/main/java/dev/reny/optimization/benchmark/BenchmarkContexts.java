package dev.reny.optimization.benchmark;

import java.io.File;
import java.util.Locale;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;

/** Builds explicit benchmark metadata without adding work to frame or tick hot paths. */
public final class BenchmarkContexts {

    private BenchmarkContexts() {}

    public static BenchmarkContext.Builder builderFor(BenchmarkScenario scenario) {
        BenchmarkScenario safeScenario = scenario == null ? BenchmarkScenario.MONSTER : scenario;
        return BenchmarkContext.builder()
            .shader(
                property("reny.benchmark.shader.name", "none"),
                property("reny.benchmark.shader.version", "none"),
                property("reny.benchmark.shader.preset", "none"))
            .extra("scenario_purpose", safeScenario.getPurpose())
            .extra("optimization_profile", "COMPATIBLE")
            .extra("optimization_patches", "none")
            .extra("workload_descriptor_version", "baseline-0.0-review-1")
            .extra("workload_procedure_id", safeScenario.getId())
            .extra("camera_policy", "fixed snapshot yaw/pitch; no mouse input")
            .extra("movement_policy", movementPolicy(safeScenario))
            .extra("movement_speed_policy", movementSpeedPolicy(safeScenario))
            .extra("scene_identity_policy", "world descriptor plus seed and snapshot route");
    }

    public static BenchmarkContext fromServer(ICommandSender sender, BenchmarkScenario scenario, File profileRoot) {
        BenchmarkContext.Builder builder = builderFor(scenario).extra("context_source", "server-command")
            .configHash(property("reny.benchmark.config.hash", BenchmarkConfigHasher.hashProfile(profileRoot)));
        applyWorld(builder, sender == null ? null : sender.getEntityWorld(), serverPosition(sender));
        return builder.build();
    }

    public static String property(String name, String fallback) {
        try {
            String value = System.getProperty(name);
            return value == null || value.trim()
                .isEmpty() ? fallback : value.trim();
        } catch (SecurityException ignored) {
            return fallback;
        }
    }

    private static String movementPolicy(BenchmarkScenario scenario) {
        return scenario == BenchmarkScenario.CHUNK_TRAVERSAL ? "hold W; sprint disabled; no camera input"
            : "stationary; no movement or camera input";
    }

    private static String movementSpeedPolicy(BenchmarkScenario scenario) {
        return scenario == BenchmarkScenario.CHUNK_TRAVERSAL ? "Minecraft default walking speed; mouseSensitivity=0.5"
            : "not applicable; player remains at snapshot route";
    }

    static void applyWorld(BenchmarkContext.Builder builder, World world, String route) {
        if (world == null) {
            builder.world("unknown", "no-world", route, "unknown");
            return;
        }
        WorldInfo info = world.getWorldInfo();
        String worldName = info == null ? "unknown" : info.getWorldName();
        String descriptor = worldName + ",dimension=" + world.provider.dimensionId;
        String weather = weather(world);
        builder.world(String.valueOf(world.getSeed()), descriptor, route, weather)
            .extra("world_time_ticks", String.valueOf(world.getWorldTime()))
            .extra("weather", weather);
    }

    static String serverPosition(ICommandSender sender) {
        if (sender == null) {
            return "unknown";
        }
        ChunkCoordinates coordinates = sender.getPlayerCoordinates();
        if (coordinates == null) {
            return "unknown";
        }
        return String.format(
            Locale.ROOT,
            "block-x=%d,block-y=%d,block-z=%d",
            Integer.valueOf(coordinates.posX),
            Integer.valueOf(coordinates.posY),
            Integer.valueOf(coordinates.posZ));
    }

    public static String weather(World world) {
        if (world.isThundering()) {
            return "thunder";
        }
        return world.isRaining() ? "rain" : "clear";
    }
}
