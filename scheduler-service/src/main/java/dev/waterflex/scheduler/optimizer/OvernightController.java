package dev.waterflex.scheduler.optimizer;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public final class OvernightController {
    private final OvernightOptimization overnight;
    public OvernightController(OvernightOptimization overnight) { this.overnight=overnight; }
    @GetMapping("/v1/optimize/overnight/attempts")
    public List<OvernightOptimization.Receipt> attempts(@RequestParam("metro_id") String metro,@RequestParam String date) {
        CalculationJson.text(metro);
        LocalDate day;
        try { day=Required.value(LocalDate.parse(date)); }
        catch (java.time.DateTimeException failure) { throw new IllegalArgumentException("Invalid overnight date",failure); }
        return overnight.attempts(metro,day);
    }
    @GetMapping("/v1/optimize/overnight/metrics")
    public Map<String,Long> metrics() { return overnight.metrics(); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException failure) { return Required.value(Map.of("error","INVALID_OVERNIGHT_QUERY")); }
}
