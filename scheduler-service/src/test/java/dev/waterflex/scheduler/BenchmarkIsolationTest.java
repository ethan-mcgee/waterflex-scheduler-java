package dev.waterflex.scheduler;

import dev.waterflex.scheduler.optimizer.OptimizationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BenchmarkIsolationTest {
    @Test void benchmarkRemovesMaintenanceTasksButProductionRetainsDefaults() throws Exception {
        var properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-benchmark.properties"));
        for (boolean benchmark : new boolean[]{false, true}) {
            var factory = new DefaultListableBeanFactory();
            var env = new org.springframework.mock.env.MockEnvironment();
            if (benchmark) properties.forEach((key, value) -> env.setProperty(Required.value(key.toString()), Required.value(value.toString())));
            factory.addEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            var processor = new ScheduledAnnotationBeanPostProcessor();
            processor.setBeanFactory(factory);
            processor.setEmbeddedValueResolver(env::resolveRequiredPlaceholders);
            try {
                processor.postProcessAfterInitialization(mock(OptimizationService.class), "optimizer");
                processor.postProcessAfterInitialization(mock(RoadClient.class), "roads");
                processor.afterSingletonsInstantiated();
                assertEquals(benchmark ? 0 : 2, processor.getScheduledTasks().size());
                if (!benchmark) {
                    var crons = processor.getScheduledTasks().stream().map(task -> ((org.springframework.scheduling.config.CronTask)task.getTask()).getExpression()).collect(java.util.stream.Collectors.toSet());
                    assertEquals(java.util.Set.of("0 0 2 * * *", "0 30 3 * * SUN"), crons);
                }
            } finally { processor.destroy(); }
        }
        assertEquals("false", properties.getProperty("routing.prewarm.enabled"));
        assertEquals("false", properties.getProperty("time-off.analysis.enabled"));
    }
    @Test void disabledQueuedAnalysisDoesNotTouchDatabase() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new TimeOffService(jdbc, mock(OptimizationService.class), mock(ScheduleGuardService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "analysisEnabled", false);
        service.processQueued(); verifyNoInteractions(jdbc);
    }
}
