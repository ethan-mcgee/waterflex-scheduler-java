package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantAuthenticationTest {
    // Shared with web/lib/tenantTokens.test.ts so both sides hash identically.
    static final String VECTOR = "wfs_AAAAAAAAAAAAAAAAAAAA-_b9b9b9b9b9b9b9b9b9b9z";

    @Test void tokenDigestMatchesThePortalIssuer() {
        assertEquals("e272c126d6d2fa192e81378fa257a85bd6b44830492c8c2141cc6928235f35db", TenantTokens.sha256(VECTOR));
        assertTrue(TenantTokens.wellFormed(VECTOR));
        for (String token : new String[] {"", "wfs_", "wfs_short", "xyz_" + "A".repeat(43), "wfs_" + "A".repeat(42) + "=", "wfs_" + "A".repeat(44)})
            assertFalse(TenantTokens.wellFormed(Required.value(token)));
    }

    @Test void publicApiResolvesTenantOnlyFromAValidToken() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class)))
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
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(proposals, mock(dev.waterflex.scheduler.api.DailyCommits.class), mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class))).addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().is(429)).andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.error").value("BUSY"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals").content(body)))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(proposals, org.mockito.Mockito.times(1)).create("acme", body);
    }

    @Test void commitRepliesPassThroughWithTheTokenTenantPathProposalAndRawBody() throws Exception {
        TenantTokens tokens = mock(TenantTokens.class);
        when(tokens.tenantFor(VECTOR)).thenReturn("acme");
        var commits = mock(dev.waterflex.scheduler.api.DailyCommits.class);
        String body = "{\"requestId\":\"x\"}";
        when(commits.commit("acme", "prop-1", body)).thenReturn(new dev.waterflex.scheduler.api.DailyProposals.Reply(409, "{\"error\":\"NOT_COMMITTABLE\",\"message\":\"m\"}", null));
        MockMvc http = Required.value(MockMvcBuilders.standaloneSetup(new PublicApiController(mock(dev.waterflex.scheduler.api.DailyProposals.class), commits, mock(dev.waterflex.scheduler.api.BookingOffers.class), mock(dev.waterflex.scheduler.api.BookingHolds.class)))
                .addFilters(new TenantAuthentication(tokens)).build());
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals/prop-1/commit")
                        .header("Authorization", "Bearer " + VECTOR).contentType("application/json").content(body)))
                .andExpect(status().is(409)).andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.error").value("NOT_COMMITTABLE"));
        http.perform(Required.value(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/daily/proposals/prop-1/commit").content(body)))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(commits, org.mockito.Mockito.times(1)).commit("acme", "prop-1", body);
    }

    @Test void internalRoutesAreOutsideThePublicFilterAndHandlersFailClosedWithoutIt() {
        var filter = new TenantAuthentication(mock(TenantTokens.class));
        var internal = new org.springframework.mock.web.MockHttpServletRequest("GET", "/v1/offers");
        internal.setRequestURI("/v1/offers");
        assertDoesNotThrow(() -> filter.doFilter(internal, new org.springframework.mock.web.MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain()));
        assertThrows(IllegalStateException.class, () -> TenantAuthentication.tenant(new org.springframework.mock.web.MockHttpServletRequest()));
    }
}
