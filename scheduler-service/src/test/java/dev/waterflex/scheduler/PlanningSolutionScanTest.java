package dev.waterflex.scheduler;

import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Timefold's Spring Boot starter scans the application's packages and refuses to start the scheduler when it finds a
 * {@code @PlanningSolution} class its solver configuration does not use. No unit test starts the application context,
 * so this holds the same rule: {@code DayPlan} is the only planning solution on the scheduler's classpath.
 */
class PlanningSolutionScanTest {
    @Test void dayPlanIsTheOnlyPlanningSolution() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PlanningSolution.class));
        List<String> found = scanner.findCandidateComponents("dev.waterflex").stream().map(candidate -> candidate.getBeanClassName()).sorted().toList();
        assertEquals(List.of("dev.waterflex.scheduler.optimizer.DayPlan"), found,
                "a second @PlanningSolution class stops the scheduler at startup (Timefold's starter rejects unused solution classes)");
    }
}
