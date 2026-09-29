package dev.waterflex.scheduler;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class TimeOffPollingTest {
    @ParameterizedTest @NullSource @ValueSource(strings = {"1", "1000", "30000"})
    void startsWithDefaultOrAllowedEnvironmentValue(@Nullable String configured) {
        try (var context = context(configured)) {
            context.refresh();
            assertEquals(configured == null ? 30000L : Long.parseLong(configured),
                    context.getBean(TimeOffPolling.class).delayMs());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "two", "0", "-1", "1.0", " 2", "9223372036854775808"})
    void rejectsInvalidEnvironmentAtStartup(String configured) {
        try (var context = context(configured)) {
            var failure = assertThrows(BeansException.class, context::refresh);
            assertInstanceOf(IllegalArgumentException.class, failure.getMostSpecificCause());
            assertTrue(Required.value(failure.getMostSpecificCause().getMessage()).contains("must be a positive integer"));
        }
    }

    @Test void explicitNullIsNotAnUnsetEnvironmentDefault() {
        assertThrows(IllegalArgumentException.class, () -> new TimeOffPolling(null));
    }

    private static AnnotationConfigApplicationContext context(@Nullable String configured) {
        var context = new AnnotationConfigApplicationContext();
        var sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Map<String, Object> environment = new HashMap<>();
        if (configured != null) environment.put("TIME_OFF_POLL_DELAY_MS", configured);
        sources.addFirst(new SystemEnvironmentPropertySource("test-environment", environment));
        context.register(TimeOffPolling.class);
        return context;
    }
}
