package dev.reny.optimization;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.relauncher.Side;
import dev.reny.optimization.benchmark.BenchmarkController;
import dev.reny.optimization.compat.CompatibilityManager;
import dev.reny.optimization.compat.EnvironmentDetector;
import dev.reny.optimization.profiler.ForgeProfilerHooks;
import dev.reny.optimization.profiler.InternalProfiler;

@Mod(
    modid = RenyOptimization.MOD_ID,
    name = RenyOptimization.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]")
public final class RenyOptimization {

    public static final String MOD_ID = "renyoptimization";
    public static final String MOD_NAME = "Reny Optimization";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    private static volatile CompatibilityManager compatibilityManager;
    private static volatile BenchmarkController benchmarkController;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        CompatibilityManager compatibility = new CompatibilityManager(EnvironmentDetector.capture());
        compatibilityManager = compatibility;
        for (String line : compatibility.toDiagnosticLines()) {
            LOG.info("compatibility: {}", line);
        }

        InternalProfiler profiler = InternalProfiler.get();
        profiler.initialize(new File(event.getModConfigurationDirectory(), "reny-profiler"));
        FMLCommonHandler.instance()
            .bus()
            .register(ForgeProfilerHooks.INSTANCE);
        BenchmarkController controller = new BenchmarkController(
            event.getModConfigurationDirectory()
                .getParentFile());
        benchmarkController = controller;
        FMLCommonHandler.instance()
            .bus()
            .register(controller);
        LOG.info(
            "Reny Optimization {} initialized; internal profiler enabled={}, no optimization patches active",
            Tags.VERSION,
            profiler.getConfig()
                .isEnabled());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance()
            .getEffectiveSide() != Side.CLIENT) {
            return;
        }
        try {
            Class<?> bridge = Class.forName("dev.reny.optimization.client.RenyClientRuntime");
            bridge.getMethod("initialize", BenchmarkController.class)
                .invoke(null, benchmarkController);
            LOG.info("Registered client-side /reny benchmark command with render metadata capture");
        } catch (Throwable exception) {
            LOG.error("Unable to initialize the client benchmark command surface", exception);
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        BenchmarkController controller = benchmarkController;
        if (controller != null) {
            controller.registerServerCommand(event);
        }
    }

    public static CompatibilityManager getCompatibilityManager() {
        return compatibilityManager;
    }

    public static BenchmarkController getBenchmarkController() {
        return benchmarkController;
    }
}
