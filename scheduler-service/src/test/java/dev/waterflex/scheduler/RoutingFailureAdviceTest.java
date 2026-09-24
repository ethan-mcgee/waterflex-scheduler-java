package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoutingFailureAdviceTest {
    @RestController static class Endpoint {
        @GetMapping("/operator-fixture") public String operation() { throw new RoadClient.RoadUnavailable("fixture routing unavailable"); }
    }
    @Test void operatorFailureIsExplicitAndRetryable() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Endpoint()).setControllerAdvice(new RoutingFailureAdvice()).build();
        mvc.perform(get("/operator-fixture")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.classification").value("ROUTING_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
