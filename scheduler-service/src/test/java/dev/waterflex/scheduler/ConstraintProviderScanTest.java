package dev.waterflex.scheduler;

import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timefold's Spring Boot starter refuses to start the scheduler when it finds more than one {@link ConstraintProvider}
 * and no solver configuration names one. No unit test starts the application context, so this holds the rule: either
 * one provider is on the classpath, or the starter's autoconfiguration is excluded.
 */
class ConstraintProviderScanTest {
    @Test void severalConstraintProvidersRequireTheStarterToBeExcluded() throws IOException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ConstraintProvider.class));
        List<String> found = scanner.findCandidateComponents("dev.waterflex").stream().map(candidate -> candidate.getBeanClassName()).sorted().toList();
        if (found.size() <= 1) return;
        var properties = new Properties();
        try (InputStream in = ConstraintProviderScanTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the classpath");
            properties.load(in);
        }
        String excluded = properties.getProperty("spring.autoconfigure.exclude", "");
        assertTrue(excluded.contains("ai.timefold.solver.spring.boot.autoconfigure.TimefoldSolverAutoConfiguration"),
                "several ConstraintProvider classes " + found + " stop the scheduler at startup unless Timefold's starter is excluded");
    }
}
