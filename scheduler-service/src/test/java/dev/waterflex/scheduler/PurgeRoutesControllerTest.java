package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurgeRoutesControllerTest {
    @Test void rejectsActualNullsAndMalformedCollectionsBeforeScheduling() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        for (String raw : java.util.List.of("{}", "{\"jobIds\":null,\"days\":[]}", "{\"jobIds\":[null],\"days\":[]}",
                "{\"jobIds\":[\"job\"],\"days\":null}", "{\"jobIds\":[\"job\"],\"days\":[null]}",
                "{\"jobIds\":[\"job\",\"job\"],\"days\":[]}",
                "{\"jobIds\":[\"job\"],\"days\":[{\"technicianId\":\"tech\",\"serviceDate\":null}]}"))
            assertThrows(Exception.class, () -> json.readValue(raw, PurgeRoutesController.Request.class));
        var request = Required.value(json.readValue("{\"jobIds\":[\"job\"],\"days\":[{\"technicianId\":\"tech\",\"serviceDate\":\"2026-10-26\"}]}", PurgeRoutesController.Request.class));
        assertEquals(1, request.days().size());
        assertDoesNotThrow(() -> new PurgeRoutesController.Request(Required.value(java.util.List.of("job")), Required.value(java.util.List.of())));
    }
}
