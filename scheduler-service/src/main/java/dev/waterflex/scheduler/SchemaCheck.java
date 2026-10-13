package dev.waterflex.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Whether the database holds the schema this build was written for. The serving scheduler never migrates; it checks
 * with its own login, reports the result as the readiness component {@code schema}, and keeps the answer once the
 * schema matches (a schema only changes through a migration, which comes with a new build and a restart).
 */
@Component
public class SchemaCheck {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(SchemaCheck.class));

    /** No check has finished yet; one is running. */
    public static final class SchemaUnchecked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        SchemaUnchecked() { super("The schema check has not finished"); }
    }

    private final DataSource source;
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile boolean current;
    private volatile @Nullable RuntimeException lastFailure;

    public SchemaCheck(DataSource source) { this.source = source; }

    private void check() {
        try {
            SchemaMigration.validate(source);
            current = true;
            lastFailure = null;
        } catch (RuntimeException failure) {
            lastFailure = failure;
            throw failure;
        }
    }

    /**
     * Returns when the schema is known to match. Otherwise it starts a check in the background (one at a time, so a
     * readiness timeout never cuts one short) and throws the last failure, or {@link SchemaUnchecked} before the first.
     */
    public void verify() {
        if (current) return;
        if (checking.compareAndSet(false, true))
            Thread.ofVirtual().start(() -> {
                try { check(); }
                catch (RuntimeException _) { /* kept in lastFailure for the next readiness call */ }
                finally { checking.set(false); }
            });
        if (current) return;
        RuntimeException failure = lastFailure;
        throw failure != null ? failure : new SchemaUnchecked();
    }

    /** Checks once at startup, so the log names the fix rather than a failed query. */
    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        try { check(); }
        catch (SchemaMigration.SchemaNotCurrent outdated) { LOG.error("{}", outdated.getMessage()); }
        catch (RuntimeException unreachable) { LOG.warn("Schema check deferred: the database did not answer ({})", unreachable.getClass().getSimpleName()); }
    }
}
