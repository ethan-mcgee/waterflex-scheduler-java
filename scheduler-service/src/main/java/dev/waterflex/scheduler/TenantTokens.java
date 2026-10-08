package dev.waterflex.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves a public API token to its tenant. Only SHA-256 digests are stored, never plaintext tokens. */
@Component
public class TenantTokens {
    /** "wfs_" plus 32 random bytes in unpadded base64url, as issued by web/scripts/issue-tenant-token.ts. */
    static final Pattern FORMAT = Required.value(Pattern.compile("wfs_[A-Za-z0-9_-]{43}"));
    private final JdbcTemplate jdbc;

    public TenantTokens(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static boolean wellFormed(String token) { return FORMAT.matcher(token).matches(); }

    static String sha256(String token) {
        try {
            return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 unavailable", missing); }
    }

    /** The active tenant for this token, or null when the token is unknown, revoked or its tenant is disabled. */
    public @Nullable String tenantFor(String token) {
        if (!wellFormed(token)) return null;
        var tenants = jdbc.query("SELECT t.id FROM tenant_api_token k JOIN tenant t ON t.id=k.\"tenantId\" WHERE k.\"tokenSha256\"=? AND k.\"revokedAt\" IS NULL AND t.\"disabledAt\" IS NULL",
                (rs, _) -> DatabaseFacts.string(rs, 1), sha256(token));
        if (tenants.size() > 1) throw new IllegalStateException("Token digest resolves to several tenants");
        return tenants.isEmpty() ? null : tenants.getFirst();
    }
}
