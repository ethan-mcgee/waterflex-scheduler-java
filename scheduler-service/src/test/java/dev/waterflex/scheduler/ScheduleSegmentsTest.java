package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleSegmentsTest {
    private static final String VALID = """
            {"format":1,"scheduleVersion":3,"routingIdentity":"roads","segments":[
            {"departure":"2026-10-26T14:00:00Z","returnedAt":"2026-10-26T16:00:00Z","appointmentIds":["one"]}]}
            """;
    @Test void requiresExplicitValidProvenanceAndNonoverlappingUniqueSegments() {
        var saved = ScheduleSegments.decode(VALID);
        assertEquals(3, saved.version()); assertEquals("roads", saved.routingIdentity());
        assertEquals(java.util.List.of("one"), saved.segments().getFirst().visitIds());
        for (String raw : java.util.List.of("null", "{}", "[]", "false", "{", VALID.replace("\"roads\"", "null"),
                VALID.replace("\"one\"", "null"), VALID.replace("\"one\"", "\"one\",\"one\""),
                VALID.replace("16:00:00", "13:00:00"), VALID.replace("\"scheduleVersion\":3", "\"scheduleVersion\":null")))
            assertThrows(RuntimeException.class, () -> ScheduleSegments.decode(Required.value(raw)));
        assertTrue(ScheduleSegments.decode("{\"format\":1,\"scheduleVersion\":0,\"routingIdentity\":null,\"segments\":[]}").segments().isEmpty());
    }
}
