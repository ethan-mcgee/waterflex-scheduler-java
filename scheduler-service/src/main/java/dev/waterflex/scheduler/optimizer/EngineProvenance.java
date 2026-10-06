package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.solver.SolverFactory;
import dev.waterflex.scheduler.Required;
import java.net.JarURLConnection;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/** Identity of the actual loaded core artifact, independent of configured dependency strings. */
public record EngineProvenance(String artifact, @Nullable String version, @Nullable String sha256,
        @Nullable String unavailableReason) {
    private static final EngineProvenance LOADED = inspect();
    public static EngineProvenance loaded() { return LOADED; }
    public String label() { return "Timefold-" + (version == null ? "unknown" : version); }
    private static EngineProvenance inspect() {
        try {
            var resource = Required.value(SolverFactory.class.getResource("SolverFactory.class"));
            if (!(resource.openConnection() instanceof JarURLConnection connection))
                return new EngineProvenance("timefold-solver-core", null, null, "LOADED_CLASS_IS_NOT_IN_JAR");
            var jar = connection.getJarFile();
            var entry = Required.value(jar.getJarEntry("META-INF/maven/ai.timefold.solver/timefold-solver-core/pom.properties"));
            Properties properties = new Properties();
            try (var input = jar.getInputStream(entry)) { properties.load(input); }
            String version = Required.value(properties.getProperty("version"));
            if (version.isBlank() || !"timefold-solver-core".equals(properties.getProperty("artifactId")))
                throw new IllegalStateException("Invalid loaded core metadata");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = connection.getJarFileURL().openStream()) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return new EngineProvenance("timefold-solver-core", version, Required.value(HexFormat.of().formatHex(digest.digest())), null);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException | LinkageError failure) {
            return new EngineProvenance("timefold-solver-core", null, null, "ARTIFACT_READ_FAILED:" + failure.getClass().getSimpleName());
        }
    }
}
