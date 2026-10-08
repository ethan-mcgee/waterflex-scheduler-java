package dev.waterflex.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MetroRoutingTest {
    @Test void parsesOneUrlPerMetro() {
        assertEquals(Map.of("omaha", "http://routing-omaha:8001", "lincoln", "https://routing-lincoln.internal/base"),
                MetroRouting.parse(" omaha = http://routing-omaha:8001/ ,lincoln=https://routing-lincoln.internal/base"));
        assertEquals(Map.of(), MetroRouting.parse(""));
        assertEquals(Map.of(), MetroRouting.parse("   "));
    }

    @Test void rejectsMalformedConfiguration() {
        for (String bad : List.of("omaha", "omaha=http://a:1,", "=http://a:1", "om aha=http://a:1", "omaha=http://a:1,omaha=http://b:1",
                "omaha=ftp://a:1", "omaha=routing:8001", "omaha=http://a:1?x=1", "omaha=http://a:1#x", "omaha=http://a:1=2", "omaha=http:// bad"))
            assertThrows(IllegalArgumentException.class, () -> MetroRouting.parse(Required.value(bad)), bad);
    }

    @Test void eachMetroGetsItsOwnClientAndUnknownMetrosHaveNone() {
        List<String> built = new ArrayList<>();
        var routing = new MetroRouting(MetroRouting.parse("omaha=http://a:1,lincoln=http://b:1"), url -> { built.add(Required.value(url)); return mock(RoadClient.class); });
        assertEquals(List.of("http://a:1", "http://b:1"), built);
        assertNotSame(routing.client("omaha"), routing.client("lincoln"));
        assertSame(routing.client("omaha"), routing.client("omaha"));
        assertThrows(MetroRouting.UnknownMetro.class, () -> routing.client("denver"));
        assertThrows(MetroRouting.UnknownMetro.class, () -> new MetroRouting(Required.value(Map.<String, String>of()), _ -> mock(RoadClient.class)).client("omaha"));
    }
}
