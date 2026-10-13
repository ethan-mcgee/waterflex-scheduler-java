package dev.waterflex.scheduler;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes thin store rows (requests, proposals, offer sets, receipts, booking days) older than the operator's
 * retention period, {@code scheduler.retention.days} ({@code SCHEDULER_RETENTION_DAYS}). The period is required: the
 * scheduler does not start without it, since how long receipts are kept for audit is the operator's decision. The
 * purge runs daily, in batches, through the database's {@code scheduler_purge} function, and several replicas may
 * run it at once.
 */
@Component
public class Retention {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(Retention.class));
    /** Rows per table per round; small enough that each round holds its locks briefly. */
    static final int BATCH = 5_000;
    /** A period beyond any audit requirement; it guards against a value in the wrong unit. */
    static final int MAX_DAYS = 3_650;

    private final JdbcTemplate jdbc;
    private final int days;
    private final Clock clock;
    private final LongAdder deleted = new LongAdder();

    @Autowired
    public Retention(JdbcTemplate jdbc, @Value("${scheduler.retention.days:}") String days) {
        this(jdbc, days(days), Required.value(Clock.systemUTC()));
    }

    Retention(JdbcTemplate jdbc, int days, Clock clock) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("scheduler.retention.days must be 1 to " + MAX_DAYS);
        this.jdbc = jdbc; this.days = days; this.clock = clock;
    }

    /** The configured period, which must be given: an unset or malformed value stops startup with this message. */
    static int days(String value) {
        if (value.isBlank())
            throw new IllegalStateException("SCHEDULER_RETENTION_DAYS (scheduler.retention.days) is required: how many days the thin store keeps requests, proposals, offers and receipts");
        try { return Integer.parseInt(value.strip()); }
        catch (NumberFormatException malformed) { throw new IllegalStateException("SCHEDULER_RETENTION_DAYS must be a whole number of days, not '" + value + "'", malformed); }
    }

    public int days() { return days; }

    /** Rows the purge has deleted since startup. */
    public long deleted() { return deleted.sum(); }

    @Scheduled(cron = "${scheduler.retention.cron:0 45 3 * * *}", zone = "America/Chicago")
    public void scheduledPurge() {
        try { purge(); }
        catch (RuntimeException failure) { LOG.error("Retention purge failed; it runs again tomorrow", failure); }
    }

    /** Deletes every row older than the period, batch by batch, and returns how many it deleted. */
    public long purge() {
        Instant cutoff = Required.value(Required.value(clock.instant()).minus(Duration.ofDays(days)));
        long total = 0;
        while (true) {
            Integer round = jdbc.queryForObject("SELECT scheduler_purge(?, ?)", Integer.class, Timestamp.from(cutoff), BATCH);
            int removed = Required.value(round, "scheduler_purge result");
            if (removed < 0) throw new IllegalStateException("scheduler_purge reported a negative count");
            total += removed;
            deleted.add(removed);
            if (removed == 0) break;
        }
        LOG.info("Retention purge deleted {} rows older than {} ({} days)", total, cutoff, days);
        return total;
    }
}
