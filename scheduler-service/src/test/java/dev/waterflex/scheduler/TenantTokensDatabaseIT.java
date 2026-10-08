package dev.waterflex.scheduler;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture rows are rolled back. */
class TenantTokensDatabaseIT {
    @Test void onlyActiveTokensOfEnabledTenantsResolve() {
        String url = Required.value(System.getenv("JDBC_DATABASE_URL"), "isolated integration database URL");
        assertEquals("/waterflex_test", URI.create(url.substring("jdbc:".length())).getPath());
        var source = new DriverManagerDataSource(url, "waterflex", "waterflex");
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var tokens = new TenantTokens(jdbc);
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            String tenant = "it-" + UUID.randomUUID().toString().substring(0, 8);
            String active = TenantAuthenticationTest.VECTOR, revoked = "wfs_" + "R".repeat(43), unknown = "wfs_" + "U".repeat(43);
            jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", tenant, "Fixture tenant");
            jdbc.update("INSERT INTO tenant_api_token (id,\"tenantId\",\"tokenSha256\",label) VALUES (?,?,?,?)", UUID.randomUUID().toString(), tenant, TenantTokens.sha256(active), "active");
            jdbc.update("INSERT INTO tenant_api_token (id,\"tenantId\",\"tokenSha256\",label,\"revokedAt\") VALUES (?,?,?,?,clock_timestamp())", UUID.randomUUID().toString(), tenant, TenantTokens.sha256(revoked), "revoked");
            assertEquals(tenant, tokens.tenantFor(active));
            assertNull(tokens.tenantFor(revoked));
            assertNull(tokens.tenantFor(unknown));
            assertNull(tokens.tenantFor("not-a-token"));
            jdbc.update("UPDATE tenant SET \"disabledAt\"=clock_timestamp() WHERE id=?", tenant);
            assertNull(tokens.tenantFor(active));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO tenant (id,name) VALUES ('Bad_ID','x')"));
        });
    }
}
