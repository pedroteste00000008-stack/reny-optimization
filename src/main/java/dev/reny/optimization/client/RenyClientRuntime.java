package dev.reny.optimization.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.command.ICommandSender;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import dev.reny.optimization.benchmark.BenchmarkConfigHasher;
import dev.reny.optimization.benchmark.BenchmarkContext;
import dev.reny.optimization.benchmark.BenchmarkContextProvider;
import dev.reny.optimization.benchmark.BenchmarkContexts;
import dev.reny.optimization.benchmark.BenchmarkController;
import dev.reny.optimization.benchmark.BenchmarkScenario;
import dev.reny.optimization.command.RenyBenchmarkCommand;

/** Client-only command bridge that captures render settings and camera metadata. */
@SideOnly(Side.CLIENT)
public final class RenyClientRuntime {

    private static boolean initialized;

    private RenyClientRuntime() {}

    public static void initialize(BenchmarkController controller) {
        if (initialized) {
            return;
        }
        ClientCommandHandler.instance
            .registerCommand(new RenyBenchmarkCommand(controller, new BenchmarkContextProvider() {

                @Override
                public BenchmarkContext create(ICommandSender sender, BenchmarkScenario scenario) {
                    return createClientContext(scenario);
                }
            }));
        initialized = true;
    }

    private static BenchmarkContext createClientContext(BenchmarkScenario scenario) {
        Minecraft minecraft = Minecraft.getMinecraft();
        BenchmarkContext.Builder builder = BenchmarkContexts.builderFor(scenario)
            .extra("context_source", "client-command")
            .extra("shader_metadata_source", "optionsshaders.txt with reny.benchmark.shader.* override")
            .configHash(
                BenchmarkContexts.property(
                    "reny.benchmark.config.hash",
                    BenchmarkConfigHasher.hashProfile(minecraft == null ? null : minecraft.mcDataDir)));
        if (minecraft == null) {
            return builder.world("unknown", "no-client", "unknown", "unknown")
                .build();
        }
        if (minecraft.gameSettings != null) {
            builder
                .display(
                    minecraft.displayWidth,
                    minecraft.displayHeight,
                    minecraft.gameSettings.renderDistanceChunks,
                    minecraft.gameSettings.enableVsync,
                    minecraft.gameSettings.limitFramerate)
                .extra("fps_cap_semantics", isUncapped(minecraft.gameSettings.limitFramerate) ? "uncapped" : "capped");
        }
        String shaderPack = discoverShaderPack(minecraft);
        String shaderName = BenchmarkContexts.property("reny.benchmark.shader.name", shaderPack);
        String shaderVersion = BenchmarkContexts
            .property("reny.benchmark.shader.version", isNoShader(shaderPack) ? "none" : "unknown");
        String shaderPreset = BenchmarkContexts
            .property("reny.benchmark.shader.preset", isNoShader(shaderPack) ? "none" : "unknown");
        builder.shader(shaderName, shaderVersion, shaderPreset)
            .extra("shader_pack_configured", String.valueOf(!isNoShader(shaderPack)))
            .extra("optifine_loaded", String.valueOf(isOptiFineLoaded()))
            .extra("optifine_version", discoverOptiFineVersion())
            .extra("game_mode", gameMode(minecraft));
        World clientWorld = minecraft.theWorld;
        if (clientWorld == null) {
            return builder.world("unknown", "no-world", "unknown", "unknown")
                .build();
        }
        World metadataWorld = clientWorld;
        if (minecraft.getIntegratedServer() != null && clientWorld.provider != null) {
            World integratedWorld = minecraft.getIntegratedServer()
                .worldServerForDimension(clientWorld.provider.dimensionId);
            if (integratedWorld != null) {
                metadataWorld = integratedWorld;
                builder.extra("world_metadata_source", "integrated-server");
            }
        }
        WorldInfo info = metadataWorld.getWorldInfo();
        String worldName = info == null ? "unknown" : info.getWorldName();
        String weather = BenchmarkContexts.weather(metadataWorld);
        String route = playerRoute(minecraft);
        builder
            .world(
                String.valueOf(metadataWorld.getSeed()),
                worldName + ",dimension=" + metadataWorld.provider.dimensionId,
                route,
                weather)
            .extra("world_time_ticks", String.valueOf(metadataWorld.getWorldTime()))
            .extra("weather", weather)
            .extra("primary_run_settings_valid", primarySettingsValid(minecraft));
        return builder.build();
    }

    private static String discoverShaderPack(Minecraft minecraft) {
        if (minecraft == null || minecraft.mcDataDir == null) {
            return "none";
        }
        File options = new File(minecraft.mcDataDir, "optionsshaders.txt");
        if (!options.isFile()) {
            return "none";
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(options));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("shaderPack=")) {
                    String value = line.substring("shaderPack=".length())
                        .trim();
                    return value.isEmpty() ? "none" : value;
                }
            }
        } catch (IOException ignored) {
            // Metadata discovery is best effort; the exported value remains explicit.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Best effort close only.
                }
            }
        }
        return "none";
    }

    private static boolean isOptiFineLoaded() {
        try {
            Class.forName("Config", false, RenyClientRuntime.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String discoverOptiFineVersion() {
        try {
            Class<?> config = Class.forName("Config", false, RenyClientRuntime.class.getClassLoader());
            Method method = config.getMethod("getVersion");
            Object value = method.invoke(null);
            return value == null ? "unknown" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "none";
        }
    }

    private static boolean isNoShader(String value) {
        return value == null || value.trim()
            .isEmpty() || "none".equalsIgnoreCase(value.trim());
    }

    private static String playerRoute(Minecraft minecraft) {
        if (minecraft.thePlayer == null) {
            return "unknown";
        }
        return String.format(
            Locale.ROOT,
            "x=%.3f,y=%.3f,z=%.3f,yaw=%.3f,pitch=%.3f",
            Double.valueOf(minecraft.thePlayer.posX),
            Double.valueOf(minecraft.thePlayer.posY),
            Double.valueOf(minecraft.thePlayer.posZ),
            Float.valueOf(minecraft.thePlayer.rotationYaw),
            Float.valueOf(minecraft.thePlayer.rotationPitch));
    }

    private static String primarySettingsValid(Minecraft minecraft) {
        if (minecraft.gameSettings == null) {
            return "unknown";
        }
        return !minecraft.gameSettings.enableVsync && isUncapped(minecraft.gameSettings.limitFramerate) ? "true"
            : "false";
    }

    private static String gameMode(Minecraft minecraft) {
        if (minecraft == null || minecraft.thePlayer == null || minecraft.thePlayer.capabilities == null) {
            return "unknown";
        }
        return minecraft.thePlayer.capabilities.isCreativeMode ? "creative" : "survival";
    }

    /** Minecraft 1.7.10 uses the slider maximum (260) as its uncapped sentinel. */
    private static boolean isUncapped(int limitFramerate) {
        return limitFramerate >= (int) GameSettings.Options.FRAMERATE_LIMIT.getValueMax();
    }
}
