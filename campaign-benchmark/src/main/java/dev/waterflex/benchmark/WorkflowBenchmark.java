package dev.waterflex.benchmark;

import static dev.waterflex.benchmark.Protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.io.StringReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.jspecify.annotations.Nullable;

/** Open-loop arrivals against a separately seeded isolated caller. No retries or synthetic server timings. */
public final class WorkflowBenchmark {
    public record Call(String id, String method, String path, JsonNode body, int expectedStatus) {
        public Call {
            CalculationJson.text(id);Required.value(body);
            if (!Set.of("POST","GET").contains(method) || !path.startsWith("/") || path.startsWith("//") || path.contains("..")
                    || path.contains("?") || expectedStatus < 100 || expectedStatus > 599) throw new IllegalArgumentException("Invalid workflow call");
        }
    }
    public record Request(Call call, @Nullable Long cancelAfterMs, @Nullable Call cancellation, List<@org.jspecify.annotations.NonNull Call> cleanup) {
        public Request {
            Required.value(call);cleanup=Required.value(List.copyOf(cleanup));
            if ((cancelAfterMs==null)!=(cancellation==null) || cancelAfterMs!=null && cancelAfterMs<1) throw new IllegalArgumentException("Cancellation must be explicit and complete");
        }
    }
    public record Envelope(int version, String datasetJson, String baseUrl, String endpointIdentity,
            Map<String,String> expectedConfiguration, List<@org.jspecify.annotations.NonNull Call> preparation, Map<String,List<Request>> warmupRequests,
            List<Request> requests, List<@org.jspecify.annotations.NonNull Call> verification, long cleanupObservationMs, long cleanupPollMs) {
        public Envelope {
            if(version!=1 || cleanupObservationMs<1 || cleanupPollMs<1 || cleanupPollMs>cleanupObservationMs || requests.isEmpty()
                    || preparation.isEmpty() || warmupRequests.isEmpty() || verification.isEmpty()) throw new IllegalArgumentException("Incomplete workflow envelope");
            Required.value(datasetJson);CalculationJson.text(endpointIdentity);
            URI uri=URI.create(baseUrl);
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !Set.of("","/").contains(uri.getPath())) throw new IllegalArgumentException("Caller origin URL required");
            expectedConfiguration=Required.value(Map.copyOf(expectedConfiguration));preparation=Required.value(List.copyOf(preparation));
            Map<String,List<Request>> copiedWarmup=new TreeMap<>();
            warmupRequests.forEach((key,value) -> copiedWarmup.put(Required.value(key),Required.value(List.copyOf(Required.value(value)))));
            warmupRequests=Required.value(Map.copyOf(copiedWarmup));requests=Required.value(List.copyOf(requests));verification=Required.value(List.copyOf(verification));
            Set<String> ids=new HashSet<>();
            for(Request request:requests) if(!ids.add(request.call().id())) throw new IllegalArgumentException("Duplicate measured request identity");
            for(List<Request> warm: warmupRequests.values()) for(Request request:warm) if(!ids.add(request.call().id())) throw new IllegalArgumentException("Warmup must use distinct disposable requests");
        }
    }
    public record Observation(String id, String outcome, long intendedArrivalNanos, @Nullable Long startedNanos,
            @Nullable Long responseCompletedNanos, long completedNanos, @Nullable Integer status, @Nullable JsonNode response, @Nullable String failure,
            @Nullable Object cancellation, List<Object> cleanup) { }
    private final Envelope envelope;
    private final HttpClient client;
    private final long timeoutMs;
    private WorkflowBenchmark(Envelope envelope,long timeoutMs) {
        this.envelope=envelope;this.timeoutMs=timeoutMs;
        client=Required.value(HttpClient.newBuilder().connectTimeout(Required.value(Duration.ofMillis(timeoutMs))).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    public static Map<String,@org.jspecify.annotations.NonNull Object> run(JsonNode campaign,String input,Path directory) throws Exception {
        Envelope envelope=record(CalculationJson.tree(input),Envelope.class);
        DailyDataset dataset=DailyDataset.parse(envelope.datasetJson());
        JsonNode source=get(campaign,"dataset"),facts=CalculationJson.tree(envelope.datasetJson());
        if(!DailyDataset.SCORE_MODEL.equals(text(get(source,"scoreVersion"))) || !DailyDataset.COST_MODEL.equals(text(get(source,"modelVersion")))
                || !text(get(get(facts,"routing"),"identity")).equals(text(get(source,"routingIdentity")))
                || !dataset.toDayPlan().getMode().name().toLowerCase(Locale.ROOT).equals(text(get(source,"cohort")))) throw new IllegalArgumentException("Workflow input model/cohort identity mismatch");
        if(envelope.verification().stream().noneMatch(call -> Required.value(call).path().equals("/internal/benchmark/audit") && call.expectedStatus()==200))
            throw new IllegalArgumentException("Caller independent scheduling audit is required");
        JsonNode load=get(campaign,"applicationLoad"),warm=get(campaign,"warmup");
        fields(load,"operation","mode","requestsPerCase","requestsPerSecond","concurrency","schedulerCache","providerCache","timeoutMs","observeCancellation","deployment","endpointIdentity");
        if (!text(get(load,"mode")).equals("paced-arrival") || !bool(get(load,"observeCancellation"))
                || !envelope.endpointIdentity().equals(text(get(load,"endpointIdentity"))) || envelope.requests().size()!=integer(get(load,"requestsPerCase"),1)) throw new IllegalArgumentException("Workflow load identity mismatch");
        var settings=record(get(campaign,"configuration"),CampaignSolverConfiguration.Settings.class);
        var budget=record(get(campaign,"budget"),PolicyBenchmark.Budget.class);
        if(operationBudgetMismatch(text(get(load,"operation")),budget,record(get(campaign,"policy"),SchedulingPolicy.Rules.class)))
            throw new IllegalArgumentException("Caller workflow budgets must describe its production allowances; offline budgets belong to Layers A/B");
        long timeout=integer(get(load,"timeoutMs"),1);
        if(timeout<budget.operationMs()) throw new IllegalArgumentException("Transport timeout cannot precede operation allowance");
        String operation=text(get(load,"operation"));
        if(!Set.of("daily-preview","booking-offer").contains(operation)) throw new IllegalArgumentException("Workflow operation");
        String mainPath=operation.equals("booking-offer") ? "/v1/offers" : "/v1/optimize/day/preview";
        for(Request request:envelope.requests()) if(!request.call().path().equals(mainPath)) throw new IllegalArgumentException("Measured call path differs from operation");
        var benchmark=new WorkflowBenchmark(envelope,timeout);
        // This endpoint requires benchmark profile and isolated waterflex_test before any mutation.
        JsonNode initial=benchmark.statistics(false);
        benchmark.configuration(initial,settings,integer(get(get(campaign,"case"),"solverSeed"),0),text(get(load,"deployment")));
        SnapshotFileIO.writeText(directory.resolve("caller-initial.json").toFile(),CalculationJson.write(initial));
        fields(warm,"millisecondsPerFreshJvm","paths","disposableInputs","calibrationMs","calibrationRepetitions","stabilityTolerancePercent");
        if(!bool(get(warm,"disposableInputs"))) throw new IllegalArgumentException("Disposable workflow warmup required");
        Map<String,Double> elapsed=new TreeMap<>();List<Object> warmEvidence=new ArrayList<>();
        for(String path:strings(get(warm,"paths"))) {
            if(!path.equals(operation)) throw new IllegalArgumentException("Warmup must invoke the measured caller operation");
            List<Request> requests=Required.value(envelope.warmupRequests().get(path),"Caller warmup inputs");long started=System.nanoTime();
            int index=0;long minimum=integer(get(warm,"millisecondsPerFreshJvm"),0);
            while((System.nanoTime()-started)/1_000_000<minimum) {
                if(index>=requests.size()) throw new IllegalArgumentException("Disposable warmup inputs exhausted; declare more unique inputs");
                warmEvidence.add(benchmark.observe(Required.value(requests.get(index++)),started,System.nanoTime()-started));
            }
            elapsed.put(path,(System.nanoTime()-started)/1e6);
        }
        SnapshotFileIO.writeText(directory.resolve("caller-warmup.json").toFile(),CalculationJson.write(warmEvidence));
        List<Object> preparation=new ArrayList<>();for(Call call:envelope.preparation()) preparation.add(benchmark.checked(Required.value(call)));
        SnapshotFileIO.writeText(directory.resolve("caller-preparation.json").toFile(),CalculationJson.write(preparation));
        JsonNode before=benchmark.statistics(true);SnapshotFileIO.writeText(directory.resolve("caller-before.json").toFile(),CalculationJson.write(before));
        int concurrency=Math.toIntExact(integer(get(load,"concurrency"),1));double rate=get(load,"requestsPerSecond").doubleValue();
        if(!get(load,"requestsPerSecond").isNumber() || !Double.isFinite(rate) || rate<=0) throw new IllegalArgumentException("Pacing rate");
        Semaphore available=new Semaphore(concurrency);List<CompletableFuture<@org.jspecify.annotations.NonNull Observation>> pending=new ArrayList<>();long started=System.nanoTime();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int index=0;index<envelope.requests().size();index++) {
                long intended=Math.round(index*1e9/rate);long remaining=started+intended-System.nanoTime();
                if(remaining>0) TimeUnit.NANOSECONDS.sleep(remaining);
                Request request=Required.value(envelope.requests().get(index));
                if(!available.tryAcquire()) {
                    pending.add(CompletableFuture.completedFuture(new Observation(request.call().id(),"GENERATOR_CAPACITY",intended,null,null,System.nanoTime()-started,null,null,"Declared client concurrency exhausted",null,Required.value(List.of()))));
                    continue;
                }
                pending.add(CompletableFuture.supplyAsync(() -> {
                    try { return benchmark.observe(request,started,intended); }
                    finally { available.release(); }
                },workers));
            }
            List<Observation> observations=new ArrayList<>();for(var future:pending) observations.add(Required.value(future.join()));
            SnapshotFileIO.writeText(directory.resolve("caller-observations.json").toFile(),CalculationJson.write(observations));
            // Observe real admission cleanup, including remote cancellation that outlives the HTTP response.
            long cleanupStarted=System.nanoTime();JsonNode after;
            do {
                after=benchmark.statistics(false);
                if(integer(get(get(after,"admission"),"active"),0)==0 && integer(get(get(after,"admission"),"queuedBookings"),0)==0
                        && integer(get(get(after,"admission"),"queuedBackground"),0)==0) break;
                TimeUnit.MILLISECONDS.sleep(envelope.cleanupPollMs());
            } while((System.nanoTime()-cleanupStarted)/1_000_000<envelope.cleanupObservationMs());
            SnapshotFileIO.writeText(directory.resolve("caller-after.json").toFile(),CalculationJson.write(after));
            boolean clean=integer(get(get(after,"admission"),"active"),0)==0 && integer(get(get(after,"admission"),"queuedBookings"),0)==0
                    && integer(get(get(after,"admission"),"queuedBackground"),0)==0;
            long cleanupElapsedMs=(System.nanoTime()-cleanupStarted)/1_000_000;
            List<Object> validations=new ArrayList<>();for(Call call:envelope.verification()) validations.add(benchmark.checked(Required.value(call)));
            SnapshotFileIO.writeText(directory.resolve("caller-verification.json").toFile(),CalculationJson.write(validations));
            Map<String,@org.jspecify.annotations.NonNull Object> result=new TreeMap<>();
            result.put("layer","workflow");result.put("datasetHash",dataset.contentHash());result.put("operation",operation);
            result.put("deployment",text(get(load,"deployment")));result.put("observations",observations);result.put("before",before);result.put("after",after);result.put("verification",validations);
            result.put("cleanupObservationMs",cleanupElapsedMs);result.put("cleanupComplete",clean);
            result.put("independentlyValid",clean && observations.stream().allMatch(observation -> observation.outcome().equals("RESPONSE") && Required.value(observation.status())<300));
            result.put("warmup",document(Map.of("paths",strings(get(warm,"paths")),"disposableInputs",true,"elapsedMsByPath",elapsed)));
            return result;
        }
    }
    private static boolean operationBudgetMismatch(String operation,PolicyBenchmark.Budget budget,SchedulingPolicy.Rules policy) {
        if(operation.equals("booking-offer")) return !budget.phase().equals("booking") || budget.operationMs()!=policy.bookingDeadlineMs();
        return !budget.phase().equals("pipeline") || budget.operationMs()!=20000 || budget.searchMs()!=15000 || budget.referenceMs()!=10000
                || budget.fairnessMs()!=5000 || budget.repairMs()!=0 || !budget.transferUnusedToFairness();
    }
    private Observation observe(Request request,long epoch,long intended) {
        long start=System.nanoTime()-epoch;Object cancellation=null;List<Object> cleanup=new ArrayList<>();
        String outcome="NOT_STARTED";Integer status=null;JsonNode response=null;String failure=null;Long responseCompleted=null;
        try(var timer=Executors.newSingleThreadScheduledExecutor()) {
            CompletableFuture<Object> cancelled=new CompletableFuture<>();
            java.util.concurrent.atomic.AtomicBoolean cancellationClaimed=new java.util.concurrent.atomic.AtomicBoolean();
            ScheduledFuture<?> scheduled=null;
            Long delay=request.cancelAfterMs();Call cancel=request.cancellation();
            if(delay!=null) scheduled=timer.schedule(() -> {
                if(!cancellationClaimed.compareAndSet(false,true)) return;
                try { cancelled.complete(checked(Required.value(cancel))); }
                catch(Throwable cancellationFailure) { cancelled.complete(Map.of("failure",String.valueOf(cancellationFailure.getMessage()))); }
            },delay,TimeUnit.MILLISECONDS);
            try {
                var result=send(request.call());status=result.statusCode();response=CalculationJson.tree(Required.value(result.body()));
                outcome=status==request.call().expectedStatus() ? "RESPONSE" : "UNEXPECTED_HTTP_STATUS";
            } catch(HttpTimeoutException expired) { outcome="TRANSPORT_TIMEOUT";failure=expired.getClass().getName(); }
            catch(Exception failed) { if(failed instanceof InterruptedException) Thread.currentThread().interrupt();outcome="TRANSPORT_FAILURE";failure=failed.getClass().getName()+":"+failed.getMessage(); }
            responseCompleted=System.nanoTime()-epoch;
            if(scheduled!=null) {
                if(cancellationClaimed.compareAndSet(false,true)) cancellation=Map.of("outcome","CALL_FINISHED_BEFORE_CANCELLATION");
                else cancellation=cancelled.join();
                scheduled.cancel(false);
            }
            for(Call call:request.cleanup()) cleanup.add(checked(Required.value(call)));
            return new Observation(request.call().id(),outcome,intended,start,responseCompleted,System.nanoTime()-epoch,status,response,failure,cancellation,cleanup);
        } catch(Exception failed) { return new Observation(request.call().id(),"CLEANUP_FAILURE",intended,start,responseCompleted,System.nanoTime()-epoch,status,response,String.valueOf(failed.getMessage()),cancellation,cleanup); }
    }
    private HttpResponse<String> send(Call call) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(envelope.baseUrl()).resolve(call.path())).timeout(Required.value(Duration.ofMillis(timeoutMs)))
                .header("Content-Type","application/json").method(call.method(),call.method().equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(CalculationJson.write(call.body()))).build();
        var response=Required.value(client.send(request,HttpResponse.BodyHandlers.ofString()));CalculationJson.checkSize(Required.value(response.body()));return response;
    }
    private Object checked(Call call) throws Exception {
        var response=send(call);if(response.statusCode()!=call.expectedStatus()) throw new IllegalStateException("Workflow call "+call.id()+" status "+response.statusCode());
        JsonNode body=CalculationJson.tree(Required.value(response.body()));
        if(call.path().equals("/internal/benchmark/audit")) {
            if(!bool(get(body,"independentlyValidated")) || !text(get(body,"costModelVersion")).equals(DailyDataset.COST_MODEL)
                    || !get(body,"days").isArray() || get(body,"days").isEmpty()) throw new IllegalArgumentException("Independent caller audit receipt required");
            for(JsonNode day:get(body,"days")) if(integer(get(get(Required.value(day),"policy"),"overtimeMinutes"),0)!=0)
                throw new IllegalArgumentException("Caller audit contains policy-ineligible overtime");
        }
        return document(Map.of("id",call.id(),"status",response.statusCode(),"response",body));
    }
    private JsonNode statistics(boolean reset) throws Exception {
        var call=new Call("statistics","POST","/internal/benchmark/statistics",CalculationJson.tree("{\"resetPeak\":"+reset+"}"),200);
        var response=send(call);if(response.statusCode()!=200) throw new IllegalStateException("Caller does not expose isolated benchmark statistics");
        JsonNode result=CalculationJson.tree(Required.value(response.body()));get(result,"configuration");get(result,"admission");get(result,"persistedCounts");get(result,"solver");return result;
    }
    private void configuration(JsonNode statistics,CampaignSolverConfiguration.Settings settings,long seed,String deployment) {
        JsonNode actual=get(statistics,"configuration");
        for(var expected:envelope.expectedConfiguration().entrySet()) if(!text(get(actual,Required.value(expected.getKey()))).equals(expected.getValue())) throw new IllegalArgumentException("Caller configuration mismatch: "+expected.getKey());
        if(!text(get(actual,"scheduler.optimizer.variant")).equals(settings.acceptor()) || !text(get(actual,"scheduler.optimizer.seed")).equals(Long.toString(seed))
                || !text(get(actual,"scheduler.calculation.mode")).equals(deployment.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Caller seed/variant/deployment mismatch");
        var configured=Required.value(SolverConfig.createFromXmlReader(new StringReader(text(get(get(statistics,"solver"),"configurationXml")))));
        if(!Required.value(configured.getEnvironmentMode()).name().equals(settings.environmentMode())) throw new IllegalArgumentException("Caller assertion mode mismatch");
        var phases=Required.value(configured.getPhaseConfigList());if(phases.size()!=1 || !(phases.getFirst() instanceof LocalSearchPhaseConfig local)) throw new IllegalArgumentException("Unexpected caller phases");
        var acceptor=Required.value(local.getAcceptorConfig());
        Integer size=settings.acceptor().equals("TABU") ? acceptor.getEntityTabuSize() : acceptor.getLateAcceptanceSize();
        if(Required.value(size)!=settings.acceptorSize() || Required.value(Required.value(local.getForagerConfig()).getAcceptedCountLimit())!=settings.acceptedCountLimit()) throw new IllegalArgumentException("Caller acceptor settings mismatch");
        if(!(local.getMoveSelectorConfig() instanceof UnionMoveSelectorConfig union) || Required.value(union.getSelectedCountLimit())!=settings.selectedCountLimit()) throw new IllegalArgumentException("Caller move selection cap mismatch");
        var selectors=Required.value(union.getMoveSelectorList());if(selectors.size()!=settings.moves().size()) throw new IllegalArgumentException("Caller move mixture mismatch");
        for(int index=0;index<selectors.size();index++) {
            var selector=Required.value(selectors.get(index));var declared=Required.value(settings.moves().get(index));
            if(!selector.getClass().getSimpleName().equals(Character.toUpperCase(declared.family().charAt(0))+declared.family().substring(1)+"MoveSelectorConfig")
                    || Double.compare(Required.value(selector.getFixedProbabilityWeight()),declared.weight())!=0) throw new IllegalArgumentException("Caller move weight mismatch");
        }
        Long spent=Required.value(configured.getTerminationConfig()).calculateTimeMillisSpentLimit();
        if(!settings.termination().kind().equals("fixed") || settings.termination().stepCap()!=null || configured.getMoveThreadCount()!=null
                || local.getTerminationConfig()!=null || Required.value(configured.getTerminationConfig()).getStepCountLimit()!=null
                || spent==null || spent!=15000L)
            throw new IllegalArgumentException("Workflow requires unchanged caller production termination; spentMs="+spent+" config="+Required.value(configured.getTerminationConfig())+": "+text(get(get(statistics,"solver"),"configurationXml")));
    }
}
