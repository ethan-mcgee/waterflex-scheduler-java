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
    /** System.nanoTime, replaceable in tests to age a solve past {@link #STUCK}. */
    private final java.util.function.LongSupplier clock;
    /**
     * A solve still registered this long after it arrived outlived the longest allowance any caller can send (the
     * longest daily search plus its reserve) by a margin: its worker is wedged, and the replica reports itself down.
     */
    static final Duration STUCK = Required.value(DailyOperation.Allowance.MAXIMUM.plus(Duration.ofSeconds(60)));
    private final class Running {
        final long arrived = clock.getAsLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile @Nullable SearchDeadline deadline;
        volatile @Nullable DailyOperation daily;
        void cancel() {
            cancelled.set(true); SearchDeadline clock = deadline; if (clock != null) clock.cancel();
            DailyOperation operation = daily; if (operation != null) operation.cancel();
        }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SolveController(@Value("${solver.auth-token}") String token, SearchAdmission admission, EmbeddedCalculation calculation,
                           @Value("${solver.transient-limit:18}") int limit) {
        this(token, admission, calculation, limit, System::nanoTime);
    }
    SolveController(String token, SearchAdmission admission, EmbeddedCalculation calculation, int limit, java.util.function.LongSupplier clock) {
        this.clock = clock;
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
    /**
     * Readiness of this pool replica, unauthenticated like before. The scheduler reads only the status code; the body
     * reports its load for operators. 503 when any solve is stuck, so the scheduler's readiness and the load balancer
     * stop sending work to a replica whose worker never finished.
     */
    @GetMapping("/health")
    public org.springframework.http.ResponseEntity<java.util.Map<String,Object>> health() {
        long now = clock.getAsLong();
        long stuck = running.values().stream().filter(state -> now - state.arrived > STUCK.toNanos()).count();
        var admitted = admission.state();
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("status", stuck == 0 ? "UP" : "DOWN");
        body.put("capacity", admission.capacity());
        body.put("active", admitted.active());
        body.put("queued", admitted.queuedBookings() + admitted.queuedBackground());
        body.put("transient", running.size());
        body.put("stuck", stuck);
        return Required.value(org.springframework.http.ResponseEntity.status(stuck == 0 ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Required.value(java.util.Map.copyOf(body))));
    }

    /**
     * Tells every solve still running here to stop when the replica shuts down. The web server's graceful drain comes
     * first, so this only reaches solves that outlived it; their callers would time out anyway.
     */
    @jakarta.annotation.PreDestroy
    public void stopRunning() { for (Running state : running.values()) Required.value(state).cancel(); }
    @PostMapping("/v1/solve/{operation}")
    public void solve(@PathVariable String operation,HttpServletRequest http,HttpServletResponse response) throws IOException {
        long entered = System.nanoTime(); authenticate(http);
        if (!operation.equals("daily") && !operation.equals("booking")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (http.getContentLengthLong() > CalculationJson.MAX_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        byte[] bytes = http.getInputStream().readNBytes(CalculationJson.MAX_BYTES+1);
        if (bytes.length > CalculationJson.MAX_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
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
                // The caller's allocation bounds the solver phases; the rest of its remaining time covers validation.
                long search = Math.min(remaining,CalculationJson.read(request.payload(),CalculationProtocol.DailyInput.class).allocation().searchMs());
                var allowance = new DailyOperation.Allowance(Required.value(Duration.ofMillis(remaining)),Required.value(Duration.ofMillis(search)));
                // The scheduler already admitted this request; here it only waits its turn on the solver's slots.
                result = DailyOperation.executeQueued(admission,allowance,() -> {
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
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public java.util.Map<String,String> incomplete(BookingSnapshot.Incomplete failure) { return Required.value(java.util.Map.of("error","INCOMPLETE_CALCULATION_FACTS")); }
}
