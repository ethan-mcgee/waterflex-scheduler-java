package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Resolve each route endpoint from the depot assigned on the service date. */
public record RouteEndpoints(RoadClient.Point departure, RoadClient.Point returnTo) {
    public static final String COLUMNS = "t.\"homeLat\",t.\"homeLng\",ep.departure::text,ep.\"returnTo\"::text,p.lat,p.lng";
    public static final String JOINS = " LEFT JOIN LATERAL (SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=t.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) assignment ON true LEFT JOIN depot p ON p.id=assignment.\"depotId\" LEFT JOIN LATERAL (SELECT departure,\"returnTo\" FROM depot_endpoint_policy WHERE \"depotId\"=p.id AND \"effectiveDate\"<=? ORDER BY \"effectiveDate\" DESC LIMIT 1) ep ON true";

    public static RouteEndpoints from(java.sql.ResultSet rs, int offset) throws java.sql.SQLException {
        RoadClient.Point home = Required.location(rs, offset, offset + 1, HttpStatus.CONFLICT);
        String departure = rs.getString(offset + 2), returnTo = rs.getString(offset + 3);
        if (departure == null || returnTo == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician depot policy is missing");
        if (!departure.equals("HOME") && !departure.equals("DEPOT") || !returnTo.equals("HOME") && !returnTo.equals("DEPOT"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid depot endpoint policy");
        RoadClient.Point depot = departure.equals("DEPOT") || returnTo.equals("DEPOT")
                ? Required.location(rs, offset + 4, offset + 5, HttpStatus.CONFLICT) : home;
        return new RouteEndpoints(departure.equals("DEPOT") ? depot : home, returnTo.equals("DEPOT") ? depot : home);
    }

    public static RouteEndpoints forTechnician(JdbcTemplate jdbc, String technicianId, java.time.LocalDate day) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM technician t" + JOINS + " WHERE t.id=?",
                (rs, _) -> from(rs, 1), java.sql.Timestamp.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()), java.sql.Timestamp.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()), technicianId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician is missing");
        return Required.value(rows.getFirst());
    }
}
