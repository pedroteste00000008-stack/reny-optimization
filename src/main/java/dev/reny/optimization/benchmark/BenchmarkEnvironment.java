package dev.reny.optimization.benchmark;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import dev.reny.optimization.Tags;

/**
 * Best-effort local environment capture. Discovery failures are represented as unknown values, never network lookups.
 */
public final class BenchmarkEnvironment {

    public static final String MINECRAFT_VERSION = "1.7.10";
    public static final String FORGE_VERSION = "10.13.4.1614";

    private final String renyVersion;
    private final String renyCommitSha;
    private final String minecraftVersion;
    private final String forgeVersion;
    private final String javaVendor;
    private final String javaVersion;
    private final String javaVm;
    private final List<String> jvmArguments;
    private final String osName;
    private final String osVersion;
    private final String osArch;
    private final String kernel;
    private final String cpu;
    private final int availableProcessors;
    private final long physicalRamBytes;
    private final String gpuVendor;
    private final String gpuRenderer;
    private final String driver;
    private final String openGlVersion;
    private final List<ModInfo> mods;
    private final BenchmarkContext context;

    private BenchmarkEnvironment(BenchmarkContext context) {
        this.context = context;
        renyVersion = Tags.VERSION;
        renyCommitSha = discoverCommitSha();
        minecraftVersion = property("reny.benchmark.minecraft", MINECRAFT_VERSION);
        List<ModInfo> discoveredMods = discoverMods();
        mods = Collections.unmodifiableList(discoveredMods);
        forgeVersion = discoverForgeVersion(discoveredMods);
        javaVendor = property("java.vendor", "unknown");
        javaVersion = property("java.version", "unknown");
        javaVm = property("java.vm.name", "unknown");
        jvmArguments = Collections.unmodifiableList(
            new ArrayList<String>(
                ManagementFactory.getRuntimeMXBean()
                    .getInputArguments()));
        osName = property("os.name", "unknown");
        osVersion = property("os.version", "unknown");
        osArch = property("os.arch", "unknown");
        kernel = discoverKernel();
        cpu = discoverCpu();
        availableProcessors = Runtime.getRuntime()
            .availableProcessors();
        physicalRamBytes = discoverPhysicalRam();
        OpenGlInfo gl = discoverOpenGl();
        gpuVendor = property("reny.benchmark.gpu.vendor", gl.vendor);
        gpuRenderer = property("reny.benchmark.gpu", gl.renderer);
        openGlVersion = property("reny.benchmark.opengl", gl.version);
        driver = property("reny.benchmark.driver", gl.version);
    }

    public static BenchmarkEnvironment capture(BenchmarkContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new BenchmarkEnvironment(context);
    }

    private static String discoverForgeVersion(List<ModInfo> mods) {
        for (ModInfo mod : mods) {
            if ("Forge".equalsIgnoreCase(mod.getId())) {
                return mod.getVersion();
            }
        }
        return property("reny.benchmark.forge", FORGE_VERSION);
    }

    private static List<ModInfo> discoverMods() {
        List<ModInfo> result = new ArrayList<ModInfo>();
        try {
            for (ModContainer container : Loader.instance()
                .getActiveModList()) {
                result.add(new ModInfo(container.getModId(), container.getName(), container.getVersion()));
            }
        } catch (Throwable ignored) {
            // FML may not be fully initialized in tooling/self-test processes.
        }
        return result;
    }

    private static String discoverCommitSha() {
        String explicit = firstNonBlank(
            safeSystemProperty("reny.commit.sha"),
            safeEnvironment("RENY_COMMIT_SHA"),
            safeEnvironment("GITHUB_SHA"));
        if (explicit != null) {
            return explicit;
        }
        String packaged = readPackagedCommitSha();
        if (packaged != null) {
            return packaged;
        }
        try {
            File git = new File(System.getProperty("user.dir", "."), ".git");
            File head = new File(git, "HEAD");
            String value = readFirstLine(head);
            if (value != null && value.startsWith("ref: ")) {
                value = readFirstLine(
                    new File(
                        git,
                        value.substring(5)
                            .trim()));
            }
            if (value != null && !value.trim()
                .isEmpty()) {
                return value.trim();
            }
        } catch (RuntimeException ignored) {
            // Best-effort local discovery only.
        }
        return "unknown";
    }

    private static String readPackagedCommitSha() {
        InputStream stream = null;
        try {
            stream = BenchmarkEnvironment.class.getClassLoader()
                .getResourceAsStream("reny-build.properties");
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return firstNonBlank(properties.getProperty("commit_sha"));
        } catch (IOException ignored) {
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Best-effort packaged metadata discovery only.
                }
            }
        }
    }

    private static String discoverKernel() {
        String linuxKernel = readFirstLine(new File("/proc/sys/kernel/osrelease"));
        return linuxKernel == null ? property("os.version", "unknown") : linuxKernel;
    }

    private static String discoverCpu() {
        String explicit = safeSystemProperty("reny.benchmark.cpu");
        if (explicit != null && !explicit.trim()
            .isEmpty()) {
            return explicit;
        }
        File cpuInfo = new File("/proc/cpuinfo");
        if (cpuInfo.isFile()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(cpuInfo));
                String line;
                while ((line = reader.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && "model name".equalsIgnoreCase(
                        line.substring(0, colon)
                            .trim())) {
                        return line.substring(colon + 1)
                            .trim();
                    }
                }
            } catch (IOException ignored) {
                // Fall through to environment/JVM hints.
            } finally {
                closeQuietly(reader);
            }
        }
        String identifier = safeEnvironment("PROCESSOR_IDENTIFIER");
        return identifier == null || identifier.trim()
            .isEmpty() ? property("os.arch", "unknown") : identifier;
    }

    private static long discoverPhysicalRam() {
        String explicit = safeSystemProperty("reny.benchmark.ram.bytes");
        if (explicit != null) {
            try {
                return Long.parseLong(explicit);
            } catch (NumberFormatException ignored) {
                // Continue with MXBean discovery.
            }
        }
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        String[] methods = { "getTotalMemorySize", "getTotalPhysicalMemorySize" };
        for (String methodName : methods) {
            try {
                Method method = bean.getClass()
                    .getMethod(methodName);
                Object value = method.invoke(bean);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (Throwable ignored) {
                // Try the next compatible JDK method.
            }
        }
        return -1L;
    }

    private static OpenGlInfo discoverOpenGl() {
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            Method getString = gl11.getMethod("glGetString", Integer.TYPE);
            return new OpenGlInfo(
                stringValue(getString.invoke(null, Integer.valueOf(7936))),
                stringValue(getString.invoke(null, Integer.valueOf(7937))),
                stringValue(getString.invoke(null, Integer.valueOf(7938))));
        } catch (Throwable ignored) {
            return new OpenGlInfo("unknown", "unknown", "unknown");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String property(String name, String fallback) {
        String value = safeSystemProperty(name);
        return value == null || value.trim()
            .isEmpty() ? fallback : value;
    }

    private static String safeSystemProperty(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String safeEnvironment(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim()
                .isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String readFirstLine(File file) {
        if (!file.isFile()) {
            return null;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            return reader.readLine();
        } catch (IOException ignored) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Nothing useful to do during metadata discovery.
            }
        }
    }

    public String getRenyVersion() {
        return renyVersion;
    }

    public String getRenyCommitSha() {
        return renyCommitSha;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getForgeVersion() {
        return forgeVersion;
    }

    public String getJavaVendor() {
        return javaVendor;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public String getJavaVm() {
        return javaVm;
    }

    public List<String> getJvmArguments() {
        return jvmArguments;
    }

    public String getOsName() {
        return osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getOsArch() {
        return osArch;
    }

    public String getKernel() {
        return kernel;
    }

    public String getCpu() {
        return cpu;
    }

    public int getAvailableProcessors() {
        return availableProcessors;
    }

    public long getPhysicalRamBytes() {
        return physicalRamBytes;
    }

    public String getGpuVendor() {
        return gpuVendor;
    }

    public String getGpuRenderer() {
        return gpuRenderer;
    }

    public String getDriver() {
        return driver;
    }

    public String getOpenGlVersion() {
        return openGlVersion;
    }

    public List<ModInfo> getMods() {
        return mods;
    }

    public BenchmarkContext getContext() {
        return context;
    }

    public static final class ModInfo {

        private final String id;
        private final String name;
        private final String version;

        private ModInfo(String id, String name, String version) {
            this.id = id == null ? "unknown" : id;
            this.name = name == null ? "unknown" : name;
            this.version = version == null ? "unknown" : version;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }
    }

    private static final class OpenGlInfo {

        private final String vendor;
        private final String renderer;
        private final String version;

        private OpenGlInfo(String vendor, String renderer, String version) {
            this.vendor = vendor;
            this.renderer = renderer;
            this.version = version;
        }
    }
}
