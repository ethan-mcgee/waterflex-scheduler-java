package dev.waterflex.scheduler;

import dev.waterflex.scheduler.api.RouteEvaluations;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Every /api/v1 refusal is a Problem with its retry rule, or the bare 401 the contract promises (spec 1.11.0). */
class PublicApiErrorsTest {
    private static final String BEARER = "Bearer " + TenantAuthenticationTest.VECTOR;

    @RestController static class Outside {
        @GetMapping("/outside-fixture") public String fail() { throw new IllegalStateException("outside the public API"); }
    }

    private static MockMvc http(RouteEvaluations routes) {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(TenantAuthenticationTest.VECTOR)).thenReturn("acme");
        var controller = new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), mock(dev.waterflex.scheduler.api.DailyCommits.class),
                mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class),
                mock(dev.waterflex.scheduler.api.BookingConfirm.class), routes, mock(dev.waterflex.scheduler.api.RouteGeometries.class),
                new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests()));
        return Required.value(MockMvcBuilders.standaloneSetup(controller, new Outside()).setControllerAdvice(new PublicApiErrors())
                .addFilters(new ApiVersionHeader(), new TenantAuthentication(tokens)).build());
    }

    @Test void anUnauthenticatedCallGetsABare401WithTheContractVersion() throws Exception {
        MockMvc http = http(mock(RouteEvaluations.class));
        for (String path : new String[] {"/api/v1/whoami", "/api/v1/no-such-operation"})
            http.perform(Required.value(get(Required.value(path)).header("Authorization", Required.value("Bearer wfs_" + "B".repeat(43)))))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string(ApiVersionHeader.HEADER, ApiVersionHeader.SPEC_VERSION))
                    .andExpect(content().string(""));
    }

    @Test void anUnknownPathIsANotFoundProblem() throws Exception {
        http(mock(RouteEvaluations.class)).perform(Required.value(get("/api/v1/no-such-operation").header("Authorization", BEARER)))
                .andExpect(status().isNotFound()).andExpect(header().string(ApiVersionHeader.HEADER, ApiVersionHeader.SPEC_VERSION))
                .andExpect(jsonPath("$.error").value("NOT_FOUND")).andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("No operation at GET /api/v1/no-such-operation"));
    }

    @Test void aWrongMethodIsAnInvalidRequestProblemNamingTheAllowedOne() throws Exception {
        http(mock(RouteEvaluations.class)).perform(Required.value(post("/api/v1/whoami").header("Authorization", BEARER)))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST")).andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").value("POST is not allowed on /api/v1/whoami; use GET"));
    }

    @Test void aFailureThatEscapesAHandlerIsAnInternalErrorProblem() throws Exception {
        RouteEvaluations routes = mock(RouteEvaluations.class);
        when(routes.evaluate("acme", "{}")).thenThrow(new IllegalStateException("broken invariant"));
        http(routes).perform(Required.value(post("/api/v1/routes/evaluate").header("Authorization", BEARER).content("{}")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(header().doesNotExist("Retry-After"));
    }

    @Test void aLostDatabaseIsARetryableServiceUnavailableProblem() throws Exception {
        RouteEvaluations routes = mock(RouteEvaluations.class);
        when(routes.evaluate("acme", "{}")).thenThrow(new DataAccessResourceFailureException("connection refused"));
        http(routes).perform(Required.value(post("/api/v1/routes/evaluate").header("Authorization", BEARER).content("{}")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE")).andExpect(jsonPath("$.retryable").value(true));
    }

    @Test void routingThatFailsOutsideAHandlersOwnRefusalIsARoutingProblem() throws Exception {
        RouteEvaluations routes = mock(RouteEvaluations.class);
        when(routes.evaluate("acme", "{}")).thenThrow(new RoadClient.RoadUnavailable("fixture routing unavailable"));
        http(routes).perform(Required.value(post("/api/v1/routes/evaluate").header("Authorization", BEARER).content("{}")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("ROUTING_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test void pathsOutsideThePublicApiKeepTheirDefaultHandling() throws Exception {
        MockMvc http = http(mock(RouteEvaluations.class));
        assertThrows(ServletException.class, () -> http.perform(Required.value(get("/outside-fixture"))));
        http.perform(Required.value(get("/no-such-page"))).andExpect(status().isNotFound()).andExpect(content().string(""))
                .andExpect(header().doesNotExist(ApiVersionHeader.HEADER));
    }
}
