package dev.waterflex.scheduler.admin;

import dev.waterflex.scheduler.Required;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The portal's client list is external input: anything but an array of {id, name} objects is refused. */
class LocalTokenBootstrapTest {
    @Test void aWellFormedListIsRead() {
        assertEquals(List.of(new LocalTokenBootstrap.Client("default", "Default client"), new LocalTokenBootstrap.Client("acme-2", "Acme")),
                LocalTokenBootstrap.clients("[{\"id\":\"default\",\"name\":\"Default client\"},{\"name\":\"Acme\",\"id\":\"acme-2\"}]"));
        assertEquals(List.of(), LocalTokenBootstrap.clients("[]"));
    }

    @Test void anythingElseIsRefused() {
        for (String bad : List.of("{}", "null", "not json", "[1]", "[{\"id\":\"a\"}]", "[{\"id\":\"a\",\"name\":\"A\",\"extra\":1}]",
                "[{\"id\":\"a\",\"name\":null}]", "[{\"id\":7,\"name\":\"A\"}]", "[{\"id\":\"../x\",\"name\":\"A\"}]", "[{\"id\":\"a\",\"name\":\" \"}]"))
            assertThrows(IllegalArgumentException.class, () -> LocalTokenBootstrap.clients(Required.value(bad)), bad);
    }
}
