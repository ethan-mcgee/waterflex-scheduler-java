package dev.waterflex.scheduler;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantAuthenticationTest {
    // The portal's web/lib/tenantTokens.test.ts accepts the same token format.
    static final String VECTOR = "wfs_AAAAAAAAAAAAAAAAAAAA-_b9b9b9b9b9b9b9b9b9b9z";

    @Test void generatedTokensAreWellFormedAndDistinct() {
        String first = TenantTokens.generate(), second = TenantTokens.generate();
        assertTrue(TenantTokens.wellFormed(first));
        assertNotEquals(first, second);
    }

    @Test void tokenDigestIsStable() {
        assertEquals("e272c126d6d2fa192e81378fa257a85bd6b44830492c8c2141cc6928235f35db", TenantTokens.sha256(VECTOR));
        assertTrue(TenantTokens.wellFormed(VECTOR));
        for (String token : new String[] {"", "wfs_", "wfs_short", "xyz_" + "A".repeat(43), "wfs_" + "A".repeat(42) + "=", "wfs_" + "A".repeat(44)})
            assertFalse(TenantTokens.wellFormed(Required.value(token)));
    }

    @Test void publicApiResolvesTenantOnlyFromAValidToken() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests())))
                .addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(get("/api/v1/whoami").header("Authorization", "Bearer " + VECTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value("acme"));
        for (String header : new String[] {"Bearer wfs_" + "B".repeat(43), VECTOR, "Basic " + VECTOR, "Bearer "})
            http.perform(Required.value(get("/api/v1/whoami").header("Authorization", Required.value(header))))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        http.perform(Required.value(get("/api/v1/whoami"))).andExpect(status().isUnauthorized());
        // A tenant parameter or header in the request never selects the tenant.
        http.perform(Required.value(get("/api/v1/whoami").param("tenantId", "acme").header("X-Tenant", "acme"))).andExpect(status().isUnauthorized());
    }

    @Test void dailyProposalRepliesPassThroughWithTheTokenTenantAndRawBody() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var proposals = mock(dev.waterflex.scheduler.api.DailyProposals.class);
        String body = "{\"requestId\":\"x\",\"tenantId\":\"other\"}";
        when(proposals.create("acme", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(429, "{\"error\":\"BUSY\",\"message\":\"m\"}", 1));
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(proposals, mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests()))).addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().is(429)).andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.error").value("BUSY"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals").content(body)))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(1)).create("acme", body);
    }

    @Test void preferRespondAsyncQueuesTheProposalAndTheStatusIsReadByRequestId() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var proposals = mock(dev.waterflex.scheduler.api.DailyProposals.class);
        String body = "{\"requestId\":\"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\"}";
        String status = "{\"requestId\":\"0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\",\"operation\":\"DAILY_PROPOSAL\",\"state\":\"QUEUED\"}";
        when(proposals.createAsync("acme", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(202, status, 3, "/api/v1/requests/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"));
        when(proposals.repairAsync("acme", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(202, status, 3, "/api/v1/requests/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"));
        when(proposals.create("acme", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(201, "{}", null));
        when(proposals.describe("acme", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(200, status, 3));
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(proposals, mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests()))).addFilters(new TenantAuthentication(tokens)).build());
        for (String prefer : List.of("respond-async", "return=minimal, respond-async", "RESPOND-ASYNC; wait=10"))
            http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals")
                            .header("Authorization", "Bearer " + VECTOR).header("Prefer", prefer).contentType("application/json").content(body)))
                    .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/requests/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
                    .andExpect(header().string("Retry-After", "3")).andExpect(header().string("Preference-Applied", "respond-async"))
                    .andExpect(jsonPath("$.state").value("QUEUED"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repairs/proposals")
                        .header("Authorization", "Bearer " + VECTOR).header("Prefer", "respond-async").contentType("application/json").content(body)))
                .andExpect(status().isAccepted());
        // Another preference alone, or none, keeps the synchronous call.
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals")
                        .header("Authorization", "Bearer " + VECTOR).header("Prefer", "return=minimal").contentType("application/json").content(body)))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Preference-Applied"));
        http.perform(Required.value(get("/api/v1/requests/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d").header("Authorization", "Bearer " + VECTOR)))
                .andExpect(status().isOk()).andExpect(header().string("Retry-After", "3")).andExpect(jsonPath("$.state").value("QUEUED"));
        http.perform(Required.value(get("/api/v1/requests/0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(3)).createAsync("acme", body);
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(1)).create("acme", body);
    }

    @Test void jsonBodiesReachTheServiceThroughTheApplicationsStrictJsonConverter() throws Exception {
        // The deployed application registers its strict JSON converter ahead of the plain-text one. A JSON object must
        // still arrive as the exact raw text, not be refused while Spring tries to bind it to a String.
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var offers = mock(dev.waterflex.scheduler.api.BookingOffers.class);
        var holds = mock(dev.waterflex.scheduler.api.BookingHolds.class);
        String body = "{\"requestId\":\"7d0e1c55-4c1e-4f7a-9d1a-0b6a3c1b2e10\",\"job\":{\"id\":\"j\"},\"snapshot\":{\"technicians\":[]}}";
        when(offers.create("acme", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(201, "{\"offerSetId\":\"set\"}", null));
        when(holds.select("acme", "offer-1", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(200, "{\"holdId\":\"h\"}", null));
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), mock(dev.waterflex.scheduler.api.DailyCommits.class), offers, holds, mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests())))
                .setMessageConverters(new JsonConfiguration().strictJsonConverter())
                .addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/booking/offers")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().is(201)).andExpect(jsonPath("$.offerSetId").value("set"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/booking/offers/offer-1/select")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holdId").value("h"));
        org.mockito.Mockito.verify(offers).create("acme", body);
        org.mockito.Mockito.verify(holds).select("acme", "offer-1", body);
    }

    @Test void commitRepliesPassThroughWithTheTokenTenantPathProposalAndRawBody() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var commits = mock(dev.waterflex.scheduler.api.DailyCommits.class);
        String body = "{\"requestId\":\"x\"}";
        when(commits.commit("acme", "prop-1", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(409, "{\"error\":\"NOT_COMMITTABLE\",\"message\":\"m\"}", null));
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), commits, mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests())))
                .addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals/prop-1/commit")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().is(409)).andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.error").value("NOT_COMMITTABLE"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals/prop-1/commit").content(body)))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(commits, org.mockito.Mockito.times(1)).commit("acme", "prop-1", body);
    }

    @Test void anUnreachableTokenStoreAnswers503WithAProblemAndNeverReachesTheHandler() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        var proposals = mock(dev.waterflex.scheduler.api.DailyProposals.class);
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(proposals, mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(new dev.waterflex.scheduler.api.RunningRequests())))
                .addFilters(new TenantAuthentication(tokens)).build());
        // What the pool throws when no connection comes within its timeout (the 2026-10-19 database stall probe), a
        // statement the database cancelled, and a connection dropped mid-query.
        for (RuntimeException unreachable : List.<RuntimeException>of(
                new org.springframework.jdbc.CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new org.springframework.dao.QueryTimeoutException("statement timeout"),
                new org.springframework.dao.RecoverableDataAccessException("connection reset"))) {
            reset(tokens);
            when(tokens.tenantFor(VECTOR)).thenThrow(unreachable);
            http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals")
                            .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content("{}")))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(content().contentType("application/json"))
                    .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE")).andExpect(jsonPath("$.message").isNotEmpty());
        }
        verifyNoInteractions(proposals);
        // A wrong query is a bug, not an outage: it still fails as a 500 rather than asking the host to retry.
        reset(tokens);
        when(tokens.tenantFor(VECTOR)).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("token", "SELECT", new java.sql.SQLException("syntax")));
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/whoami");
        request.addHeader("Authorization", "Bearer " + VECTOR);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertThrows(org.springframework.jdbc.BadSqlGrammarException.class, () -> new TenantAuthentication(tokens).doFilter(request, response, new org.springframework.mock.web.MockFilterChain()));
        assertNotEquals(503, response.getStatus());
    }

    @Test void internalRoutesAreOutsideThePublicFilterAndHandlersFailClosedWithoutIt() {
        var filter = new TenantAuthentication(mock(TenantTokens.class));
        var internal = new org.springframework.mock.web.MockHttpServletRequest("GET", "/health");
        internal.setRequestURI("/health");
        assertDoesNotThrow(() -> filter.doFilter(internal, new org.springframework.mock.web.MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain()));
        assertThrows(IllegalStateException.class, () -> TenantAuthentication.tenant(new org.springframework.mock.web.MockHttpServletRequest()));
    }

    @Test void cancellingARunningRequestAnswersAcceptedWithoutABodyAndOnlyForItsTenant() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var requests = new dev.waterflex.scheduler.api.RunningRequests();
        String id = "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21";
        var running = requests.start("acme", id);
        var other = requests.start("other", "00000000-0000-4000-8000-000000000000");
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class), mock(dev.waterflex.scheduler.api.BookingConfirm.class), mock(dev.waterflex.scheduler.api.RouteEvaluations.class), mock(dev.waterflex.scheduler.api.RouteGeometries.class), new dev.waterflex.scheduler.api.RequestCancellation(requests))).addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/requests/" + id)))
                .andExpect(status().isUnauthorized());
        assertFalse(running.cancelled(), "an unauthenticated cancel changes nothing");
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/requests/00000000-0000-4000-8000-000000000000")
                        .header("Authorization", "Bearer " + VECTOR)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        assertFalse(other.cancelled(), "another tenant's request is never found");
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/requests/not-a-uuid")
                        .header("Authorization", "Bearer " + VECTOR)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/requests/" + id)
                        .header("Authorization", "Bearer " + VECTOR)))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        assertTrue(running.cancelled());
    }
}
