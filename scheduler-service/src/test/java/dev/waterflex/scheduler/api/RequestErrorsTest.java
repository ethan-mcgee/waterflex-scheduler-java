package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.api.PublicRequests.RouteEvaluationRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A 400's message says what is wrong and where, in the contract's terms, and never names a Java type (spec 1.11.0). */
class RequestErrorsTest {
    private static String refusal(String body) {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> PublicRequests.read(body, RouteEvaluationRequest.class));
        String message = DailyProposals.invalidRequest(Required.value(invalid));
        for (String internal : new String[] {"dev.waterflex", "java.", "`", "[Source", "StreamReadFeature", "Cannot construct", "Cannot deserialize"})
            assertFalse(message.contains(internal), () -> "\"" + message + "\" exposes " + internal);
        return message;
    }

    private static ObjectNode example() {
        return (ObjectNode) CalculationJson.tree(PublicApiContractTest.example("RouteEvaluationRequest"));
    }

    @Test void theSpecExampleIsValid() {
        assertNotNull(PublicRequests.read(CalculationJson.write(example()), RouteEvaluationRequest.class));
    }

    @Test void anUnknownFieldIsNamedWithTheObjectThatHoldsIt() {
        ObjectNode root = example();
        root.put("tenantId", "acme");
        assertEquals("Unknown field \"tenantId\"", refusal(CalculationJson.write(root)));
        ObjectNode nested = example();
        ((ObjectNode) nested.get("snapshot")).put("colour", "blue");
        assertEquals("Unknown field \"colour\" at $.snapshot", refusal(CalculationJson.write(nested)));
    }

    @Test void aMissingValueIsTheRecordsOwnWords() {
        ObjectNode root = example();
        root.remove("serviceDate");
        assertEquals("Missing serviceDate", refusal(CalculationJson.write(root)));
    }

    @Test void aValueOfTheWrongKindSaysWhatWasExpectedAndWhere() {
        ObjectNode root = example();
        root.put("serviceDate", "the twelfth");
        assertEquals("Expected a date (YYYY-MM-DD) at $.serviceDate", refusal(CalculationJson.write(root)));
    }

    @Test void malformedJsonGivesTheParsersWordsAndThePosition() {
        String message = refusal("{");
        assertTrue(message.startsWith("Malformed JSON: Unexpected end-of-input"), message);
        assertTrue(message.contains(" at line 1, column "), message);
    }

    @Test void aDuplicateKeyNamesTheKey() {
        String message = refusal("{\"serviceDate\":\"2026-10-12\",\"serviceDate\":\"2026-10-13\"}");
        assertTrue(message.startsWith("Duplicate field 'serviceDate'"), message);
    }

    @Test void contentAfterTheDocumentIsRefused() {
        assertEquals("Unexpected content after the JSON value", refusal(CalculationJson.write(example()) + " {}"));
    }

    @Test void aRefusalWithoutAParserCauseKeepsItsOwnMessage() {
        assertEquals("requestId must be a lowercase canonical UUID",
                RequestErrors.describe(new IllegalArgumentException("requestId must be a lowercase canonical UUID\nsecond line")));
    }
}
