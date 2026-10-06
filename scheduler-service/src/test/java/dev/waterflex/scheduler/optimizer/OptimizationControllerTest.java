package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.JsonConfiguration;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchDeadline;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OptimizationControllerTest {
    private static org.springframework.test.web.servlet.RequestBuilder previewRequest() {
        return Required.value(post("/v1/optimize/day/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"metro_id\":\"m\",\"date\":\"2030-01-01\"}"));
    }
    @Test void strictApplicationConverterMustNotEncodePreparedJsonAsABase64String() throws Exception {
        var service = mock(OptimizationService.class);
        byte[] prepared = Required.value("{\"status\":\"PREVIEW\",\"created_at\":\"2030-01-01T00:00:00Z\",\"applied_at\":null,\"reason\":\"café\"}".getBytes(StandardCharsets.UTF_8));
        when(service.previewJson(new OptimizationService.Request("m","2030-01-01"))).thenReturn(prepared);
        var http = MockMvcBuilders.standaloneSetup(new OptimizationController(service))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        var response = http.perform(previewRequest())
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
        assertArrayEquals(prepared,response.getContentAsByteArray());
        var body = Required.value(OptimizationService.jsonMapper().readTree(response.getContentAsByteArray()));
        assertTrue(body.isObject()); assertTrue(body.path("applied_at").isNull());
        assertEquals("café",body.path("reason").textValue());
    }
    @Test void expiredOperationKeepsServiceUnavailableStatusBeforeWritingAnyPreparedBody() throws Exception {
        var service = mock(OptimizationService.class);
        when(service.previewJson(new OptimizationService.Request("m","2030-01-01"))).thenThrow(new SearchDeadline.Expired());
        var http = MockMvcBuilders.standaloneSetup(new OptimizationController(service))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build();
        http.perform(previewRequest())
                .andExpect(status().isServiceUnavailable());
    }
}
