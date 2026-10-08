package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.optimizer.DailyOperation;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Admission and transient cancellation ownership last until actual worker completion. */
@RestController
public final class SolveController {
    private final byte[] token;
    private final SearchAdmission admission;
    private final EmbeddedCalculation calculation;
    private final int transientLimit;
    private final ConcurrentHashMap<String,Running> running = new ConcurrentHashMap<>();
    private static final class Running {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile @Nullable SearchDeadline deadline;
        volatile @Nullable DailyOperation daily;
        void cancel() {
            cancelled.set(true); SearchDeadline clock = deadline; if (clock != null) clock.cancel();
            DailyOperation operation = daily; if (operation != null) operation.cancel();
        }
    }
    public SolveController(@Value("${solver.auth-token}") String token, SearchAdmission admission, EmbeddedCalculation calculation,
                           @Value("${solver.transient-limit:18}") int limit) {
        if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Solver authentication token must contain at least 32 non-whitespace characters");
        if (limit < 1 || limit > 18) throw new IllegalArgumentException("Invalid transient solve limit");
        this.token = Required.value(token.getBytes(StandardCharsets.UTF_8)); this.admission = admission; this.calculation = calculation; transientLimit = limit;
    }
    private void authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(token,authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Solver authentication required");
    }
    @GetMapping("/health")
    public java.util.Map<String,String> health() { return Required.value(java.util.Map.of("status","UP")); }
    @PostMapping("/v1/solve/{operation}")
    public void solve(@PathVariable String operation,HttpServletRequest http,HttpServletResponse response) throws IOException {
        long entered = System.nanoTime(); authenticate(http);
        if (!operation.equals("daily") && !operation.equals("booking")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (http.getContentLengthLong() > CalculationJson.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] bytes = http.getInputStream().readNBytes(CalculationJson.MAX_BYTES+1);
        if (bytes.length > CalculationJson.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var request = CalculationJson.read(new String(bytes,StandardCharsets.UTF_8),CalculationProtocol.Request.class);
        if (!request.operation().equals(operation.toUpperCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Endpoint operation mismatch");
        requireRoutingKey(http,request.requestId());
        long remaining = request.remainingMillis() - (System.nanoTime()-entered)/1_000_000;
        if (remaining <= 0) throw new SearchDeadline.Expired();
        Running state = new Running();
        synchronized(running) {
            if (running.containsKey(request.requestId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request already executing");
            if (running.size() >= transientLimit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Solver transient capacity exhausted");
            running.put(request.requestId(),state);
        }
        boolean daily = request.operation().equals("DAILY");
        try {
            byte[] result;
            if (daily) {
                // The worker owns removal, including when its HTTP caller expires before solver cleanup.
                result = DailyOperation.executePrepared(admission,Required.value(Duration.ofMillis(remaining)),() -> {
                    state.daily = DailyOperation.current(); state.deadline = SearchDeadline.current();
                    if (state.cancelled.get()) state.cancel();
                    return new DailyOperation.Preparation<>(() -> encode(request,entered,response),true,_ -> { });
                }, _ -> running.remove(request.requestId(),state));
            } else {
                var deadline = new SearchDeadline(Required.value(Duration.ofMillis(remaining))); state.deadline = deadline;
                result = deadline.within(() -> {
                    if (state.cancelled.get()) state.cancel();
                    try (var lease = admission.acquire(SearchAdmission.Kind.BOOKING,deadline)) { return encode(request,entered,response); }
                    finally { running.remove(request.requestId(),state); }
                });
            }
            SearchDeadline clock = state.deadline; if (clock != null) clock.requireTime();
            response.setContentType("application/json"); response.setContentLength(result.length); response.getOutputStream().write(result);
        } finally { if (!daily) running.remove(request.requestId(),state); }
    }
    private byte[] encode(CalculationProtocol.Request request,long entered,HttpServletResponse response) {
        var calculated = calculation.calculate(request);
        // Encoding is part of the original operation, before its capacity and clock can close.
        var result = new CalculationProtocol.Response(calculated.schemaVersion(),calculated.requestId(),calculated.contentHash(),
                calculated.policyVersion(),calculated.costVersion(),calculated.scoreVersion(),calculated.engine(),calculated.provenance(),(System.nanoTime()-entered)/1_000_000,calculated.payload());
        byte[] bytes = Required.value(CalculationJson.write(result).getBytes(StandardCharsets.UTF_8)); SearchDeadline.checkpoint(); return bytes;
    }
    /** The load balancer hashes this header, so a solve and its cancellation must carry the same, correct value. */
    private static void requireRoutingKey(HttpServletRequest http,String requestId) {
        if (!requestId.equals(http.getHeader(HttpCalculation.REQUEST_HEADER))) throw new IllegalArgumentException("Solve request routing header mismatch");
    }
    @DeleteMapping("/v1/solves/{requestId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@PathVariable String requestId,HttpServletRequest request) {
        authenticate(request); java.util.UUID.fromString(requestId); requireRoutingKey(request,requestId);
        Running state = running.get(requestId); if (state == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No active solve"); state.cancel();
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalid(IllegalArgumentException failure) { return Required.value(java.util.Map.of("error","INVALID_CALCULATION_INPUT")); }
    @ExceptionHandler({SearchDeadline.Expired.class, SearchAdmission.Busy.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public java.util.Map<String,String> unavailable(RuntimeException failure) { return Required.value(java.util.Map.of("error","CALCULATION_UNAVAILABLE")); }
    @ExceptionHandler(BookingSnapshot.Incomplete.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public java.util.Map<String,String> incomplete(BookingSnapshot.Incomplete failure) { return Required.value(java.util.Map.of("error","INCOMPLETE_CALCULATION_FACTS")); }
}
