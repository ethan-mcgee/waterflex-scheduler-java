package dev.waterflex.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatrixControllerTest {
    @TempDir Path temp;
    private static final MatrixController.Point FIRST = new MatrixController.Point(43.7350, 7.4200);
    private static final MatrixController.Point SECOND = new MatrixController.Point(43.7350, 7.4220);
    private static final String CHECKSUM = "3ffcd8c6989bf9371df3959caaf2a721367dd8bc2f3a37d80835409ad614a4a2";

    @Test
    void pinnedGraphPreservesDirectionGeometryAndIdentity() throws Exception {
        Path osm = temp.resolve("fixture-roads.osm");
        try (var source = getClass().getResourceAsStream("/fixture-roads.osm")) {
            assertNotNull(source);
            Files.copy(source, osm);
        }
        assertEquals(CHECKSUM, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(osm))));
        Files.writeString(temp.resolve("manifest.json"), "{\"mergedSha256\":\"" + CHECKSUM + "\"}");
        Path graph = temp.resolve("graph");
        MatrixController routing = new MatrixController(osm.toString(), graph.toString(), "fixture-v1", 100, 60);
        String identity = (String) routing.health().get("routingIdentity");
        assertEquals(64, identity.length());
        try {
            var matrix = routing.matrix(new MatrixController.Request(List.of(FIRST, SECOND), List.of(FIRST, SECOND), identity));
            assertEquals(identity, matrix.routingIdentity());
            assertTrue(matrix.legs().get(0).get(1).routable());
            assertTrue(matrix.legs().get(1).get(0).routable());
            assertTrue(matrix.legs().get(1).get(0).meters() > matrix.legs().get(0).get(1).meters());
            var geometry = routing.routeGeometry(new MatrixController.RouteRequest(List.of(FIRST, SECOND), identity));
            assertEquals(identity, geometry.routingIdentity());
            assertTrue(geometry.legs().getFirst().geometry().coordinates().size() >= 2);
            assertEquals(matrix.legs().get(0).get(1).meters(), geometry.legs().getFirst().meters());
            assertFalse(routing.matrix(new MatrixController.Request(List.of(FIRST),
                    List.of(new MatrixController.Point(0, 0)), identity)).legs().getFirst().getFirst().routable());
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> routing.matrix(
                    new MatrixController.Request(List.of(new MatrixController.Point(100, 0)), List.of(FIRST), identity)))
                    .getStatusCode().value());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> routing.matrix(
                    new MatrixController.Request(List.of(FIRST), List.of(SECOND), "old-graph")))
                    .getStatusCode().value());
        } finally { routing.close(); }
        Files.writeString(temp.resolve("manifest.json"), "{\"mergedSha256\":\"replacement-map\"}");
        MatrixController replacement = new MatrixController(osm.toString(), graph.toString(), "fixture-v1", 100, 60);
        try {
            assertNotEquals(identity, replacement.health().get("routingIdentity"));
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> replacement.matrix(
                    new MatrixController.Request(List.of(FIRST), List.of(SECOND), identity)))
                    .getStatusCode().value());
        } finally { replacement.close(); }
    }
}
