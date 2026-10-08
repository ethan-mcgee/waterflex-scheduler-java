package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.optimizer.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"solver.auth-token=fixture-private-token-at-least-32-characters","solver.variant=CURRENT_CAPPED"})
class SolverReplayTest {
    static final String TOKEN = "fixture-private-token-at-least-32-characters";
    @Value("${local.server.port}") int port;
    private URI uri(String path) { return Required.value(URI.create("http://127.0.0.1:"+port+path)); }
    private HttpResponse<String> post(CalculationProtocol.Request request,String token) throws Exception {
        return Required.value(HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/v1/solve/"+request.operation().toLowerCase(Locale.ROOT)))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").header(HttpCalculation.REQUEST_HEADER,request.requestId()).timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(CalculationJson.write(request))).build(),HttpResponse.BodyHandlers.ofString()));
    }
    @Test void databaseFreeBootAuthenticatesAndBookingReplayMatchesEmbeddedExactly() throws Exception {
        var request = ReplayFixture.bookingRequest(); assertEquals(401,post(request,"wrong-token").statusCode());
        var http = post(request,TOKEN); assertEquals(200,http.statusCode(),http.body());
        var remote = CalculationJson.read(Required.value(http.body()),CalculationProtocol.Response.class); remote.match(request);
        var embedded = new SearchDeadline(Required.value(Duration.ofSeconds(5))).within(() -> new EmbeddedCalculation(new DailySolver("CURRENT_CAPPED",17)).calculate(request));
        var actual = CalculationJson.read(remote.payload(),BookingCalculation.Output.class);
        var expected = CalculationJson.read(embedded.payload(),BookingCalculation.Output.class);
        assertEquals(expected,actual); assertFalse(actual.result().candidates().isEmpty());
        var input = ReplayFixture.booking(); BookingCalculation.validate(input.snapshot(),input.request(),actual.result());
        assertEquals("Timefold-2.6.0",remote.engine()); assertEquals(request.contentHash(),remote.contentHash());
        var adapter = new HttpCalculation("http://127.0.0.1:"+port,TOKEN);
        var response = new SearchDeadline(Required.value(Duration.ofSeconds(5))).within(() -> adapter.calculate(request));
        assertEquals(actual,CalculationJson.read(response.payload(),BookingCalculation.Output.class));
    }
    @Test void requiredRearrangementReplaysInsertionThenRefinementOverHttp() throws Exception {
        var original = ReplayFixture.booking(); var source = Required.value(original.snapshot().days().get(ReplayFixture.DAY));
        var a = new BookingSnapshot.Technician("a",ReplayFixture.START,Required.value(ReplayFixture.START.plusSeconds(14400)),120,0,Required.value(Set.of("service","old-service")),Required.value(List.of()),ReplayFixture.POINT,ReplayFixture.POINT,3);
        var b = new BookingSnapshot.Technician("b",a.shiftStart(),a.shiftEnd(),120,0,Required.value(Set.of("old-service")),Required.value(List.of()),ReplayFixture.POINT,ReplayFixture.POINT,3);
        var old = Required.value(source.visits().get("old"));
        var visit = new BookingSnapshot.Visit(old.id(),old.jobId(),"old-service",old.windowStart(),old.windowEnd(),60,old.location(),"a",ReplayFixture.START,false);
        Map<String,DayPlan.RoadLeg> roads = new TreeMap<>();
        for (String from : List.of("a","b","old","new")) for (String to : List.of("old","new","a:return","b:return")) if (!from.equals(to)) roads.put(from+">"+to,new DayPlan.RoadLeg(60,100));
        var day = new BookingSnapshot.Day(Required.value(Map.of("a",a,"b",b)),Required.value(Map.of("old",visit)),new BookingSnapshot.Arrangement(Required.value(Map.of("a",List.of("old"),"b",List.of()))),2,new BookingSnapshot.Roads(roads,Required.value(Set.of())));
        Map<LocalDate,BookingSnapshot.Day> days = new TreeMap<>(original.snapshot().days()); days.put(ReplayFixture.DAY,day);
        var snapshot = new BookingSnapshot("metro",ReplayFixture.CAPTURED,"config-rev","roads-rev",original.snapshot().policy(),original.snapshot().rates(),days);
        var request = new BoundedBookingSearch.Request("new","service",60,ReplayFixture.POINT);
        var insertionInput = new BookingCalculation.Input(snapshot,request,BookingCalculation.Stage.INSERTION,"BOUNDED",Required.value(Set.of()),null,0);
        BookingDataset.parse(BookingDataset.encode(insertionInput,BookingDataset.Encoding.SPARSE));
        var inserted = post(CalculationProtocol.Request.of("BOOKING",5000,insertionInput),TOKEN); assertEquals(200,inserted.statusCode(),inserted.body());
        var insertion = CalculationJson.read(CalculationJson.read(Required.value(inserted.body()),CalculationProtocol.Response.class).payload(),BookingCalculation.Output.class);
        assertTrue(insertion.result().candidates().isEmpty());
        var refinementInput = new BookingCalculation.Input(snapshot,request,BookingCalculation.Stage.REFINEMENT,"BOUNDED",Required.value(Set.of()),insertion.result(),0);
        var decodedRefinement = BookingDataset.parse(BookingDataset.encode(refinementInput,BookingDataset.Encoding.SPARSE));
        new SearchDeadline(Required.value(Duration.ofSeconds(5))).within(() -> BookingCalculation.run(decodedRefinement));
        var refined = post(CalculationProtocol.Request.of("BOOKING",5000,refinementInput),TOKEN); assertEquals(200,refined.statusCode(),refined.body());
        var result = CalculationJson.read(CalculationJson.read(Required.value(refined.body()),CalculationProtocol.Response.class).payload(),BookingCalculation.Output.class).result();
        assertTrue(result.complete()); assertFalse(result.candidates().isEmpty());
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.fairnessDelta().signum() < 0));
        BookingCalculation.validate(snapshot,request,result);
    }
    @Test void dailyFixedWorkReplayHasEquivalentProposalsPolicyAndCoverage() throws Exception {
        var request = ReplayFixture.dailyRequest(); var http = post(request,TOKEN); assertEquals(200,http.statusCode(),http.body());
        var remote = CalculationJson.read(Required.value(http.body()),CalculationProtocol.Response.class); remote.match(request);
        var embedded = DailyOperation.execute(new SearchAdmission(2,16),Required.value(Duration.ofSeconds(20)),
                () -> new EmbeddedCalculation(new DailySolver("CURRENT_CAPPED",17)).calculate(request),_ -> { });
        var actual = CalculationJson.read(remote.payload(),CalculationProtocol.DailyOutput.class);
        var expected = CalculationJson.read(embedded.payload(),CalculationProtocol.DailyOutput.class);
        assertEquals(expected.proposal(),actual.proposal()); assertEquals(expected.reference(),actual.reference());
        assertEquals(expected.outcome(),actual.outcome()); assertEquals(expected.accepted(),actual.accepted());
        var input = CalculationJson.read(request.payload(),CalculationProtocol.DailyInput.class);
        var source = DailyDataset.parse(input.dataset()).toDayPlan();
        assertEquals(actual.outcome(),DailyOutcome.assess(actual.proposal().restore(source)));
        assertTrue(RouteEvaluator.evaluate(actual.proposal().restore(source)).feasible());
    }
    @Test void malformedNullMissingDuplicateAndConflictingWireFactsFailClosed() throws Exception {
        var request = ReplayFixture.bookingRequest(); String json = CalculationJson.write(request);
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(json.replace("\"remainingMillis\":5000","\"remainingMillis\":null")),CalculationProtocol.Request.class));
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(json.replace("\"schemaVersion\":1,","")),CalculationProtocol.Request.class));
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(json.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")),CalculationProtocol.Request.class));
        assertThrows(IllegalArgumentException.class,() -> new CalculationProtocol.Request(1,request.requestId(),"BOOKING",5000,"wrong",request.payload()));
        String facts=CalculationJson.write(ReplayFixture.booking());
        assertTrue(facts.contains("\"durationMinutes\":30")); assertTrue(facts.contains("\"regularHourly\":\"30\""));
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(facts.replace("\"durationMinutes\":30","\"durationMinutes\":null")),BookingCalculation.Input.class));
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(facts.replace("\"regularHourly\":\"30\"","\"regularHourly\":30")),BookingCalculation.Input.class));
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(Required.value(facts.replace("\"lat\":41.25","\"lat\":null")),BookingCalculation.Input.class));
        var deleted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/v1/solves/"+request.requestId())).header("Authorization","Bearer "+TOKEN).header(HttpCalculation.REQUEST_HEADER,request.requestId()).DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,deleted.statusCode());
    }
    @Test void solveAndCancelRequireTheMatchingRoutingHeader() throws Exception {
        var request = ReplayFixture.bookingRequest(); var client = HttpClient.newHttpClient();
        for (String key : List.of("", Required.value(UUID.randomUUID().toString()))) {
            var builder = HttpRequest.newBuilder(uri("/v1/solve/booking")).header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CalculationJson.write(request)));
            if (!key.isEmpty()) builder.header(HttpCalculation.REQUEST_HEADER,key);
            assertEquals(400,client.send(builder.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var cancel = HttpRequest.newBuilder(uri("/v1/solves/"+request.requestId())).header("Authorization","Bearer "+TOKEN).DELETE();
            if (!key.isEmpty()) cancel.header(HttpCalculation.REQUEST_HEADER,key);
            assertEquals(400,client.send(cancel.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void missingRoadsAndForgedCandidateMetricsNeverCreateValidatedOffers() {
        var input = ReplayFixture.booking(); var day = Required.value(input.snapshot().days().get(ReplayFixture.DAY));
        Map<String,DayPlan.RoadLeg> roads = new HashMap<>(day.roads().legs()); roads.remove("a>new");
        var changed = new BookingSnapshot.Day(day.technicians(),day.visits(),day.baseline(),day.reservationVersion(),new BookingSnapshot.Roads(roads,Required.value(Set.of())));
        Map<java.time.LocalDate,BookingSnapshot.Day> days = new TreeMap<>(input.snapshot().days()); days.put(ReplayFixture.DAY,changed);
        var snapshot = new BookingSnapshot(input.snapshot().metroId(),input.snapshot().capturedAt(),input.snapshot().configurationFingerprint(),input.snapshot().routingIdentity(),input.snapshot().policy(),input.snapshot().rates(),days);
        var missing = new BookingCalculation.Input(snapshot,input.request(),input.stage(),input.variant(),input.dates(),null,0);
        assertThrows(BookingSnapshot.Incomplete.class,() -> BookingCalculation.run(missing));
        var result = BookingCalculation.run(input).result(); var original = result.candidates().getFirst();
        var forged = new BoundedBookingSearch.Candidate(original.window(),original.technicianId(),original.arrangement(),original.overtimeDelta(),
                original.costDeltaCents()-1,original.fairnessDelta(),original.changedAssignments(),original.insertionPosition(),original.source(),original.validation());
        var invalid = new BoundedBookingSearch.Result(Required.value(List.of(forged)),result.coverage(),result.complete(),result.distinctRegularWindows(),result.confirmedRegularMinutes(),result.regularCapacityMinutes(),false,result.stopReason());
        assertThrows(IllegalArgumentException.class,() -> BookingCalculation.validate(input.snapshot(),input.request(),invalid));
    }
    @Test void indexedBookingDenseAndSparseReplayTheSameDirectedFactsAndHash() {
        var input = ReplayFixture.booking();
        String sparse=BookingDataset.encode(input,BookingDataset.Encoding.SPARSE), dense=BookingDataset.encode(input,BookingDataset.Encoding.DENSE);
        assertEquals(CalculationJson.tree(sparse).path("contentHash"),CalculationJson.tree(dense).path("contentHash"));
        var first=BookingDataset.parse(sparse); var second=BookingDataset.parse(dense);
        assertEquals(first.snapshot(),second.snapshot()); assertEquals(first.request(),second.request());
        assertEquals(BookingCalculation.run(first),BookingCalculation.run(second));
        var roads=Required.value(first.snapshot().days().get(ReplayFixture.DAY)).roads();
        assertEquals(180,Required.value(roads.legs().get("old>new")).seconds()); assertEquals(60,Required.value(roads.legs().get("new>old")).seconds());
        assertThrows(IllegalArgumentException.class,() -> BookingDataset.parse(Required.value(sparse.replace("\"from\":0","\"from\":10000"))));
        assertThrows(IllegalArgumentException.class,() -> BookingDataset.parse(Required.value(dense.replace("\"seconds\":null","\"seconds\":0"))));
    }
    @Test void explicitNullResponseFactsAndEngineMismatchFailBeforeCallerUse() {
        var input=ReplayFixture.booking(); var output=BookingCalculation.run(input); String json=CalculationJson.write(output);
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)CalculationJson.tree(json);
        var candidate=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("result").path("candidates").get(0);
        candidate.putNull("validation");
        assertThrows(IllegalArgumentException.class,() -> CalculationJson.read(CalculationJson.write(root),BookingCalculation.Output.class));
        var request=ReplayFixture.bookingRequest(); var response=CalculationProtocol.Response.of(request,1,output);
        var engine=EngineProvenance.loaded();
        var wrong=new EngineProvenance(engine.artifact(),engine.version(),"0".repeat(64),null);
        var forged=new CalculationProtocol.Response(response.schemaVersion(),response.requestId(),response.contentHash(),response.policyVersion(),response.costVersion(),response.scoreVersion(),response.engine(),wrong,response.elapsedMillis(),response.payload());
        assertThrows(IllegalArgumentException.class,() -> forged.match(request));
    }
    @Test void coldDailyHttpInputKeepsEveryDemandExplicitAndOnlyAcceptsCompleteValidatedWork() throws Exception {
        var template=ReplayFixture.dailyRequest(); var original=CalculationJson.read(template.payload(),CalculationProtocol.DailyInput.class);
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)CalculationJson.tree(original.dataset()); root.put("mode","COLD").putNull("contentHash");
        for (var technician : root.path("technicians")) { var value=(com.fasterxml.jackson.databind.node.ObjectNode)technician; value.putArray("assigned"); value.put("pinnedPrefix",0); }
        for (var visit : root.path("visits")) { var value=(com.fasterxml.jackson.databind.node.ObjectNode)visit; value.putNull("originalTechnician").putNull("originalPlannedStart"); }
        root.putArray("unassigned").add(0).add(1);
        var input=new CalculationProtocol.DailyInput(DailyDataset.seal(CalculationJson.write(root)),original.policy());
        var request=CalculationProtocol.Request.of("DAILY",20000,input); var http=post(request,TOKEN); assertEquals(200,http.statusCode(),http.body());
        var response=CalculationJson.read(Required.value(http.body()),CalculationProtocol.Response.class); response.match(request);
        var output=CalculationJson.read(response.payload(),CalculationProtocol.DailyOutput.class); var outcome=output.outcome();
        assertEquals(2,outcome.assignedVisitIds().size()+outcome.unassignedVisitIds().size());
        var restored=output.proposal().restore(DailyDataset.parse(input.dataset()).toDayPlan()); assertEquals(outcome,DailyOutcome.assess(restored));
        if (output.accepted()) { assertTrue(outcome.complete()); assertTrue(outcome.assignedWorkFeasible()); assertTrue(outcome.scoringMatchesValidation()); assertTrue(outcome.policyEligible()); }
    }
}
