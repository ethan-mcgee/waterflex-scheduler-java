package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.Required;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
class OvernightControllerTest {
    @Test void successfulReceiptRequiresItsPreviewRunIdentity() {
        var at=Required.value(java.time.Instant.parse("2030-01-01T08:00:00Z"));
        var day=Required.value(java.time.LocalDate.parse("2030-01-01"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,() -> new OvernightOptimization.Receipt("attempt","metro",day,
                OvernightOptimization.State.SUCCEEDED,"key",null,null,null,at,at));
    }
    @Test void missingAndMalformedQueryFactsNeverReachReceiptStorage() throws Exception {
        var overnight=mock(OvernightOptimization.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OvernightController(overnight)).build();
        mvc.perform(Required.value(get("/v1/optimize/overnight/attempts").param("metro_id","metro").param("date","invalid"))).andExpect(status().isBadRequest());
        mvc.perform(Required.value(get("/v1/optimize/overnight/attempts").param("metro_id"," ").param("date","2030-01-01"))).andExpect(status().isBadRequest());
        mvc.perform(Required.value(get("/v1/optimize/overnight/attempts").param("metro_id","metro"))).andExpect(status().isBadRequest());
        verifyNoInteractions(overnight);
    }
}
