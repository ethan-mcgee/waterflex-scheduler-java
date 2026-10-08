package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Caller-owned receipts. Overnight work creates previews only and never applies schedules. */
@Component
public final class OvernightOptimization {
    public enum State { ATTEMPTED, SUCCEEDED, SKIPPED, FAILED, CANCELLED }
    public enum FailureCode { DEADLINE_OR_CANCELLATION, ROUTING_UNAVAILABLE, SOLVER_TRANSPORT_UNAVAILABLE, INPUT_CONFLICT, CALCULATION_FAILED }
    public record Failure(int schemaVersion, FailureCode code, String exceptionClass, String stage) {
        public Failure {
            if (schemaVersion != 1) throw new IllegalArgumentException("Unknown overnight failure version");
            Required.value(code); CalculationJson.text(exceptionClass);
            if (!stage.equals("PREVIEW")) throw new IllegalArgumentException("Unknown overnight stage");
        }
    }
    public record Receipt(String id,String metroId,LocalDate serviceDate,State state,String previewKey,
            @Nullable String resultRunId,@Nullable String resultReason,@Nullable Failure failure,Instant startedAt,@Nullable Instant finishedAt) {
        public Receipt {
            CalculationJson.text(id); CalculationJson.text(metroId); CalculationJson.text(previewKey);
            Required.value(serviceDate); Required.value(state); Required.value(startedAt);
            if ((state == State.ATTEMPTED) != (finishedAt == null) || (state == State.FAILED || state == State.CANCELLED) != (failure != null)
                    || finishedAt != null && finishedAt.isBefore(startedAt)) throw new IllegalArgumentException("Invalid overnight receipt");
            if (state == State.SUCCEEDED) CalculationJson.text(Required.value(resultRunId,"successful overnight preview"));
        }
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Function<OptimizationService.Request,Map<String,Object>> preview;
    private final Clock clock;
    private final AtomicLong receiptWriteFailures = new AtomicLong();
    private final AtomicLong claimedElsewhere = new AtomicLong();
    private static final ZoneId NIGHT_ZONE = Required.value(ZoneId.of("America/Chicago"));
    private static final org.slf4j.Logger LOG = Required.value(org.slf4j.LoggerFactory.getLogger(OvernightOptimization.class));
    @org.springframework.beans.factory.annotation.Autowired
    public OvernightOptimization(JdbcTemplate jdbc,OptimizationService service,org.springframework.transaction.PlatformTransactionManager manager) {
        this(jdbc,manager,request -> service.preview(Required.value(request)),Required.value(Clock.systemUTC()));
    }
    OvernightOptimization(JdbcTemplate jdbc,org.springframework.transaction.PlatformTransactionManager manager,
            Function<OptimizationService.Request,Map<String,Object>> preview,Clock clock) {
        this.jdbc=jdbc; this.preview=preview; this.clock=clock;
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(4);
    }
    @Scheduled(cron="${scheduler.optimizer.cron:0 0 2 * * *}",zone="America/Chicago")
    public void run() {
        // A metro shared by several clients is optimized by each client's own host through the public API.
        List<String> metros=jdbc.query(MetroTenancy.SINGLE_CLIENT_METROS,(rs,_) -> DatabaseFacts.string(rs,1));
        run(metros,OptimizationService.overnightDates(Required.value(clock.instant())));
    }
    void run(List<String> metros,List<LocalDate> days) {
        LocalDate night=night();
        for (String metro : metros) for (LocalDate day : days) runDay(Required.value(metro),Required.value(day),night);
    }
    void runDay(String metro,LocalDate day) { runDay(metro,day,night()); }
    private LocalDate night() { return Required.value(LocalDate.ofInstant(Required.value(clock.instant()),NIGHT_ZONE)); }
    /** Every replica fires the cron; the receipt insert is the claim, so one replica runs each metro-day per night. */
    void runDay(String metro,LocalDate day,LocalDate night) {
        String id=Required.value(UUID.randomUUID().toString()), key="overnight:"+id;
        try {
            Integer inserted=transactions.execute(_ -> jdbc.update("INSERT INTO overnight_optimization_attempt (id,\"metroId\",\"serviceDate\",state,\"previewKey\",\"nightOf\") VALUES (?,?,?,'ATTEMPTED',?,?) ON CONFLICT (\"metroId\",\"serviceDate\",\"nightOf\") DO NOTHING",id,metro,day,key,night));
            if (Required.value(inserted,"overnight claim result") == 0) {
                claimedElsewhere.incrementAndGet(); LOG.info("Overnight metro={} serviceDate={} night={} already claimed by another replica",metro,day,night); return;
            }
        } catch (RuntimeException failure) {
            receiptWriteFailures.incrementAndGet(); LOG.error("Overnight receipt creation failed attempt={} metro={} serviceDate={}",id,metro,day,failure); return;
        }
        State state; String runId=null; String reason=null; Failure context=null;
        try {
            if (ScheduleCutoff.frozen(day,Required.value(clock.instant()))) { state=State.SKIPPED; reason="FROZEN_AFTER_6_AM"; }
            else {
                var result=Required.value(preview.apply(new OptimizationService.Request(metro,Required.value(day.toString()),key)));
                Object status=result.get("status");
                if (!(status instanceof String text)) throw new IllegalArgumentException("Missing overnight preview status");
                state=switch(text) { case "PREVIEW","REPAIR_PREVIEW" -> State.SUCCEEDED; case "SKIPPED","NO_SHIFT" -> State.SKIPPED; default -> throw new IllegalArgumentException("Unexpected overnight preview status"); };
                Object run=result.get("run_id");
                if (run != null) { if (!(run instanceof String textRun) || textRun.isBlank()) throw new IllegalArgumentException("Invalid overnight run identity"); runId=textRun; }
                if (state == State.SUCCEEDED) Required.value(runId,"successful overnight preview");
                Object resultReason=result.get("reason");
                if (resultReason != null) { if (!(resultReason instanceof String textReason) || textReason.isBlank()) throw new IllegalArgumentException("Invalid overnight reason"); reason=textReason; }
            }
        } catch (RuntimeException failure) {
            state=failure instanceof SearchDeadline.Expired ? State.CANCELLED : State.FAILED;
            FailureCode code=failure instanceof SearchDeadline.Expired ? FailureCode.DEADLINE_OR_CANCELLATION
                    : failure instanceof RoadClient.RoadUnavailable ? FailureCode.ROUTING_UNAVAILABLE
                    : failure instanceof HttpCalculation.Unavailable ? FailureCode.SOLVER_TRANSPORT_UNAVAILABLE
                    : failure instanceof org.springframework.web.server.ResponseStatusException ? FailureCode.INPUT_CONFLICT : FailureCode.CALCULATION_FAILED;
            context=new Failure(1,code,Required.value(failure.getClass().getName()),"PREVIEW");
            LOG.warn("Overnight calculation stopped attempt={} metro={} serviceDate={} state={} code={}",id,metro,day,state,code,failure);
        }
        State terminal=state; String savedRun=runId; String savedReason=reason; Failure savedFailure=context;
        try {
            transactions.executeWithoutResult(_ -> {
                int changed=jdbc.update("UPDATE overnight_optimization_attempt SET state=?,\"resultRunId\"=?,\"resultReason\"=?,\"failureContext\"=?::jsonb,\"finishedAt\"=clock_timestamp() WHERE id=? AND state='ATTEMPTED'",
                        terminal.name(),savedRun,savedReason,savedFailure == null ? null : CalculationJson.write(savedFailure),id);
                if (changed != 1) throw new IllegalStateException("Overnight receipt ownership changed");
            });
            LOG.info("Overnight outcome attempt={} metro={} serviceDate={} state={} run={}",id,metro,day,terminal,savedRun);
        } catch (RuntimeException failure) {
            receiptWriteFailures.incrementAndGet(); LOG.error("Overnight terminal receipt failed attempt={} metro={} serviceDate={}",id,metro,day,failure);
        }
    }
    public List<Receipt> attempts(String metro,LocalDate day) {
        CalculationJson.text(metro);
        return Required.value(jdbc.query("SELECT id,\"metroId\",\"serviceDate\",state,\"previewKey\",\"resultRunId\",\"failureContext\"::text,\"startedAt\",\"finishedAt\",\"resultReason\" FROM overnight_optimization_attempt WHERE \"metroId\"=? AND \"serviceDate\"=? ORDER BY \"startedAt\" DESC,id LIMIT 100",(rs,_) -> {
            String json=rs.getString(7); var finished=rs.getTimestamp(9);
            return new Receipt(DatabaseFacts.string(rs,1),DatabaseFacts.string(rs,2),Required.value(DatabaseFacts.timestamp(rs,3).toLocalDateTime().toLocalDate()),
                    State.valueOf(DatabaseFacts.string(rs,4)),DatabaseFacts.string(rs,5),rs.getString(6),rs.getString(10),json == null ? null : CalculationJson.read(json,Failure.class),
                    Required.value(DatabaseFacts.timestamp(rs,8).toInstant()),finished == null ? null : Required.value(finished.toInstant()));
        },metro,day));
    }
    public Map<String,Long> metrics() {
        Map<String,Long> counts=new TreeMap<>(); for (State state : State.values()) counts.put(state.name(),0L);
        jdbc.query("SELECT state,count(*) FROM overnight_optimization_attempt GROUP BY state",(org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(State.valueOf(DatabaseFacts.string(rs,1)).name(),DatabaseFacts.longValue(rs,2)));
        counts.put("totalAttempted",counts.values().stream().mapToLong(value -> Required.value(value).longValue()).sum());
        counts.put("unfinishedOlderThanFiveMinutes",DatabaseFacts.query(jdbc,"SELECT count(*) FROM overnight_optimization_attempt WHERE state='ATTEMPTED' AND \"startedAt\"<clock_timestamp()-interval '5 minutes'",Long.class));
        counts.put("receiptWriteFailuresSinceProcessStart",receiptWriteFailures.get());
        counts.put("claimedByOtherReplicaSinceProcessStart",claimedElsewhere.get());
        return counts;
    }
}
