package dev.waterflex.scheduler.admin;

import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.TenantTokens;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenants (clients of the public API) and their tokens, administered by the operator as the schema owner. The running
 * scheduler only reads these tables. A token's plaintext is returned once when issued; only its digest is stored.
 */
public final class TenantAdministration {
    /** Same rule as the tenant.id CHECK constraint. */
    public static final Pattern TENANT_ID = Required.value(Pattern.compile("[a-z0-9][a-z0-9-]{0,62}"));

    public record Issued(String id, String token) { }
    public record TenantRow(String id, String name, boolean disabled, int activeTokens) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TenantAdministration(DataSource owner) {
        this.jdbc = new JdbcTemplate(owner);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(owner));
    }

    private static String text(String value, String what) {
        String trimmed = Required.value(value.strip());
        if (trimmed.isEmpty()) throw new IllegalArgumentException(what + " is required");
        return trimmed;
    }

    /**
     * Issues a token for the tenant, creating the tenant when it does not exist. With {@code replacingLabel}, the
     * tenant's earlier active tokens with that label are revoked in the same transaction (a lost token file reissued).
     */
    public Issued issue(String tenantId, String name, String label, @Nullable String replacingLabel) {
        if (!TENANT_ID.matcher(tenantId).matches())
            throw new IllegalArgumentException("Tenant ID must be lowercase letters, digits and hyphens (1 to 63 characters)");
        String tenantName = text(name, "Tenant name"), tokenLabel = text(label, "Token label");
        String token = TenantTokens.generate(), id = Required.value(UUID.randomUUID().toString());
        transactions.executeWithoutResult(_ -> {
            List<Boolean> disabled = jdbc.query("SELECT \"disabledAt\" IS NOT NULL FROM tenant WHERE id=? FOR UPDATE",
                    (rs, _) -> rs.getBoolean(1), tenantId);
            if (disabled.isEmpty()) jdbc.update("INSERT INTO tenant (id,name) VALUES (?,?)", tenantId, tenantName);
            else if (Required.value(disabled.getFirst())) throw new IllegalStateException("Tenant " + tenantId + " is disabled");
            if (replacingLabel != null)
                jdbc.update("UPDATE tenant_api_token SET \"revokedAt\"=clock_timestamp() WHERE \"tenantId\"=? AND label=? AND \"revokedAt\" IS NULL",
                        tenantId, replacingLabel);
            jdbc.update("INSERT INTO tenant_api_token (id,\"tenantId\",\"tokenSha256\",label) VALUES (?,?,?,?)",
                    id, tenantId, TenantTokens.sha256(token), tokenLabel);
        });
        return new Issued(id, token);
    }

    /** Revokes an active token; false when no active token has this ID. */
    public boolean revoke(String tokenId) {
        return jdbc.update("UPDATE tenant_api_token SET \"revokedAt\"=clock_timestamp() WHERE id=? AND \"revokedAt\" IS NULL", tokenId) == 1;
    }

    /** Disables or enables a tenant; a disabled tenant's tokens get 401. False when the tenant does not exist. */
    public boolean setDisabled(String tenantId, boolean disabled) {
        return jdbc.update(disabled
                ? "UPDATE tenant SET \"disabledAt\"=COALESCE(\"disabledAt\", clock_timestamp()) WHERE id=?"
                : "UPDATE tenant SET \"disabledAt\"=NULL WHERE id=?", tenantId) == 1;
    }

    public List<TenantRow> tenants() {
        return Required.value(jdbc.query("""
                SELECT t.id, t.name, t."disabledAt" IS NOT NULL AS disabled,
                       (SELECT count(*) FROM tenant_api_token k WHERE k."tenantId"=t.id AND k."revokedAt" IS NULL)::int AS active
                FROM tenant t ORDER BY t.id""",
                (rs, _) -> new TenantRow(Required.value(rs.getString(1), "tenant.id"), Required.value(rs.getString(2), "tenant.name"),
                        rs.getBoolean(3), rs.getInt(4))));
    }

    /** Whether this token is active for this enabled tenant, as the scheduler would accept it. */
    public boolean accepts(String tenantId, String token) {
        if (!TenantTokens.wellFormed(token)) return false;
        Integer found = jdbc.queryForObject("""
                SELECT count(*)::int FROM tenant_api_token k JOIN tenant t ON t.id=k."tenantId"
                WHERE k."tokenSha256"=? AND k."revokedAt" IS NULL AND t.id=? AND t."disabledAt" IS NULL""",
                Integer.class, TenantTokens.sha256(token), tenantId);
        return Required.value(found, "token count") == 1;
    }
}
