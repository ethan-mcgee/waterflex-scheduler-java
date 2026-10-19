package dev.waterflex.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicApiBodyLimitTest {
    private static MockHttpServletRequest withBody(int bytes) {
        var request = new MockHttpServletRequest("POST", "/api/v1/booking/offers");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test void aBodyAtTheCeilingIsReadInFull() throws Exception {
        assertEquals(CalculationJson.MAX_BYTES, PublicApiController.body(withBody(CalculationJson.MAX_BYTES)).length());
    }

    @Test void aLargerBodyIsRefusedWhetherOrNotItDeclaresItsLength() {
        assertThrows(PublicApiController.TooLarge.class, () -> PublicApiController.body(withBody(CalculationJson.MAX_BYTES + 1)));
        // A chunked upload declares no length (-1): the read itself stops one byte past the ceiling.
        var chunked = new MockHttpServletRequest("POST", "/api/v1/booking/offers") {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContent(new byte[CalculationJson.MAX_BYTES + 1]);
        assertThrows(PublicApiController.TooLarge.class, () -> PublicApiController.body(chunked));
    }

    @Test void theRefusalIs413WithTheApisProblemBody() throws Exception {
        var response = new MockHttpServletResponse();
        PublicApiController.writeTooLarge(new PublicApiController.TooLarge(), response);
        assertEquals(413, response.getStatus());
        assertEquals("application/json", response.getContentType());
        var problem = CalculationJson.tree(response.getContentAsString(Required.value(StandardCharsets.UTF_8)));
        assertEquals("INVALID_REQUEST", problem.path("error").asText());
        assertTrue(problem.path("message").asText().contains(String.valueOf(CalculationJson.MAX_BYTES)));
    }
}
