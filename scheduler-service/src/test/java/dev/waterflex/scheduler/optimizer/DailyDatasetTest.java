package dev.waterflex.scheduler.optimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import dev.waterflex.scheduler.Required;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import static org.junit.jupiter.api.Assertions.*;

class DailyDatasetTest {
    private static String fixture() throws Exception {
        try (var stream = Required.value(DailyDatasetTest.class.getResourceAsStream("/daily-dataset-v1.json"))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static ObjectNode draft() throws Exception { return Required.value((ObjectNode) new ObjectMapper().readTree(fixture())); }
    private static ObjectNode first(ObjectNode root, String field) { return Required.value((ObjectNode) root.path(field).get(0)); }
    private static ObjectNode road(ObjectNode root) { return Required.value((ObjectNode) root.path("roads").path("entries").get(0)); }
    private static void reject(Consumer<@NonNull ObjectNode> mutate) throws Exception {
        ObjectNode root = draft(); mutate.accept(root);
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(Required.value(root.toString())));
    }
    @Test void denseSparseRoundTripsShareHashFactsAndIndependentResults() throws Exception {
        DailyDataset sparse = DailyDataset.parse(DailyDataset.seal(fixture()));
        for (var encoding : DailyDataset.Encoding.values()) {
            DailyDataset decoded = DailyDataset.parse(sparse.json(Required.value(encoding)));
            assertEquals(sparse.contentHash(), decoded.contentHash()); assertEquals(sparse.facts(), decoded.facts());
            DayPlan first = decoded.toDayPlan(), second = decoded.toDayPlan();
            assertSame(decoded.facts(), first.getFacts()); assertSame(first.getFacts(), second.getFacts());
            assertNotSame(first.getRoutes().getFirst(), second.getRoutes().getFirst());
            assertEquals(RouteEvaluator.evaluate(sparse.toDayPlan()), RouteEvaluator.evaluate(first));
            assertTrue(RouteEvaluator.evaluate(first).feasible());
        }
        assertEquals(540, sparse.facts().matrix().get("v0>v1").seconds());
        assertEquals(600, sparse.facts().matrix().get("v1>v0").seconds());
        assertTrue(sparse.json(DailyDataset.Encoding.SPARSE).contains("\"regularHourly\":\"20.02\""));
    }
    @Test void canonicalHashIgnoresObjectAndSparseEntryOrder() throws Exception {
        ObjectNode root = draft(); ArrayNode entries = (ArrayNode) root.path("roads").path("entries");
        List<com.fasterxml.jackson.databind.JsonNode> reversed = new ArrayList<>(); entries.forEach(reversed::add);
        Collections.reverse(reversed); entries.removeAll(); reversed.forEach(entries::add);
        assertEquals(DailyDataset.parse(DailyDataset.seal(fixture())).contentHash(), DailyDataset.parse(DailyDataset.seal(Required.value(root.toString()))).contentHash());
    }
    @Test void unknownMissingNullDuplicateAndNonfiniteJsonFailClosed() throws Exception {
        reject(root -> root.put("typo", 1)); reject(root -> first(root, "technicians").put("typo", 1));
        reject(root -> root.remove("rates")); reject(root -> ((ObjectNode) root.path("rates")).putNull("regularHourly"));
        reject(root -> first(root, "technicians").putNull("shiftStart")); reject(root -> first(root, "visits").putNull("durationMinutes"));
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(Required.value(fixture().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"))));
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(fixture() + " {}"));
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(Required.value(fixture().replace("\"latitude\": 41", "\"latitude\": NaN"))));
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.parse(fixture()));
        reject(root -> ((ObjectNode) root.path("rates")).put("regularHourly", 20.02));
        reject(root -> ((ObjectNode) root.path("rates")).put("regularHourly", "0.0000000000000000000000000000001"));
        reject(root -> ((ObjectNode) root.path("versions")).put("cost", "legacy-double-v1"));
        reject(root -> ((ObjectNode) root.path("rates")).put("regularHourly", "Infinity"));
    }
    @Test void identityIndexAssignmentAndConfigurationErrorsAreRejected() throws Exception {
        reject(root -> ((ObjectNode) root.path("technicians").get(1)).put("id", "t0"));
        reject(root -> ((ObjectNode) root.path("visits").get(1)).put("id", "v0"));
        reject(root -> ((ObjectNode) root.path("locations").get(1)).put("id", "departure0"));
        reject(root -> first(root, "technicians").put("id", "t>0"));
        reject(root -> first(root, "visits").put("id", "t0:return"));
        reject(root -> first(root, "technicians").put("departureLocation", 6));
        reject(root -> first(root, "visits").put("service", -1));
        reject(root -> first(root, "visits").put("originalTechnician", 2));
        reject(root -> ((ArrayNode) first(root, "technicians").path("assigned")).add(0));
        reject(root -> ((ArrayNode) root.path("unassigned")).add(0));
        reject(root -> ((ArrayNode) first(root, "technicians").path("assigned")).removeAll());
        reject(root -> { ((ArrayNode) first(root, "technicians").path("assigned")).removeAll(); ((ArrayNode) root.path("unassigned")).add(0); });
        reject(root -> root.put("mode", "COLD")); reject(root -> first(root, "technicians").put("pinnedPrefix", 2));
        reject(root -> ((ObjectNode) root.path("search")).put("remainingMillis", 0));
        reject(root -> ((ObjectNode) root.path("versions")).put("cost", "unknown"));
        reject(root -> ((ArrayNode) root.path("revisions").path("schedule")).removeAll());
        reject(root -> ((ArrayNode) root.path("technicians")).removeAll());
        reject(root -> first(root, "visits").put("durationMinutes", 0));
        reject(root -> first(root, "visits").put("windowEnd", "2026-10-12T10:00:00Z"));
        reject(root -> first(root, "technicians").put("shiftEnd", "2026-10-12T09:00:00Z"));
        reject(root -> first(root, "technicians").put("maxDailyMinutes", -1));
        reject(root -> ((ArrayNode) first(root, "technicians").path("absences")).addObject()
                .put("start", "2026-10-12T12:00:00Z").put("end", "2026-10-12T11:00:00Z"));
        reject(root -> ((ObjectNode) root.path("routing")).putNull("identity"));
    }
    @Test void missingUnreachableAndDirectedRoadStatesRemainDistinct() throws Exception {
        reject(root -> ((ArrayNode) root.path("roads").path("entries")).remove(0));
        reject(root -> ((ArrayNode) root.path("roads").path("entries")).add(road(root).deepCopy()));
        reject(root -> road(root).put("from", 6)); reject(root -> road(root).put("seconds", -1));
        reject(root -> road(root).put("meters", Long.MAX_VALUE)); reject(root -> road(root).put("state", "UNREACHABLE"));
        reject(root -> road(root).put("seconds", 1.5)); reject(root -> ((ObjectNode) root.path("roads")).put("size", 5));
        ObjectNode root = draft(); road(root).put("state", "UNREACHABLE").putNull("seconds").putNull("meters");
        DailyDataset dataset = DailyDataset.parse(DailyDataset.seal(Required.value(root.toString())));
        assertTrue(dataset.facts().unreachable().contains("t0>v0")); assertFalse(dataset.facts().matrix().containsKey("t0>v0"));
        assertFalse(RouteEvaluator.evaluate(dataset.toDayPlan()).feasible());
        assertEquals(dataset.contentHash(), DailyDataset.parse(dataset.json(DailyDataset.Encoding.DENSE)).contentHash());
        // Unqualified moves still reach the current selector, so their roads must also be declared.
        reject(value -> { ((ArrayNode) first(value, "technicians").path("qualifications")).removeAll(); ((ArrayNode) value.path("roads").path("entries")).remove(1); });
    }
    @Test void denseDimensionsRequiredCellsAndSelfStatesAreValidated() throws Exception {
        var dataset = DailyDataset.parse(DailyDataset.seal(fixture()));
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(dataset.json(DailyDataset.Encoding.DENSE)); root.putNull("contentHash");
        ((ArrayNode) root.path("roads").path("cells").get(0)).remove(0);
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(Required.value(root.toString())));
        ObjectNode missing = (ObjectNode) new ObjectMapper().readTree(dataset.json(DailyDataset.Encoding.DENSE)); missing.putNull("contentHash");
        ((ObjectNode) missing.path("roads").path("cells").get(0).get(4)).put("state", "NOT_REQUIRED").putNull("seconds").putNull("meters");
        assertThrows(IllegalArgumentException.class, () -> DailyDataset.seal(Required.value(missing.toString())));
        reject(value -> { road(value).put("to", 0); });
    }
    @Test void hashDetectsChangedProvenanceFactsAndAllowances() throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(DailyDataset.seal(fixture()));
        root.put("snapshotId", "different"); assertThrows(IllegalArgumentException.class, () -> DailyDataset.parse(Required.value(root.toString())));
        root.putNull("contentHash"); DailyDataset changed = DailyDataset.parse(DailyDataset.seal(Required.value(root.toString())));
        assertNotEquals(DailyDataset.parse(DailyDataset.seal(fixture())).contentHash(), changed.contentHash());
    }
    @Test void emptyDemandHasAnExplicitCompleteEmptyOutcome() throws Exception {
        ObjectNode root = draft();
        for (String field : List.of("locations", "services", "technicians", "visits", "unassigned")) root.putArray(field);
        ((ObjectNode) root.path("revisions")).putArray("schedule"); root.put("mode", "COLD");
        ObjectNode roads = root.putObject("roads"); roads.put("encoding", "SPARSE").put("size", 0).putArray("entries");
        DayPlan plan = DailyDataset.parse(DailyDataset.seal(Required.value(root.toString()))).toDayPlan();
        assertTrue(RouteEvaluator.evaluate(plan).feasible()); assertEquals(0, RouteEvaluator.evaluate(plan).costCents());
    }
    @Test void coldPartialAndPinnedDatasetsRoundTripExplicitUnresolvedDemandAndNullableOriginals() throws Exception {
        for (String mode : List.of("COLD", "PARTIAL", "REPAIR")) {
            ObjectNode root = draft(); root.put("mode", mode);
            for (var visit : root.path("visits")) ((ObjectNode) visit).putNull("originalTechnician").putNull("originalPlannedStart");
            ((ArrayNode) root.path("unassigned")).add(mode.equals("COLD") ? 0 : 1);
            ((ArrayNode) root.path("technicians").get(1).path("assigned")).removeAll();
            if (mode.equals("COLD")) {
                ((ArrayNode) root.path("technicians").get(0).path("assigned")).removeAll();
                ((ArrayNode) root.path("unassigned")).add(1);
            } else first(root, "technicians").put("pinnedPrefix", 1);
            var dataset = DailyDataset.parse(DailyDataset.seal(Required.value(root.toString())));
            var dense = DailyDataset.parse(dataset.json(DailyDataset.Encoding.DENSE));
            DayPlan plan = dense.toDayPlan();
            assertEquals(dataset.contentHash(), dense.contentHash()); assertEquals(mode, plan.getMode().name());
            assertEquals(mode.equals("COLD") ? List.of("v0", "v1") : List.of("v1"), plan.getUnassignedVisitIds());
            assertNull(plan.getVisits().getFirst().getOriginalTechnicianId()); assertNull(plan.getVisits().getFirst().getOriginalPlannedStart());
            assertEquals(mode.equals("COLD") ? 0 : 1, PlanCopies.copy(plan).getRoutes().getFirst().getPinnedPrefix());
        }
        reject(root -> first(root, "visits").putNull("originalPlannedStart"));
        reject(root -> first(root, "visits").putNull("originalTechnician"));
        reject(root -> first(root, "technicians").putArray("pinnedVisits").add(0));
        reject(root -> ((ObjectNode) root.path("versions")).put("score", "hard-medium-soft-decimal-v1"));
    }
    @Test void zeroTechniciansPreservesNonemptyDemandAsExplicitUnassigned() throws Exception {
        ObjectNode root = draft(); root.put("mode", "COLD"); root.putArray("technicians");
        ((ObjectNode) root.path("revisions")).putArray("schedule"); root.putArray("unassigned").add(0).add(1);
        for (var visit : root.path("visits")) ((ObjectNode) visit).putNull("originalTechnician").putNull("originalPlannedStart");
        DayPlan plan = DailyDataset.parse(DailyDataset.seal(Required.value(root.toString()))).toDayPlan();
        assertEquals(List.of("v0", "v1"), plan.getUnassignedVisitIds());
        plan.getFacts().requireSearchRoads();
    }
}
