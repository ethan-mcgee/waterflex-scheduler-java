package dev.waterflex.scheduler.optimizer;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Strict, replayable daily input. No database, routing callbacks or application authority. */
public final class DailyDataset {
    public static final String COST_MODEL = "legacy-double-v1";
    public static final String SCORE_MODEL = "hard-medium-soft-decimal-v1";
    public enum Encoding { DENSE, SPARSE }
    private static final ObjectMapper JSON = mapper();
    private static ObjectMapper mapper() {
        JsonFactory factory = new JsonFactory(); factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ObjectMapper mapper = new ObjectMapper(factory); mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS); return mapper;
    }
    private final ObjectNode canonical;
    private final PlanFacts facts;
    private final List<List<Integer>> assignments;
    private final String hash;

    private DailyDataset(ObjectNode canonical, PlanFacts facts, List<List<Integer>> assignments, String hash) {
        this.canonical = canonical; this.facts = facts;
        this.assignments = Required.value(assignments.stream().map(value -> Required.value(List.copyOf(value))).toList()); this.hash = hash;
    }
    public static DailyDataset parse(String json) { return read(json, true); }
    /** Explicit draft sealing validates every fact; parse never accepts a null or missing hash. */
    public static String seal(String draft) { return read(draft, false).json(Encoding.SPARSE); }
    public String contentHash() { return hash; }
    public PlanFacts facts() { return facts; }

    private static DailyDataset read(String input, boolean requireHash) {
        try {
            ObjectNode root = object(Required.value(JSON.readTree(input), "daily dataset"), "dataset",
                    "schemaVersion", "operation", "mode", "snapshotId", "contentHash", "versions", "routing", "revisions", "search", "rates", "locations", "services", "technicians", "visits", "unassigned", "roads");
            check(integer(root, "schemaVersion", 1, 1) == 1, "unsupported dataset schema");
            equal(root, "operation", "DAILY");
            String mode = string(root, "mode"); check(Set.of("ASSIGNED", "REPAIR", "COLD", "PARTIAL").contains(mode), "unsupported mode");
            string(root, "snapshotId");
            ObjectNode versions = object(required(root, "versions"), "versions", "policy", "cost", "score");
            equal(versions, "policy", SchedulingPolicy.VERSION); equal(versions, "cost", COST_MODEL); equal(versions, "score", SCORE_MODEL);
            ObjectNode routing = object(required(root, "routing"), "routing", "provider", "profile", "identity");
            for (String field : List.of("provider", "profile", "identity")) string(routing, Required.value(field));
            ObjectNode search = object(required(root, "search"), "search", "variant", "seed", "remainingMillis");
            SolverExperiment.Variant.valueOf(string(search, "variant")); integer(search, "seed", Long.MIN_VALUE, Long.MAX_VALUE);
            integer(search, "remainingMillis", 1, 20000);
            ObjectNode rates = object(required(root, "rates"), "rates", "regularHourly", "overtimeHourly", "mileagePerMile", "travelBufferPct", "travelBufferMinutes");
            double regular = decimal(rates, "regularHourly"), overtime = decimal(rates, "overtimeHourly"), mileage = decimal(rates, "mileagePerMile"), pct = decimal(rates, "travelBufferPct");
            long minutes = integer(rates, "travelBufferMinutes", 0, Integer.MAX_VALUE);
            ArrayNode locations = array(root, "locations"), services = array(root, "services"), technicians = array(root, "technicians"), visits = array(root, "visits");
            Set<String> locationIds = new HashSet<>(), serviceIds = new HashSet<>(), technicianIds = new HashSet<>(), visitIds = new HashSet<>();
            for (JsonNode node : locations) {
                ObjectNode location = object(Required.value(node), "location", "id", "latitude", "longitude");
                unique(locationIds, string(location, "id"), "location"); coordinate(location, "latitude", 90); coordinate(location, "longitude", 180);
            }
            List<String> serviceNames = new ArrayList<>();
            for (JsonNode node : services) {
                ObjectNode service = object(Required.value(node), "service", "id"); String id = string(service, "id");
                PlanFacts.identity(id, "service.id"); unique(serviceIds, id, "service"); serviceNames.add(id);
            }
            List<PlanFacts.Technician> techFacts = new ArrayList<>(); List<List<Integer>> assigned = new ArrayList<>();
            List<Integer> departures = new ArrayList<>(), returns = new ArrayList<>();
            for (JsonNode node : technicians) {
                ObjectNode tech = object(Required.value(node), "technician", "id", "departureLocation", "returnLocation", "shiftStart", "shiftEnd", "maxDailyMinutes", "maxOvertimeMinutes", "qualifications", "absences", "assigned", "pinnedPrefix");
                String id = string(tech, "id"); unique(technicianIds, id, "technician");
                departures.add(index(tech, "departureLocation", locations.size())); returns.add(index(tech, "returnLocation", locations.size()));
                Set<String> qualifications = new HashSet<>(); List<Integer> qualificationIndices = indices(array(tech, "qualifications"), services.size(), "qualification");
                qualificationIndices.forEach(value -> qualifications.add(Required.value(serviceNames.get(value))));
                qualificationIndices.sort((a, b) -> Integer.compare(Required.value(a), Required.value(b))); ArrayNode sorted = tech.putArray("qualifications"); qualificationIndices.forEach(sorted::add);
                List<TechRoute.Unavailable> absences = new ArrayList<>();
                for (JsonNode absence : array(tech, "absences")) {
                    ObjectNode value = object(Required.value(absence), "absence", "start", "end"); absences.add(new TechRoute.Unavailable(instant(value, "start"), instant(value, "end")));
                }
                techFacts.add(new PlanFacts.Technician(id, instant(tech, "shiftStart"), instant(tech, "shiftEnd"),
                        (int) integer(tech, "maxDailyMinutes", 0, Integer.MAX_VALUE), (int) integer(tech, "maxOvertimeMinutes", 0, Integer.MAX_VALUE), qualifications, absences));
                assigned.add(indices(array(tech, "assigned"), visits.size(), "assigned visit"));
                check(integer(tech, "pinnedPrefix", 0, Integer.MAX_VALUE) == 0, "pinned prefixes are unsupported until the pinning model is enabled");
            }
            List<PlanFacts.Visit> visitFacts = new ArrayList<>(); List<Integer> visitLocations = new ArrayList<>();
            Set<String> demandServices = new HashSet<>();
            for (JsonNode node : visits) {
                ObjectNode visit = object(Required.value(node), "visit", "id", "service", "location", "windowStart", "windowEnd", "durationMinutes", "originalTechnician", "originalPlannedStart");
                String id = string(visit, "id"); unique(visitIds, id, "visit");
                String service = Required.value(serviceNames.get(index(visit, "service", services.size())));
                int original = index(visit, "originalTechnician", techFacts.size());
                visitLocations.add(index(visit, "location", locations.size())); demandServices.add(service);
                visitFacts.add(new PlanFacts.Visit(id, service, instant(visit, "windowStart"), instant(visit, "windowEnd"),
                        (int) integer(visit, "durationMinutes", 1, Integer.MAX_VALUE), techFacts.get(original).id(), instant(visit, "originalPlannedStart")));
            }
            Set<Integer> coverage = new HashSet<>();
            for (List<Integer> route : assigned) for (Integer visit : route) check(coverage.add(visit), "visit assigned more than once: " + visit);
            List<Integer> unassigned = indices(array(root, "unassigned"), visits.size(), "unassigned visit");
            for (Integer visit : unassigned) check(coverage.add(visit), "visit is assigned and unassigned: " + visit);
            check(coverage.size() == visits.size(), "every demand identity must occur exactly once");
            check(unassigned.isEmpty(), "partial/unassigned daily demand is unsupported until construction is enabled");
            check(visits.isEmpty() || Set.of("ASSIGNED", "REPAIR").contains(mode), "cold/partial planning is unsupported until construction is enabled");
            ObjectNode revisions = object(required(root, "revisions"), "revisions", "schedule", "configuration", "reservation");
            string(revisions, "configuration"); string(revisions, "reservation"); ArrayNode schedule = array(revisions, "schedule");
            check(schedule.size() == techFacts.size(), "schedule revision count must equal technician count");
            for (JsonNode value : schedule) check(value.isTextual() && !Required.value(value.textValue()).isBlank(), "schedule revision must be a nonblank token");

            // All identities and indices are checked before constructing road lookup maps or planning entities.
            Map<Long, JsonNode> roadStates = roads(required(root, "roads"), locations.size());
            Set<Long> requiredPairs = new HashSet<>();
            for (int t = 0; t < techFacts.size(); t++) {
                // Current selectors can tentatively place any visit on any technician, including infeasible moves.
                Set<Integer> eligible = new HashSet<>();
                for (int v = 0; v < visitFacts.size(); v++) eligible.add(v);
                for (int v : eligible) {
                    requiredPairs.add(pair(departures.get(t), visitLocations.get(v)));
                    requiredPairs.add(pair(visitLocations.get(v), returns.get(t)));
                    for (int next : eligible) if (v != next) requiredPairs.add(pair(visitLocations.get(v), visitLocations.get(next)));
                }
            }
            for (long key : requiredPairs) check(roadStates.containsKey(key), "missing required directed road " + (key >>> 32) + "->" + (key & 0xffffffffL));
            Map<String, DayPlan.RoadLeg> matrix = new HashMap<>(); Set<String> unreachable = new HashSet<>();
            // The compatibility bridge preserves stable demand/technician IDs and each depot's role.
            for (int t = 0; t < techFacts.size(); t++) for (int v = 0; v < visitFacts.size(); v++) {
                transfer(roadStates, departures.get(t), visitLocations.get(v), techFacts.get(t).id() + ">" + visitFacts.get(v).id(), matrix, unreachable);
                transfer(roadStates, visitLocations.get(v), returns.get(t), visitFacts.get(v).id() + ">" + techFacts.get(t).id() + ":return", matrix, unreachable);
            }
            for (int v = 0; v < visitFacts.size(); v++) for (int next = 0; next < visitFacts.size(); next++) if (v != next)
                transfer(roadStates, visitLocations.get(v), visitLocations.get(next), visitFacts.get(v).id() + ">" + visitFacts.get(next).id(), matrix, unreachable);
            PlanFacts facts = new PlanFacts(techFacts, visitFacts, matrix, unreachable, regular, overtime, mileage, pct, minutes, demandServices);
            ObjectNode canonicalRoads = root.putObject("roads"); canonicalRoads.put("encoding", "SPARSE"); canonicalRoads.put("size", locations.size());
            ArrayNode entries = canonicalRoads.putArray("entries");
            roadStates.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                ObjectNode value = entries.addObject(); value.put("from", (int) (entry.getKey() >>> 32)); value.put("to", (int) (long) entry.getKey());
                value.setAll((ObjectNode) entry.getValue());
            });
            JsonNode claimed = required(root, "contentHash"); root.remove("contentHash");
            String hash = digest(serialize(sorted(root)));
            if (requireHash || !claimed.isNull()) check(claimed.isTextual() && hash.equals(claimed.textValue()), "dataset contentHash mismatch");
            return new DailyDataset((ObjectNode) sorted(root), facts, assigned, hash);
        } catch (IllegalArgumentException failure) { throw failure; }
          catch (Exception failure) { throw new IllegalArgumentException("Invalid daily dataset: " + failure.getMessage(), failure); }
    }

    public DayPlan toDayPlan() {
        List<PlanVisit> values = new ArrayList<>(); List<TechRoute> routes = new ArrayList<>();
        for (PlanFacts.Visit visit : facts.demand()) values.add(new PlanVisit(visit.id(), visit.service(), visit.start(), visit.end(), visit.minutes(), visit.originalTechnician(), visit.originalStart()));
        for (int t = 0; t < facts.technicians().size(); t++) {
            PlanFacts.Technician tech = facts.technicians().get(t);
            TechRoute route = new TechRoute(tech.id(), tech.start(), tech.end(), tech.maxDaily(), tech.maxOvertime(), tech.qualifications());
            route.setUnavailable(tech.absences());
            for (int index : assignments.get(t)) { PlanVisit visit = values.get(index); route.getVisits().add(visit); visit.setTechnician(route); }
            route.freeze(); routes.add(route);
        }
        DayPlan plan = new DayPlan(routes, values, new RouteScoringFacts(facts, null)); facts.validateEntities(plan, true); return plan;
    }
    public String json(Encoding encoding) {
        ObjectNode output = canonical.deepCopy(); output.put("contentHash", hash);
        if (encoding == Encoding.DENSE) {
            ObjectNode sparse = (ObjectNode) output.path("roads"); int size = sparse.path("size").intValue();
            Map<Long, JsonNode> states = roads(sparse, size); ObjectNode dense = output.putObject("roads"); dense.put("encoding", "DENSE"); dense.put("size", size);
            ArrayNode cells = dense.putArray("cells");
            for (int from = 0; from < size; from++) { ArrayNode row = cells.addArray(); for (int to = 0; to < size; to++) {
                JsonNode state = states.get(pair(from, to));
                if (state == null) row.addObject().put("state", "NOT_REQUIRED").putNull("seconds").putNull("meters"); else row.add(state);
            } }
        }
        return serialize(sorted(output));
    }
    private static Map<Long, JsonNode> roads(JsonNode input, int size) {
        check(size <= 10000, "location count exceeds dataset limit 10000");
        String encoding = string(input, "encoding");
        ObjectNode road = object(input, "roads", "encoding", "size", encoding.equals("DENSE") ? "cells" : "entries");
        check(integer(road, "size", 0, 10000) == size, "road dimensions must equal location count"); Map<Long, JsonNode> states = new HashMap<>();
        if (encoding.equals("DENSE")) {
            ArrayNode rows = array(road, "cells"); check(rows.size() == size, "dense road row count mismatch");
            for (int from = 0; from < size; from++) {
                JsonNode row = rows.get(from); check(row.isArray() && row.size() == size, "dense road column count mismatch");
                for (int to = 0; to < size; to++) state(Required.value(row.get(to)), from, to, states, true);
            }
        } else {
            check(encoding.equals("SPARSE"), "unsupported road encoding");
            for (JsonNode node : array(road, "entries")) {
                ObjectNode entry = object(Required.value(node), "road entry", "from", "to", "state", "seconds", "meters");
                int from = index(entry, "from", size), to = index(entry, "to", size);
                ObjectNode value = entry.deepCopy(); value.remove(List.of("from", "to")); state(value, from, to, states, false);
            }
        }
        return states;
    }
    private static void state(JsonNode input, int from, int to, Map<Long, JsonNode> states, boolean dense) {
        ObjectNode value = object(input, "road state", "state", "seconds", "meters"); String state = string(value, "state");
        if (state.equals("REACHABLE")) {
            long seconds = integer(value, "seconds", 0, Integer.MAX_VALUE), meters = integer(value, "meters", 0, Integer.MAX_VALUE);
            check(from != to || seconds == 0 && meters == 0, "self road must be zero seconds/meters");
        } else {
            check(state.equals("UNREACHABLE") || dense && state.equals("NOT_REQUIRED"), "unsupported road state");
            check(required(value, "seconds").isNull() && required(value, "meters").isNull(), "unreachable/not-required road cannot carry numbers");
            check(from != to || state.equals("NOT_REQUIRED"), "self road cannot be unreachable");
            if (state.equals("NOT_REQUIRED")) return;
        }
        check(states.putIfAbsent(pair(from, to), value.deepCopy()) == null, "duplicate directed road pair");
    }
    private static void transfer(Map<Long, JsonNode> states, int from, int to, String key, Map<String, DayPlan.RoadLeg> matrix, Set<String> unreachable) {
        JsonNode state = states.get(pair(from, to)); if (state == null) return;
        if (state.path("state").textValue().equals("UNREACHABLE")) unreachable.add(key);
        else matrix.put(key, new DayPlan.RoadLeg(state.path("seconds").longValue(), state.path("meters").longValue()));
    }
    private static long pair(int from, int to) { return ((long) from << 32) | (to & 0xffffffffL); }
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field); check(value != null, "missing required field: " + field); return Required.value(value);
    }
    private static ObjectNode object(JsonNode node, String name, String... fields) {
        check(node.isObject(), name + " must be an object"); Set<String> expected = new HashSet<>(List.of(fields));
        node.fieldNames().forEachRemaining(field -> check(expected.remove(field), "unknown field in " + name + ": " + field));
        check(expected.isEmpty(), "missing fields in " + name + ": " + expected); return (ObjectNode) node;
    }
    private static ArrayNode array(JsonNode node, String field) { JsonNode value = required(node, field); check(value.isArray(), field + " must be an array"); return (ArrayNode) value; }
    private static String string(JsonNode node, String field) { JsonNode value = required(node, field); check(value.isTextual() && !Required.value(value.textValue()).isBlank(), field + " must be a nonblank string"); return Required.value(value.textValue()); }
    private static void equal(JsonNode node, String field, String expected) { check(string(node, field).equals(expected), "unsupported " + field + " version/value"); }
    private static long integer(JsonNode node, String field, long min, long max) {
        JsonNode value = required(node, field); check(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= min && value.longValue() <= max, field + " must be an integer in range"); return value.longValue();
    }
    private static int index(JsonNode node, String field, int count) { return (int) integer(node, field, 0, count - 1L); }
    private static List<Integer> indices(ArrayNode array, int count, String field) {
        List<Integer> result = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
        for (JsonNode value : array) { check(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 && value.intValue() < count, field + " index out of range"); int index = value.intValue(); check(seen.add(index), "duplicate " + field + " index"); result.add(index); } return result;
    }
    private static Instant instant(ObjectNode node, String field) { Instant value = Required.value(Instant.parse(string(node, field))); node.put(field, value.toString()); return value; }
    private static double decimal(ObjectNode node, String field) {
        String text = string(node, field); check(text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"), field + " must be a nonnegative decimal string");
        BigDecimal value = new BigDecimal(text); double number = value.doubleValue(); PlanFacts.number(number, field);
        check(value.signum() == 0 || number > 0, field + " underflows legacy numeric model"); node.put(field, value.stripTrailingZeros().toPlainString()); return number;
    }
    private static void coordinate(JsonNode node, String field, int bound) { JsonNode value = required(node, field); check(value.isNumber() && Double.isFinite(value.doubleValue()) && Math.abs(value.doubleValue()) <= bound, field + " coordinate out of range"); }
    private static void unique(Set<String> ids, String id, String field) { check(ids.add(id), "duplicate " + field + " identity: " + id); }
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) { ObjectNode result = JSON.createObjectNode(); List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add); Collections.sort(keys); for (String key : keys) result.set(key, sorted(Required.value(node.get(key)))); return Required.value(result); }
        if (node.isArray()) { ArrayNode result = JSON.createArrayNode(); for (JsonNode value : node) result.add(sorted(Required.value(value))); return Required.value(result); } JsonNode copy = node.deepCopy(); return Required.value(copy);
    }
    private static String serialize(JsonNode node) { try { return Required.value(JSON.writeValueAsString(node)); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static String digest(String value) { try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static void check(boolean valid, String message) { PlanFacts.check(valid, message); }
}
