package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The dealership policy is the sole source of route endpoints. */
public record RouteEndpoints(RoadClient.Point departure, RoadClient.Point returnTo) {
    public static final String COLUMNS = "t.\"homeLat\",t.\"homeLng\",ep.departure::text,ep.\"returnTo\"::text,p.lat,p.lng";
    public static final String JOINS = " LEFT JOIN dealership d ON d.id=t.\"dealershipId\" LEFT JOIN depot p ON p.id=d.\"depotId\" LEFT JOIN LATERAL (SELECT departure,\"returnTo\" FROM dealership_endpoint_policy WHERE \"dealershipId\"=d.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) ep ON true";

    public static RouteEndpoints from(java.sql.ResultSet rs, int offset) throws java.sql.SQLException {
        RoadClient.Point home = Required.location(rs, offset, offset + 1, HttpStatus.CONFLICT);
        String departure = rs.getString(offset + 2), returnTo = rs.getString(offset + 3);
        if (departure == null || returnTo == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician dealership is missing");
        if (!departure.equals("HOME") && !departure.equals("DEPOT") || !returnTo.equals("HOME") && !returnTo.equals("DEPOT"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid dealership endpoint policy");
        RoadClient.Point depot = departure.equals("DEPOT") || returnTo.equals("DEPOT")
                ? Required.location(rs, offset + 4, offset + 5, HttpStatus.CONFLICT) : home;
        return new RouteEndpoints(departure.equals("DEPOT") ? depot : home, returnTo.equals("DEPOT") ? depot : home);
    }

    public static RouteEndpoints forTechnician(JdbcTemplate jdbc, String technicianId, java.time.LocalDate day) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM technician t" + JOINS + " WHERE t.id=?",
                (rs, _) -> from(rs, 1), java.sql.Timestamp.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()), technicianId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician is missing");
        return Required.value(rows.getFirst());
    }
}
