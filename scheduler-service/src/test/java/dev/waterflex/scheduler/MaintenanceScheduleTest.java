package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.OvernightOptimization;
import dev.waterflex.scheduler.optimizer.OptimizationService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceScheduleTest {
    /** Operators turn the scheduler's own batches off with Spring's "-" (for example SCHEDULER_OPTIMIZER_CRON=-). */
    @Test void dashDisablesMaintenanceTasksButProductionRetainsDefaults() throws Exception {
        for (boolean disabled : new boolean[]{false, true}) {
            var factory = new DefaultListableBeanFactory();
            var env = new org.springframework.mock.env.MockEnvironment();
            if (disabled) { env.setProperty("scheduler.optimizer.cron", "-"); env.setProperty("routing.cache.cleanup-cron", "-"); }
            factory.addEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            var processor = new ScheduledAnnotationBeanPostProcessor();
            processor.setBeanFactory(factory);
            processor.setEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            try {
                processor.postProcessAfterInitialization(mock(OvernightOptimization.class), "optimizer");
                processor.postProcessAfterInitialization(mock(RoadClient.class), "roads");
                processor.afterSingletonsInstantiated();
                assertEquals(disabled ? 0 : 2, processor.getScheduledTasks().size());
                if (!disabled) {
                    var crons = processor.getScheduledTasks().stream().map(task -> ((org.springframework.scheduling.config.CronTask)task.getTask()).getExpression()).collect(java.util.stream.Collectors.toSet());
                    assertEquals(java.util.Set.of("0 0 2 * * *", "0 30 3 * * SUN"), crons);
                }
            } finally { processor.destroy(); }
        }
    }
    @Test void disabledQueuedAnalysisDoesNotTouchDatabase() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new TimeOffService(jdbc, mock(OptimizationService.class), mock(ScheduleGuardService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "analysisEnabled", false);
        service.processQueued(); verifyNoInteractions(jdbc);
    }
}
