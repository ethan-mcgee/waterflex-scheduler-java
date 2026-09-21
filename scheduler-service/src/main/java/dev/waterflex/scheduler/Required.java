package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Required scheduling facts fail closed and roll back the surrounding transaction. */
public final class Required {
    private Required() { }
    public static <T> @NonNull T value(@Nullable T value, String name) {
        if (value == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incomplete scheduling data: " + name);
        return value;
    }
    public static <T> @NonNull T value(@Nullable T value) { return value(value, "required value"); }
    public static String string(ResultSet rs, int column) throws SQLException { return value(rs.getString(column), "column " + column); }
    public static Timestamp timestamp(ResultSet rs, int column) throws SQLException { return value(rs.getTimestamp(column), "timestamp column " + column); }
    public static int integer(ResultSet rs, int column) throws SQLException { int v = rs.getInt(column); if (rs.wasNull()) throw missing(column); return v; }
    public static @Nullable Long nullableLong(ResultSet rs, int column) throws SQLException { long v = rs.getLong(column); return rs.wasNull() ? null : v; }
    public static long longValue(ResultSet rs, int column) throws SQLException { long v = rs.getLong(column); if (rs.wasNull()) throw missing(column); return v; }
    public static boolean bool(ResultSet rs, int column) throws SQLException { boolean v = rs.getBoolean(column); if (rs.wasNull()) throw missing(column); return v; }
    public static double number(ResultSet rs, int column) throws SQLException {
        double v = rs.getDouble(column);
        if (rs.wasNull() || !Double.isFinite(v)) throw missing(column);
        return v;
    }
    public static RoadClient.Point location(ResultSet rs, int lat, int lng, HttpStatus status) throws SQLException {
        double latitude = rs.getDouble(lat); boolean latitudeMissing = rs.wasNull();
        double longitude = rs.getDouble(lng); boolean longitudeMissing = rs.wasNull();
        if (latitudeMissing || longitudeMissing || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
            throw new ResponseStatusException(status, "Missing or invalid location coordinates");
        return new RoadClient.Point(latitude, longitude);
    }
    public static <T> T query(JdbcTemplate jdbc, String sql, Class<T> type, @Nullable Object... args) {
        return value(jdbc.queryForObject(sql, type, args), "query result");
    }
    private static ResponseStatusException missing(int column) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Missing or invalid scheduling column " + column);
    }
}
