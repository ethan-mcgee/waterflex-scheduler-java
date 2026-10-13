package dev.waterflex.scheduler.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.waterflex.scheduler.Required;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local Compose only: gives every portal client a working token file {@code <dir>/<clientId>} (mode 0600, written to a
 * temporary file and renamed). The client list is the JSON array {@code [{"id": ..., "name": ...}]} the portal writes,
 * since the scheduler never reads the portal's database. A file whose token this scheduler accepts is kept, so a
 * restart never rotates working tokens; a missing file, or one the scheduler does not accept (a new scheduler
 * database), is reissued and the client's earlier local tokens are revoked. Refuses unless LOCAL_TOKEN_BOOTSTRAP=true.
 */
public final class LocalTokenBootstrap {
    /** The label of tokens this bootstrap issues; a reissue revokes the earlier ones. */
    public static final String LABEL = "local-compose";

    public record Client(String id, String name) { }
    public record Result(List<String> issued, List<String> kept) { }

    private LocalTokenBootstrap() { }

    /** The portal's client list, checked strictly: an array of objects with exactly a valid id and a non-blank name. */
    static List<Client> clients(String json) {
        JsonNode root;
        try { root = new ObjectMapper().readTree(json); }
        catch (IOException malformed) { throw new IllegalArgumentException("The client list is not JSON", malformed); }
        if (root == null || !root.isArray()) throw new IllegalArgumentException("The client list must be a JSON array");
        List<Client> clients = new ArrayList<>();
        for (JsonNode entry : root) {
            if (entry == null || !entry.isObject() || entry.size() != 2)
                throw new IllegalArgumentException("Each client must be an object with exactly id and name");
            JsonNode id = entry.get("id"), name = entry.get("name");
            if (id == null || !id.isTextual() || name == null || !name.isTextual())
                throw new IllegalArgumentException("Each client needs a string id and name");
            String clientId = Required.value(id.textValue()), clientName = Required.value(name.textValue());
            if (!TenantAdministration.TENANT_ID.matcher(clientId).matches())
                throw new IllegalArgumentException("Client " + clientId + " cannot have a token file");
            if (clientName.isBlank()) throw new IllegalArgumentException("Client " + clientId + " has a blank name");
            clients.add(new Client(clientId, clientName));
        }
        return clients;
    }

    public static Result run(Map<String, String> env, Path clientList, Path dir, TenantAdministration tenants) throws IOException {
        if (!"true".equals(env.get("LOCAL_TOKEN_BOOTSTRAP")))
            throw new IllegalStateException("Local token bootstrap is disabled (set LOCAL_TOKEN_BOOTSTRAP=true, local Compose only)");
        List<Client> clients = clients(Required.value(Files.readString(clientList, StandardCharsets.UTF_8)));
        boolean posix = Required.value(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");
        Files.createDirectories(dir);
        if (posix) Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        List<String> issued = new ArrayList<>(), kept = new ArrayList<>();
        for (Iterator<Client> each = clients.iterator(); each.hasNext(); ) {
            Client client = Required.value(each.next());
            Path file = Required.value(dir.resolve(client.id()));
            if (Files.exists(file) && tenants.accepts(client.id(), Required.value(Files.readString(file, StandardCharsets.UTF_8).strip()))) {
                kept.add(client.id());
                continue;
            }
            String token = tenants.issue(client.id(), client.name(), LABEL, LABEL).token();
            Path temporary = Required.value(dir.resolve(client.id() + ".tmp"));
            Files.writeString(temporary, token + "\n", StandardCharsets.UTF_8);
            if (posix) Files.setPosixFilePermissions(temporary, Set.copyOf(PosixFilePermissions.fromString("rw-------")));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            issued.add(client.id());
        }
        return new Result(issued, kept);
    }
}
