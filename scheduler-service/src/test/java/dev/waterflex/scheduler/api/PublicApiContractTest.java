package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.junit.jupiter.api.Assertions.*;

/** Keeps the Java public types and docs/api/openapi-v1.yaml in agreement. */
class PublicApiContractTest {
    private static final Map<String, Object> SPEC = load();
    private static final Map<String, Object> SCHEMAS = map(map(SPEC.get("components")).get("schemas"));
    private static final Map<String, Object> EXAMPLES = map(map(SPEC.get("components")).get("examples"));

    /** Every object schema in the spec, with the Java record that implements it. */
    private static final Map<String, Class<? extends Record>> TYPES = types();

    private static Map<String, Class<? extends Record>> types() {
        Map<String, Class<? extends Record>> types = new LinkedHashMap<>();
        types.put("Address", PublicTypes.Address.class);
        types.put("Location", PublicTypes.Location.class);
        types.put("Window", PublicTypes.Window.class);
        types.put("Rates", PublicTypes.Rates.class);
        types.put("Policy", PublicTypes.Policy.class);
        types.put("Technician", PublicTypes.Technician.class);
        types.put("TechnicianDay", PublicTypes.TechnicianDay.class);
        types.put("Appointment", PublicTypes.Appointment.class);
        types.put("Snapshot", PublicTypes.Snapshot.class);
        types.put("TechnicianDayVersion", PublicTypes.TechnicianDayVersion.class);
        types.put("DailyProposalRequest", PublicRequests.DailyProposalRequest.class);
        types.put("RepairProposalRequest", PublicRequests.RepairProposalRequest.class);
        types.put("BookingOffersRequest", PublicRequests.BookingOffersRequest.class);
        types.put("RequestOnly", PublicRequests.RequestOnly.class);
        types.put("CommitRequest", PublicRequests.CommitRequest.class);
        types.put("WhoAmI", PublicResponses.WhoAmI.class);
        types.put("PlannedStop", PublicResponses.PlannedStop.class);
        types.put("PlannedRoute", PublicResponses.PlannedRoute.class);
        types.put("DailyProposal", PublicResponses.DailyProposal.class);
        types.put("SkippedTechnicianDay", PublicResponses.SkippedTechnicianDay.class);
        types.put("Offer", PublicResponses.Offer.class);
        types.put("OfferSet", PublicResponses.OfferSet.class);
        types.put("Hold", PublicResponses.Hold.class);
        types.put("Released", PublicResponses.Released.class);
        types.put("Assignment", PublicResponses.Assignment.class);
        types.put("CommitReceipt", PublicResponses.CommitReceipt.class);
        types.put("Problem", PublicResponses.Problem.class);
        types.put("StaleProblem", PublicResponses.StaleProblem.class);
        return types;
    }

    private static Map<String, Object> load() {
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(Path.of("../docs/api/openapi-v1.yaml")));
            return map(parsed);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read the OpenAPI spec", failure); }
    }

    private static Map<String, Object> map(@Nullable Object value) {
        assertInstanceOf(Map.class, value);
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) Required.value(value)).forEach((key, item) -> copy.put(String.valueOf(key), Required.value(item)));
        return copy;
    }

    private static void assertMatches(String where, Map<String, Object> schema, Class<? extends Record> type) {
        Set<String> properties = new TreeSet<>(map(schema.get("properties")).keySet());
        Set<String> required = new TreeSet<>();
        Object listed = schema.get("required");
        if (listed instanceof List<?> names) for (Object name : names) required.add(String.valueOf(name));
        Set<String> components = new TreeSet<>(), nonNull = new TreeSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            components.add(component.getName());
            if (!component.getAnnotatedType().isAnnotationPresent(Nullable.class)) nonNull.add(component.getName());
        }
        assertEquals(properties, components, where + " fields");
        assertEquals(required, nonNull, where + " required fields");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), where + " must reject unknown fields");
    }

    @Test void everyObjectSchemaHasAJavaRecordWithTheSameFieldsAndRequiredness() {
        Set<String> objectSchemas = new HashSet<>();
        SCHEMAS.forEach((name, schema) -> { if ("object".equals(map(schema).get("type"))) objectSchemas.add(name); });
        assertEquals(objectSchemas, TYPES.keySet(), "object schemas without a Java record, or records without a schema");
        TYPES.forEach((name, type) -> assertMatches(Required.value(name), map(SCHEMAS.get(name)), Required.value(type)));
        assertMatches("RepairProposalRequest.absence", map(map(map(SCHEMAS.get("RepairProposalRequest")).get("properties")).get("absence")), PublicRequests.Absence.class);
        assertMatches("BookingOffersRequest.job", map(map(map(SCHEMAS.get("BookingOffersRequest")).get("properties")).get("job")), PublicRequests.Job.class);
        assertMatches("BookingOffersRequest.horizon", map(map(map(SCHEMAS.get("BookingOffersRequest")).get("properties")).get("horizon")), PublicRequests.Horizon.class);
    }

    static String example(String name) {
        return CalculationJson.write(Required.value(map(EXAMPLES.get(name)).get("value")));
    }

    @Test void everySpecExampleParsesStrictlyAndRoundTrips() {
        int checked = 0;
        for (String key : EXAMPLES.keySet()) {
            String name = Required.value(key);
            Class<? extends Record> type = Required.value(TYPES.get(name), "Java type for example " + name);
            Record parsed = Required.value(PublicRequests.read(example(name), type));
            assertEquals(parsed, PublicRequests.read(CalculationJson.write(parsed), type), name + " round trip");
            checked++;
        }
        assertTrue(checked >= 8);
    }

    /** Edits one parsed copy of the valid example. */
    private interface Change { void apply(ObjectNode request); }

    private static void rejected(String why, Change change) {
        ObjectNode request = object(CalculationJson.tree(example("DailyProposalRequest")));
        change.apply(request);
        String json = CalculationJson.write(request);
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(json, PublicRequests.DailyProposalRequest.class), why);
    }

    private static ObjectNode object(@Nullable JsonNode node) {
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) Required.value(node);
    }

    private static ObjectNode child(ObjectNode parent, String field) { return object(parent.get(field)); }
    private static ObjectNode snapshot(ObjectNode request) { return child(request, "snapshot"); }
    private static ObjectNode element(ObjectNode request, String list, int index) {
        JsonNode items = snapshot(request).get(list);
        assertInstanceOf(ArrayNode.class, items);
        return object(((ArrayNode) Required.value(items)).get(index));
    }
    private static ObjectNode day(ObjectNode request, int index) { return element(request, "technicianDays", index); }
    private static ObjectNode appointment(ObjectNode request, int index) { return element(request, "appointments", index); }

    @Test void strictReadingRejectsWhatTheSpecForbids() {
        PublicRequests.read(example("DailyProposalRequest"), PublicRequests.DailyProposalRequest.class);
        rejected("tenant in request", request -> { request.put("tenantId", "acme-water"); });
        rejected("unknown field", request -> { request.put("surprise", 1); });
        rejected("numeric money", request -> { child(snapshot(request), "rates").put("regularHourly", 20); });
        rejected("noncanonical decimal", request -> { child(snapshot(request), "rates").put("travelBufferPct", "0.20"); });
        rejected("missing policy", request -> { snapshot(request).remove("policy"); });
        rejected("missing fairness budget", request -> { child(snapshot(request), "policy").remove("fairnessBudget"); });
        rejected("numeric fairness budget", request -> { child(snapshot(request), "policy").put("fairnessBudget", 0.02); });
        rejected("fairness budget above one", request -> { child(snapshot(request), "policy").put("fairnessBudget", "1.5"); });
        rejected("null required integer", request -> { day(request, 0).putNull("maxPaidMinutes"); });
        rejected("string integer", request -> { day(request, 0).put("maxPaidMinutes", "540"); });
        rejected("missing absences", request -> { day(request, 0).remove("absences"); });
        rejected("missing lastModified", request -> { day(request, 0).remove("lastModified"); });
        rejected("missing plannedStart", request -> { appointment(request, 0).remove("plannedStart"); });
        rejected("uppercase request ID", request -> { request.put("requestId", "3B1F6C1E-2A7D-4F0E-8C52-9A1D7E6B4C21"); });
        rejected("day outside serviceDate", request -> { request.put("serviceDate", "2026-10-13"); });
        rejected("appointment without technician-day", request -> { appointment(request, 1).put("technicianId", "tech-9"); });
        rejected("duplicate sequence", request -> { appointment(request, 1).put("sequence", 0); });
        rejected("duplicate appointment", request -> { appointment(request, 1).put("id", "appt-7"); });
        rejected("lat without lng", request -> { child(appointment(request, 0), "location").remove("lng"); });
        rejected("empty location", request -> { appointment(request, 0).putObject("location"); });
        rejected("unknown time zone", request -> { snapshot(request).put("timeZone", "Mars/Base"); });
        rejected("day for unknown technician", request -> { day(request, 1).put("technicianId", "tech-9"); });
        rejected("window ends before it starts", request -> { child(appointment(request, 0), "window").put("end", "2026-10-12T07:00:00-05:00"); });
        String valid = example("DailyProposalRequest");
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(Required.value(valid.replaceFirst("\\{","{\"requestId\":\"3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21\",")), PublicRequests.DailyProposalRequest.class), "duplicate key");
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(valid + "{}", PublicRequests.DailyProposalRequest.class), "trailing tokens");
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read("null", PublicRequests.DailyProposalRequest.class), "null document");
    }

    @Test void responseTypesRefuseWhatTheContractNeverSends() {
        String proposal = example("DailyProposal");
        PublicRequests.read(proposal, PublicResponses.DailyProposal.class);
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(Required.value(proposal.replace("\"overtimeMinutes\":0", "\"overtimeMinutes\":1")), PublicResponses.DailyProposal.class));
        var offers = PublicRequests.read(example("OfferSet"), PublicResponses.OfferSet.class);
        List<PublicResponses.Offer> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(Required.value(offers.offers().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new PublicResponses.OfferSet(offers.offerSetId(), offers.expiresAt(), five, true, offers.skippedTechnicianDays()));
        List<PublicResponses.Offer> twice = Required.value(List.of(Required.value(offers.offers().getFirst()), Required.value(offers.offers().getFirst())));
        assertThrows(IllegalArgumentException.class, () -> new PublicResponses.OfferSet(offers.offerSetId(), offers.expiresAt(), twice, true, offers.skippedTechnicianDays()));
        assertThrows(IllegalArgumentException.class, () -> PublicRequests.read("{\"requestId\":\"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\",\"technicianDays\":[]}", PublicRequests.CommitRequest.class));
    }
}
