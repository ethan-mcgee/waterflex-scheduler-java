package dev.waterflex.scheduler;

import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** The portal's client boundaries are enforced by the database, not only by the portal's routes. */
class PortalClientsDatabaseIT {
    @Test void clientsKeepTheirRowsAndTechniciansApartEvenInOneMetro() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            String prefix = "clients-it-" + UUID.randomUUID();
            String acme = prefix + "-acme", brook = prefix + "-brook";
            for (String client : new String[] {acme, brook}) {
                jdbc.update("INSERT INTO client (id,name) VALUES (?,?)", client, client);
                jdbc.update("INSERT INTO dealership (id,\"clientId\",name,\"updatedAt\") VALUES (?,?,?,CURRENT_TIMESTAMP)", client, client, client);
                jdbc.update("INSERT INTO metro (id,name,timezone) VALUES (?,?,'America/Chicago')", client, client);
                jdbc.update("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.25,-95.93)", client, client, client, client);
                jdbc.update("INSERT INTO technician (id,\"clientId\",name,color,\"homeLat\",\"homeLng\",\"shiftStartMin\",\"shiftEndMin\",\"updatedAt\") VALUES (?,?,?,'#000000',41.25,-95.93,480,1020,CURRENT_TIMESTAMP)", client, client, client);
                jdbc.update("INSERT INTO customer (id,\"clientId\",\"firstName\",\"lastName\",email,phone) VALUES (?,?,'Client','Fixture','client@example.invalid','0000000000')", client, client);
            }
            jdbc.update("INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'1900-01-01')", acme, acme);

            rejects(jdbc, "clientId cannot change", "UPDATE dealership SET \"clientId\"=? WHERE id=?", brook, acme);
            rejects(jdbc, "clientId cannot change", "UPDATE technician SET \"clientId\"=? WHERE id=?", brook, acme);
            rejects(jdbc, "clientId cannot change", "UPDATE customer SET \"clientId\"=? WHERE id=?", brook, acme);
            rejects(jdbc, "depot of its own client",
                "INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2000-01-01')", acme, brook);
            rejects(jdbc, "depot of its own client",
                "UPDATE technician_depot_assignment SET \"depotId\"=? WHERE \"technicianId\"=?", brook, acme);
            rejects(jdbc, "another client's dealership", "UPDATE depot SET \"dealershipId\"=? WHERE id=?", brook, acme);
            // Two clients may serve the same metro, each with its own depots and technicians.
            jdbc.update("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.25,-95.93)", brook + "-second", acme, brook, brook);
            assertEquals(1, jdbc.update("UPDATE depot SET \"metroId\"=? WHERE id=?", acme, brook));
            rejects(jdbc, "depot of its own client",
                "INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2001-01-01')", acme, brook + "-second");
            rejects(jdbc, "another client's dealership", "UPDATE depot SET \"dealershipId\"=? WHERE id=?", acme, brook + "-second");

            // The same client may add depots to its own metro, and an unchanged clientId is not a change.
            jdbc.update("INSERT INTO depot (id,\"metroId\",\"dealershipId\",name,lat,lng) VALUES (?,?,?,?,41.26,-95.94)", acme + "-second", acme, acme, acme);
            jdbc.update("INSERT INTO technician_depot_assignment (\"technicianId\",\"depotId\",\"effectiveDate\") VALUES (?,?,'2000-01-01')", acme, acme + "-second");
            assertEquals(1, jdbc.update("UPDATE technician SET \"clientId\"=?, name='Renamed' WHERE id=?", acme, acme));
        });
    }

    /** Runs a statement that must fail with the named rule, inside a savepoint so the fixture transaction survives. */
    private static void rejects(JdbcTemplate jdbc, String rule, String sql, @Nullable Object... args) {
        jdbc.execute("SAVEPOINT client_rule");
        var error = assertThrows(DataAccessException.class, () -> jdbc.update(sql, args));
        jdbc.execute("ROLLBACK TO SAVEPOINT client_rule");
        String message = String.valueOf(error.getMostSpecificCause().getMessage());
        assertTrue(message.contains(rule), message);
    }
}
