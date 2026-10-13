package dev.waterflex.scheduler.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.waterflex.scheduler.DeadlineSignals;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * A deadline or admission refusal raised while a connection is acquired keeps its meaning through Spring's wrapping.
 * Before, a booking search near its deadline answered 500 instead of 429 and kept its request claim until the lease ran out.
 */
class PublicApiStoreDeadlineTest {
    private static DataSource refusing(RuntimeException refusal) {
        return Required.value(DataSource.class.cast(Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (_, method, _) -> {
                    if (method.getName().equals("getConnection")) throw refusal;
                    throw new UnsupportedOperationException(method.getName());
                })));
    }

    private static PublicApiStore store(DataSource source) {
        return new PublicApiStore(new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    @Test
    void anAdmissionRefusalWhileConnectingReachesTheCallerUnwrapped() {
        var busy = new SearchAdmission.Busy("Insufficient deadline remaining for database admission");
        assertSame(busy, assertThrows(SearchAdmission.Busy.class, () -> store(refusing(busy)).asTenant("acme", () -> "unreached")));
    }

    @Test
    void anExpiredDeadlineWhileConnectingReachesTheCallerUnwrapped() {
        var expired = new SearchDeadline.Expired();
        assertSame(expired, assertThrows(SearchDeadline.Expired.class, () -> store(refusing(expired)).asTenant("acme", () -> "unreached")));
    }

    @Test
    void anyOtherConnectionFailureStaysADatabaseFailure() {
        var refused = new IllegalStateException("connection refused");
        var failure = assertThrows(CannotCreateTransactionException.class, () -> store(refusing(refused)).asTenant("acme", () -> "unreached"));
        assertSame(refused, failure.getCause());
    }

    @Test
    void unwrapFindsTheSignalAnywhereInTheCauseChain() {
        var busy = new SearchAdmission.Busy("full");
        var wrapped = new CannotGetJdbcConnectionException("no connection", new java.sql.SQLException("wrapper", busy));
        assertSame(busy, DeadlineSignals.unwrap(wrapped));
        var plain = new IllegalStateException("plain");
        assertSame(plain, DeadlineSignals.unwrap(plain));
    }
}
