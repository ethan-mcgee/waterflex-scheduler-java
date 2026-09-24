package dev.waterflex.scheduler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Transparent JDBC observation. Never records SQL text, parameters, or customer data. */
@Component
public final class SearchDatabaseTelemetry implements BeanPostProcessor {
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource source)) return bean;
        return Required.value(Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (Object _, Method method, @Nullable Object @Nullable [] args) -> {
            Object result = invoke(source, Required.value(method), args);
            SearchDeadline deadline = SearchDeadline.current();
            if (result instanceof Connection connection && method.getName().equals("getConnection") && deadline != null)
                return connection(connection, deadline.telemetry());
            return result;
        }));
    }

    static Connection connection(Connection target, SearchTelemetry telemetry) {
        return Required.value(Connection.class.cast(Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (Object _, Method method, @Nullable Object @Nullable [] args) -> {
            if (method.getName().equals("commit") || method.getName().equals("rollback")) boundNetwork(target, method.getName().equals("rollback"));
            Object result = invoke(target, Required.value(method), args);
            if (result instanceof Statement statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                String sql = args != null && args.length > 0 && args[0] instanceof String value ? value : "";
                Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (Object _, Method operation, @Nullable Object @Nullable [] parameters) -> {
                    if (!operation.getName().startsWith("execute")) return invoke(statement, operation, parameters);
                    String text = parameters != null && parameters.length > 0 && parameters[0] instanceof String value ? value : sql;
                    String normalized = text.toUpperCase(java.util.Locale.ROOT);
                    boolean locking = normalized.contains("FOR UPDATE") || normalized.contains("FOR SHARE") || normalized.contains("PG_ADVISORY");
                    long started = System.nanoTime();
                    try { boundNetwork(target, false); return invoke(statement, operation, parameters); }
                    finally { telemetry.database(System.nanoTime() - started, locking); }
                });
            }
            return result;
        })));
    }
    /** Statement timeouts do not cover transport or COMMIT acknowledgement. Hikari restores this on close. */
    private static void boundNetwork(Connection target, boolean rollback) throws java.sql.SQLException {
        SearchDeadline deadline = SearchDeadline.current();
        if (deadline == null) return;
        long millis = rollback ? deadline.remainingNanos() / 1_000_000 : deadline.timeout(Required.value(java.time.Duration.ofSeconds(5))).toMillis();
        target.setNetworkTimeout(command -> Required.value(command).run(), Math.toIntExact(Math.max(1, Math.min(5000, millis))));
    }
    private static @Nullable Object invoke(Object target, Method method, @Nullable Object @Nullable [] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw Required.value(failure.getCause()); }
    }
}
