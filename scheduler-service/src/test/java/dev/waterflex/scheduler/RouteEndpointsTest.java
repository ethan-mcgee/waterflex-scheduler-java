package dev.waterflex.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteEndpointsTest {
    @Test
    void missingDealershipRejectsRouteWithoutInventingCoordinates() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getDouble(1)).thenReturn(41.2);
        when(row.getDouble(2)).thenReturn(-95.9);
        when(row.getString(3)).thenReturn(null);
        when(row.getString(4)).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> RouteEndpoints.from(row, 1));
    }

    @Test
    void depotEndpointRequiresRealDepotCoordinates() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getDouble(1)).thenReturn(41.2);
        when(row.getDouble(2)).thenReturn(-95.9);
        when(row.getString(3)).thenReturn("DEPOT");
        when(row.getString(4)).thenReturn("HOME");
        when(row.getDouble(5)).thenReturn(0.0);
        when(row.wasNull()).thenReturn(false, false, true, true);
        assertThrows(ResponseStatusException.class, () -> RouteEndpoints.from(row, 1));
    }
}
