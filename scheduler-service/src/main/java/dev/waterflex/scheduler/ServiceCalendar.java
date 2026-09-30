package dev.waterflex.scheduler;

import java.time.Instant;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Service dates only. Expiry, leases, timestamps and deadlines always use real clocks. */
@Component
public final class ServiceCalendar {
    private final @Nullable Instant reference;

    public ServiceCalendar() { reference = null; }

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceCalendar(Environment environment, JdbcTemplate jdbc) {
        String configured = environment.getProperty("benchmark.calendar-reference", "");
        if (configured.isBlank()) { reference = null; return; }
        if (!environment.acceptsProfiles(Profiles.of("benchmark")))
            throw new IllegalArgumentException("Fixed service calendar requires benchmark profile");
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("Benchmark requires local PostgreSQL");
        URI database = URI.create(url.substring(5));
        String host = database.getHost();
        if (host == null || !java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host)
                || !"/waterflex_test".equals(database.getPath())
                || !"waterflex_test".equals(Required.query(jdbc, "SELECT current_database()", String.class))
                || !Required.query(jdbc, "SELECT current_schema()", String.class).startsWith("benchmark_"))
            throw new IllegalArgumentException("Fixed service calendar requires local waterflex_test and benchmark_ schema");
        reference = Instant.parse(configured);
    }

    public Instant now() { return at(Required.value(Instant.now())); }
    public Instant at(Instant realInstant) { Instant fixed = reference; return fixed == null ? realInstant : fixed; }
    public String reference() { Instant fixed = reference; return fixed == null ? "real-time" : Required.value(fixed.toString()); }
}
