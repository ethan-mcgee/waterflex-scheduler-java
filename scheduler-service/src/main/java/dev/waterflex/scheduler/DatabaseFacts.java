package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Required scheduling facts fail closed and roll back the surrounding transaction. */
public final class DatabaseFacts {
    private DatabaseFacts() { }
    public static String string(ResultSet rs, int column) throws SQLException { return Required.value(rs.getString(column), "column " + column); }
    public static Timestamp timestamp(ResultSet rs, int column) throws SQLException { return Required.value(rs.getTimestamp(column), "timestamp column " + column); }
    public static int integer(ResultSet rs, int column) throws SQLException { int v = rs.getInt(column); if (rs.wasNull()) throw missing(column); return v; }
    public static @Nullable Long nullableLong(ResultSet rs, int column) throws SQLException { long v = rs.getLong(column); return rs.wasNull() ? null : v; }
    public static boolean bool(ResultSet rs, int column) throws SQLException { boolean v = rs.getBoolean(column); if (rs.wasNull()) throw missing(column); return v; }
    public static java.math.BigDecimal decimal(ResultSet rs, int column) throws SQLException {
        return Required.value(rs.getBigDecimal(column), "decimal column " + column);
    }
    public static <T> T query(JdbcTemplate jdbc, String sql, Class<T> type, @Nullable Object... args) {
        try { return Required.value(jdbc.queryForObject(sql, type, args), "query result"); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Incomplete scheduling data: required query row missing", e);
        }
    }
    private static ResponseStatusException missing(int column) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Missing or invalid scheduling column " + column);
    }
}
