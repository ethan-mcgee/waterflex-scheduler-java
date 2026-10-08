package dev.waterflex.routing;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoutingAuthenticationTest {
    private static final String TOKEN = "routing-fixture-token-at-least-32-characters";

    @Test
    void computationRequiresTheServiceTokenButHealthStaysOpen() throws Exception {
        MatrixController controller = new MatrixController("missing.osm", "missing-graph", "test", 10, 10);
        try {
            MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(controller).addFilters(new RoutingAuthentication(TOKEN))
                    .setMessageConverters(new JsonConfiguration().strictJsonConverter()).build());
            String body = "{\"pairs\":[{\"id\":\"a\",\"origin\":{\"lat\":43.735,\"lng\":7.42},\"destination\":{\"lat\":43.735,\"lng\":7.422}}],\"expectedRoutingIdentity\":\"x\"}";
            for (String path : new String[] {"/internal/legs", "/internal/matrix", "/internal/route"}) {
                http.perform(Required.value(post(Required.value(path)).contentType("application/json").content(body))).andExpect(status().isUnauthorized());
                http.perform(Required.value(post(Required.value(path)).header("Authorization", "Bearer wrong-" + TOKEN).contentType("application/json").content(body)))
                        .andExpect(status().isUnauthorized());
                http.perform(Required.value(post(Required.value(path)).header("Authorization", TOKEN).contentType("application/json").content(body)))
                        .andExpect(status().isUnauthorized());
            }
            // With the token the request reaches the controller, which reports the missing graph instead of 401.
            int authorized = http.perform(Required.value(post("/internal/legs").header("Authorization", "Bearer " + TOKEN)
                    .contentType("application/json").content(body))).andReturn().getResponse().getStatus();
            assertNotEquals(401, authorized);
            http.perform(Required.value(get("/health"))).andExpect(status().isOk());
        } finally { controller.close(); }
    }

    @Test
    void shortBlankOrWhitespaceTokensRefuseToStart() {
        for (String token : new String[] {"", "short", "routing token with spaces that is long enough ok"})
            assertThrows(IllegalArgumentException.class, () -> new RoutingAuthentication(Required.value(token)));
    }
}
