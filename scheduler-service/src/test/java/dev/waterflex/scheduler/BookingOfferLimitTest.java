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

class BookingOfferLimitTest {
    @ParameterizedTest @NullSource @ValueSource(strings = {"1", "2", "4"})
    void startsWithDefaultOrAllowedEnvironmentValue(@Nullable String configured) {
        try (var context = context(configured)) {
            context.refresh();
            assertEquals(1,
                    context.getBean(BookingOfferLimit.class).value());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "two", "0", "3", "-1", "5", "1.0", "02", " 2"})
    void rejectsInvalidEnvironmentAtStartup(String configured) {
        try (var context = context(configured)) {
            var failure = assertThrows(BeansException.class, context::refresh);
            assertInstanceOf(IllegalArgumentException.class, failure.getMostSpecificCause());
            assertTrue(Required.value(failure.getMostSpecificCause().getMessage()).contains("must be exactly 1, 2, or 4"));
        }
    }

    @Test void explicitNullIsNotAnUnsetEnvironmentDefault() {
        assertThrows(IllegalArgumentException.class, () -> new BookingOfferLimit(null));
    }

    private static AnnotationConfigApplicationContext context(@Nullable String configured) {
        var context = new AnnotationConfigApplicationContext();
        var sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        Map<String, Object> environment = new HashMap<>();
        if (configured != null) environment.put("BOOKING_OFFER_LIMIT", configured);
        sources.addFirst(new SystemEnvironmentPropertySource("test-environment", environment));
        context.register(BookingOfferLimit.class);
        return context;
    }
}
