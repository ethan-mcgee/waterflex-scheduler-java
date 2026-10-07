package dev.waterflex.scheduler;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
/** Applies the existing request clock to caller-owned PostgreSQL transactions. */
public final class DatabaseDeadline {
    private DatabaseDeadline() { }
    public static void apply(JdbcTemplate jdbc) {
        SearchDeadline current = SearchDeadline.current();
        if (current == null) return;
        long millis = Math.max(1, current.timeout(Required.value(Duration.ofSeconds(5))).toMillis());
        jdbc.queryForList("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)",
                Long.toString(millis), Long.toString(millis));
    }
}
