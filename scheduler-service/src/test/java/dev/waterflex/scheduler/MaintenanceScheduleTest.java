package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceScheduleTest {
    /** The road cache cleanup is the scheduler's only scheduled task; Spring's "-" turns it off. */
    @Test void dashDisablesRoadCacheCleanupButProductionRetainsItsDefault() throws Exception {
        for (boolean disabled : new boolean[]{false, true}) {
            var factory = new DefaultListableBeanFactory();
            var env = new org.springframework.mock.env.MockEnvironment();
            if (disabled) env.setProperty("routing.cache.cleanup-cron", "-");
            factory.addEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            var processor = new ScheduledAnnotationBeanPostProcessor();
            processor.setBeanFactory(factory);
            processor.setEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            try {
                processor.postProcessAfterInitialization(mock(RoadClient.class), "roads");
                processor.afterSingletonsInstantiated();
                assertEquals(disabled ? 0 : 1, processor.getScheduledTasks().size());
                if (!disabled) {
                    var crons = processor.getScheduledTasks().stream().map(task -> ((org.springframework.scheduling.config.CronTask)task.getTask()).getExpression()).collect(java.util.stream.Collectors.toSet());
                    assertEquals(java.util.Set.of("0 30 3 * * SUN"), crons);
                }
            } finally { processor.destroy(); }
        }
    }
}
