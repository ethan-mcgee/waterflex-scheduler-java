package dev.waterflex.scheduler;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which clients serve a metro. The scheduler's own database paths (daily previews and repairs, booking, dispatch
 * geometry, the overnight batch) read all of a metro's technicians and appointments together, so they serve only a
 * metro whose depots all belong to one client. A metro shared by several clients is scheduled through the public API,
 * where each client sends a snapshot of its own facts.
 */
public final class MetroTenancy {
    private MetroTenancy() { }

    /** Metros with at most one client: the ones the database paths may read whole. */
    public static final String SINGLE_CLIENT_METROS = "SELECT m.id FROM metro m WHERE (SELECT count(DISTINCT d.\"clientId\") FROM depot p "
            + "JOIN dealership d ON d.id=p.\"dealershipId\" WHERE p.\"metroId\"=m.id) <= 1 ORDER BY m.id";

    public static boolean shared(JdbcTemplate jdbc, String metroId) {
        int clients = DatabaseFacts.query(jdbc, "SELECT count(DISTINCT d.\"clientId\") FROM depot p JOIN dealership d ON d.id=p.\"dealershipId\" WHERE p.\"metroId\"=?",
                Integer.class, metroId);
        return clients > 1;
    }

    /** Refuses a metro served by more than one client; call it in the same transaction as the reads it protects. */
    public static void requireSingleClient(JdbcTemplate jdbc, String metroId) {
        if (shared(jdbc, metroId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Metro " + metroId + " is served by more than one client; schedule it through the public API");
    }
}
