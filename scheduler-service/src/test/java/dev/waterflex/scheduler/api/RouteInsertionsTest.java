package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicResponses.InsertionGap;
import dev.waterflex.scheduler.api.PublicResponses.InsertionStart;
import dev.waterflex.scheduler.api.PublicResponses.InsertionTechnicianDay;
import dev.waterflex.scheduler.api.PublicResponses.RouteInsertionResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The spec example's day: tech-1 works 08:00 to 17:00 (America/Chicago, UTC-5) with appt-7 (60 minutes, window 08:00
 * to 12:00) then appt-8 (45 minutes, window 12:00 to 16:00, located by address); tech-2 works the same shift with an
 * absence from 12:00 to 13:00 and no appointments. Travel buffer is 20 percent plus 2 minutes, so a 600-second leg
 * counts 14 minutes.
 */
class RouteInsertionsTest {
    private static final RoadPoint ADDRESS = new RoadPoint(41.2587, -95.9378), APPT_7 = new RoadPoint(41.2603, -96.0731), JOB = new RoadPoint(41.2405, -96.0102);
    private static final DailyPreparation.AddressLocator LOCATOR = _ -> ADDRESS;

    private static RoadClient roads(BiFunction<RoadPoint, RoadPoint, RoadClient.@Nullable Leg> leg) {
        return mock(RoadClient.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("activeIdentity")) return "omaha-map-v7";
            if (!method.equals("sparse")) throw new AssertionError("Unexpected routing call " + method);
            List<RoadClient.Pair> pairs = Required.value(invocation.getArgument(0));
            Map<String, RoadClient.Leg> legs = new HashMap<>();
            for (RoadClient.Pair pair : pairs) {
                RoadClient.Leg answered = leg.apply(pair.origin(), pair.destination());
                if (answered != null) legs.put(pair.id(), answered);
            }
            return legs;
        });
    }

    private static RoadClient uniform(int seconds) { return roads((_, _) -> new RoadClient.Leg(seconds, 1000)); }

    private static RouteInsertions insertions(RoadClient roads) {
        return new RouteInsertions(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> roads), LOCATOR, 4);
    }

    private static ObjectNode request() {
        return (ObjectNode) Required.value(CalculationJson.tree(PublicApiContractTest.example("RouteInsertionRequest")));
    }

    private static ObjectNode object(ObjectNode parent, String field) { return (ObjectNode) Required.value(parent.get(field)); }

    private static RouteInsertionResult answer(RouteInsertions service, ObjectNode request) {
        var reply = service.insert("acme", CalculationJson.write(request));
        assertEquals(200, reply.status(), reply.json());
        return PublicRequests.read(reply.json(), RouteInsertionResult.class);
    }

    private static InsertionTechnicianDay day(RouteInsertionResult result, String technicianId) {
        for (InsertionTechnicianDay day : result.technicianDays()) if (day.technicianId().equals(technicianId)) return day;
        throw new AssertionError("No technician-day for " + technicianId);
    }

    private static Instant at(String localTime) { return Required.value(Instant.parse("2026-10-12T" + localTime + ":00-05:00")); }

    private static List<Instant> starts(InsertionGap gap) {
        List<Instant> starts = new java.util.ArrayList<>();
        for (InsertionStart start : gap.starts()) starts.add(start.plannedStart());
        return Required.value(List.copyOf(starts));
    }

    private static void arrivalBuffer(ObjectNode request, int percent, int minMinutes, int maxMinutes) {
        object(object(request, "snapshot"), "rates").putObject("arrivalBuffer").put("percent", percent).put("minMinutes", minMinutes).put("maxMinutes", maxMinutes);
    }

    @Test void everyGapReportsItsLegsTheLegItBreaksAndTheDriveItAdds() {
        RouteInsertionResult result = answer(insertions(uniform(600)), request());
        assertEquals("omaha-map-v7", result.routingIdentity());
        assertEquals(List.of(), result.skippedTechnicianDays());
        assertEquals(List.of("tech-1", "tech-2"), result.technicianDays().stream().map(day -> Required.value(day).technicianId()).toList());

        InsertionTechnicianDay first = day(result, "tech-1");
        assertTrue(first.holdsAsGiven());
        assertTrue(first.qualified());
        assertEquals(new PublicResponses.RouteTotals(42L, 3000L, 147L, Required.value(first.asGiven()).costCents(), 0L), first.asGiven(), "three buffered legs, timed with no avoidable waiting");
        assertNull(first.stops(), "stops are sent only when asked for");
        assertEquals(3, first.gaps().size(), "one more gap than appointments");
        InsertionGap before = Required.value(first.gaps().get(0)), between = Required.value(first.gaps().get(1)), after = Required.value(first.gaps().get(2));
        assertNull(before.afterAppointmentId());
        assertEquals("appt-7", before.beforeAppointmentId());
        assertEquals("appt-7", between.afterAppointmentId());
        assertEquals("appt-8", between.beforeAppointmentId());
        assertEquals("appt-8", after.afterAppointmentId());
        assertNull(after.beforeAppointmentId());
        for (InsertionGap gap : first.gaps()) {
            assertTrue(gap.routable());
            assertEquals(new PublicResponses.InsertionLeg(600L, 1000L, 14), gap.toJob());
            assertEquals(new PublicResponses.InsertionLeg(600L, 1000L, 14), gap.fromJob());
            assertEquals(new PublicResponses.InsertionLeg(600L, 1000L, 14), gap.replaced());
            assertEquals(600L, gap.addedSeconds(), "two new legs less the one they replace");
            assertEquals(1000L, gap.addedMeters());
            assertEquals(14, gap.addedBufferedMinutes());
        }

        InsertionGap empty = Required.value(day(result, "tech-2").gaps().getFirst());
        assertEquals(1, day(result, "tech-2").gaps().size());
        assertNull(empty.afterAppointmentId());
        assertNull(empty.beforeAppointmentId());
        assertNull(empty.replaced(), "a day with no appointments has no leg to break");
        assertEquals(1200L, empty.addedSeconds());
        assertEquals(2000L, empty.addedMeters());
        assertEquals(28, empty.addedBufferedMinutes());
    }

    @Test void theAddedDriveSubtractsTheReplacedLegFromTheSameRoads() {
        // Only the leg appt-7 to appt-8 is long, so only the gap that breaks it saves drive.
        RoadClient roads = roads((origin, destination) -> origin.equals(APPT_7) && destination.equals(ADDRESS) ? new RoadClient.Leg(1800, 9000) : new RoadClient.Leg(600, 1000));
        InsertionGap between = Required.value(day(answer(insertions(roads), request()), "tech-1").gaps().get(1));
        assertEquals(new PublicResponses.InsertionLeg(1800L, 9000L, 38), between.replaced());
        assertEquals(-600L, between.addedSeconds(), "600 + 600 - 1800");
        assertEquals(-7000L, between.addedMeters());
        assertEquals(-10, between.addedBufferedMinutes(), "14 + 14 - 38");
    }

    @Test void startsAreTheHalfHoursAtWhichTheDayStillHolds() {
        RouteInsertionResult result = answer(insertions(uniform(600)), request());
        InsertionTechnicianDay first = day(result, "tech-1");
        // Before appt-7: reachable from 08:14, and appt-7 must still be reached before 12:00.
        assertEquals(List.of(at("08:30"), at("09:00"), at("09:30"), at("10:00"), at("10:30")), starts(Required.value(first.gaps().get(0))));
        // Between: after appt-7 (earliest done 09:14 plus the leg), and appt-8 must still be reached before 16:00.
        List<Instant> between = starts(Required.value(first.gaps().get(1)));
        assertEquals(at("09:30"), between.getFirst());
        assertEquals(at("14:30"), between.getLast());
        assertEquals(11, between.size());
        // After appt-8: it opens at 12:00, and the technician must be back by 17:00.
        assertEquals(List.of(at("13:00"), at("13:30"), at("14:00"), at("14:30"), at("15:00"), at("15:30")), starts(Required.value(first.gaps().get(2))));

        InsertionStart early = Required.value(Required.value(first.gaps().get(0)).starts().getFirst());
        assertEquals(new PublicTypes.Window(at("08:00"), at("10:00")), early.window(), "a window of the client's length that opens on the hour at or before the start");
        assertNull(early.keepsWindow(), "the job carried no promised window");
        assertEquals(56L, early.timed().driveMinutes(), "four buffered legs");
        assertEquals(4000L, early.timed().meters());
        assertNull(early.stops());

        // tech-2 is away from 12:00 to 13:00: the job (60 minutes, 14 back) fits before the absence or after it.
        assertEquals(List.of(at("08:30"), at("09:00"), at("09:30"), at("10:00"), at("10:30"), at("13:30"), at("14:00"), at("14:30"), at("15:00"), at("15:30")),
                starts(Required.value(day(result, "tech-2").gaps().getFirst())));
    }

    @Test void aStartInsideTheLateArrivalBufferIsNotOffered() {
        ObjectNode request = request();
        request.put("windowMinutes", 60);
        // Half of a 60-minute window: every window's deadline is its half hour, so only starts on the hour remain.
        arrivalBuffer(request, 50, 0, 60);
        List<Instant> offered = starts(Required.value(day(answer(insertions(uniform(600)), request), "tech-2").gaps().getFirst()));
        assertEquals(List.of(at("09:00"), at("10:00"), at("14:00"), at("15:00")), offered);
    }

    @Test void aMovedAppointmentKeepsItsPromisedWindowUpToTheBufferAndGetsANewOneOutsideIt() {
        ObjectNode request = request();
        object(request, "job").putObject("window").put("start", "2026-10-12T09:00:00-05:00").put("end", "2026-10-12T11:00:00-05:00");
        // 25 percent of 120 minutes: the promised window's deadline is 10:30.
        arrivalBuffer(request, 25, 15, 60);
        InsertionGap gap = Required.value(day(answer(insertions(uniform(600)), request), "tech-2").gaps().getFirst());
        Map<Instant, InsertionStart> byStart = new HashMap<>();
        for (InsertionStart start : gap.starts()) byStart.put(start.plannedStart(), start);
        PublicTypes.Window promised = new PublicTypes.Window(at("09:00"), at("11:00"));
        for (String listed : List.of("09:00", "09:30", "10:00")) {
            String kept = Required.value(listed);
            InsertionStart start = Required.value(byStart.get(at(kept)), kept);
            assertEquals(promised, start.window());
            assertEquals(Boolean.TRUE, start.keepsWindow());
        }
        assertFalse(byStart.containsKey(at("10:30")), "a start at the promised window's buffer deadline is not offered, in that window or any other");
        InsertionStart earlier = Required.value(byStart.get(at("08:30")));
        assertEquals(new PublicTypes.Window(at("08:00"), at("10:00")), earlier.window());
        assertEquals(Boolean.FALSE, earlier.keepsWindow(), "a start outside the promised window changes the promise");
        assertEquals(Boolean.FALSE, Required.value(byStart.get(at("13:30"))).keepsWindow());
    }

    @Test void aStartThatWouldPushAnotherCustomerIntoTheirBufferIsNotOffered() {
        ObjectNode request = request();
        // appt-7's window is 08:00 to 12:00: with 25 percent held between 15 and 60 minutes, its deadline is 11:00.
        arrivalBuffer(request, 25, 15, 60);
        List<Instant> before = starts(Required.value(day(answer(insertions(uniform(600)), request), "tech-1").gaps().get(0)));
        assertEquals(List.of(at("08:30"), at("09:00"), at("09:30")), before, "from 10:00 on, appt-7 would be reached at 11:14 or later: on time, but inside its buffer");
    }

    @Test void aGapWithNoRoadRouteHasNoNumbersAndNoStarts() {
        RoadClient roads = roads((origin, destination) -> origin.equals(APPT_7) && destination.equals(JOB) ? null : new RoadClient.Leg(600, 1000));
        var reply = insertions(roads).insert("acme", CalculationJson.write(request()));
        assertEquals(200, reply.status(), reply.json());
        InsertionTechnicianDay first = day(PublicRequests.read(reply.json(), RouteInsertionResult.class), "tech-1");
        InsertionGap between = Required.value(first.gaps().get(1));
        assertFalse(between.routable());
        assertNull(between.toJob());
        assertNull(between.fromJob());
        assertNull(between.replaced());
        assertNull(between.addedSeconds(), "a missing leg is never a leg of zero");
        assertNull(between.addedMeters());
        assertNull(between.addedBufferedMinutes());
        assertEquals(List.of(), between.starts());
        assertTrue(Required.value(first.gaps().get(0)).routable(), "the other gaps do not use that leg");
        assertFalse(Required.value(first.gaps().get(0)).starts().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new InsertionGap(null, null, false, new PublicResponses.InsertionLeg(0L, 0L, 0), null, null, null, null, null,
                Required.value(List.of())), "the response type refuses legs on a gap that is not routable");
    }

    @Test void anUnqualifiedTechnicianAndADayThatDoesNotHoldHaveNoStarts() {
        ObjectNode install = request();
        object(install, "job").put("serviceId", "softener-install");
        RouteInsertionResult unqualified = answer(insertions(uniform(600)), install);
        InsertionTechnicianDay second = day(unqualified, "tech-2");
        assertFalse(second.qualified(), "tech-2 only services softeners");
        assertTrue(second.holdsAsGiven());
        assertTrue(Required.value(second.gaps().getFirst()).routable(), "the drive is still reported");
        assertEquals(List.of(), Required.value(second.gaps().getFirst()).starts());
        assertTrue(day(unqualified, "tech-1").qualified());

        // Twelve hours a leg: tech-1's own appointments cannot be reached, so nothing can be added to that day.
        InsertionTechnicianDay broken = day(answer(insertions(uniform(43_200)), request()), "tech-1");
        assertFalse(broken.holdsAsGiven());
        assertNull(broken.asGiven(), "a day that does not hold has no totals to report");
        for (InsertionGap gap : broken.gaps()) assertEquals(List.of(), gap.starts());
    }

    @Test void routesAreSentOnlyWhenAskedAndPlaceTheJobAtItsStart() {
        ObjectNode request = request();
        request.put("routes", true);
        arrivalBuffer(request, 25, 15, 60);
        InsertionTechnicianDay first = day(answer(insertions(uniform(600)), request), "tech-1");
        assertEquals(List.of("appt-7", "appt-8"), Required.value(first.stops()).stream().map(stop -> Required.value(stop).appointmentId()).toList());
        InsertionStart start = Required.value(Required.value(first.gaps().get(1)).starts().getFirst());
        List<PublicResponses.PlannedStop> stops = Required.value(start.stops());
        assertEquals(List.of("appt-7", "job-31", "appt-8"), stops.stream().map(stop -> Required.value(stop).appointmentId()).toList());
        PublicResponses.PlannedStop job = Required.value(stops.get(1));
        assertEquals(1, job.sequence());
        assertEquals(start.plannedStart(), job.plannedStart());
        assertEquals(Required.value(start.plannedStart().plusSeconds(3600)), job.plannedEnd());
        assertEquals(Required.value(start.window().end().minusSeconds(1800)), job.arrivalBufferStart(), "the buffer of the window the start would promise, not of an internal one");
    }

    @Test void invalidAndIncompleteRequestsAreRefused() {
        RouteInsertions service = insertions(uniform(600));
        ObjectNode missingJob = request();
        missingJob.remove("job");
        assertEquals(400, service.insert("acme", CalculationJson.write(missingJob)).status());
        ObjectNode nullLocation = request();
        object(nullLocation, "job").putNull("location");
        assertEquals(400, service.insert("acme", CalculationJson.write(nullLocation)).status());
        ObjectNode taken = request();
        object(taken, "job").put("id", "appt-7");
        assertEquals(400, service.insert("acme", CalculationJson.write(taken)).status(), "the snapshot must not contain the job");
        ObjectNode technician = request();
        object(technician, "job").put("id", "tech-1");
        assertEquals(400, service.insert("acme", CalculationJson.write(technician)).status());
        ObjectNode step = request();
        step.put("startStepMinutes", 20);
        assertEquals(400, service.insert("acme", CalculationJson.write(step)).status());
        ObjectNode window = request();
        window.put("windowMinutes", 90);
        assertEquals(400, service.insert("acme", CalculationJson.write(window)).status(), "window lengths are whole hours");
        ObjectNode noRoutes = request();
        noRoutes.remove("routes");
        assertEquals(400, service.insert("acme", CalculationJson.write(noRoutes)).status(), "routes is required, never assumed false");
        ObjectNode withId = request();
        withId.put("requestId", "3b1f6c1e-2a7d-4f0e-8c52-9a1d7e6b4c21");
        assertEquals(400, service.insert("acme", CalculationJson.write(withId)).status(), "nothing is stored, so no requestId is accepted");

        ObjectNode nullWindow = request();
        object(nullWindow, "job").putNull("window");
        assertEquals(200, service.insert("acme", CalculationJson.write(nullWindow)).status(), "a job without a promise may say so with null");

        var lincolnOnly = new RouteInsertions(new MetroRouting(Required.value(Map.of("lincoln", "http://b:1")), _ -> uniform(600)), LOCATOR, 4);
        var unknown = lincolnOnly.insert("acme", CalculationJson.write(request()));
        assertEquals(422, unknown.status());
        assertEquals(PublicResponses.ErrorCode.INCOMPLETE_FACTS, PublicRequests.read(unknown.json(), PublicResponses.Problem.class).error());
    }

    @Test void aLocationThatCannotBeLocatedSkipsItsDayOrRefusesTheJob() {
        var unlocatable = new RouteInsertions(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> uniform(600)), _ -> null, 4);
        RouteInsertionResult result = answer(unlocatable, request());
        assertEquals(List.of("tech-1"), result.skippedTechnicianDays().stream().map(day -> Required.value(day).technicianId()).toList(), "appt-8 has an address only");
        assertEquals(List.of("tech-2"), result.technicianDays().stream().map(day -> Required.value(day).technicianId()).toList());

        ObjectNode addressOnly = request();
        ObjectNode location = object(addressOnly, "job").putObject("location");
        location.putObject("address").put("line1", "1 Nowhere Rd").put("city", "Omaha").put("state", "NE").put("postalCode", "68102");
        var refused = unlocatable.insert("acme", CalculationJson.write(addressOnly));
        assertEquals(422, refused.status(), refused.json());
        assertEquals(PublicResponses.ErrorCode.INCOMPLETE_FACTS, PublicRequests.read(refused.json(), PublicResponses.Problem.class).error());
    }

    @Test void routingThatIsDownIsRetryable() {
        RoadClient down = mock(RoadClient.class, invocation -> {
            if (invocation.getMethod().getName().equals("activeIdentity")) return "omaha-map-v7";
            throw new RoadClient.RoadUnavailable("down");
        });
        var reply = insertions(down).insert("acme", CalculationJson.write(request()));
        assertEquals(503, reply.status());
        assertEquals(PublicResponses.ErrorCode.ROUTING_UNAVAILABLE, PublicRequests.read(reply.json(), PublicResponses.Problem.class).error());
    }

    @Test void aFullLimitIsRefusedAtOnceAndFreedWhenTheCallEnds() throws InterruptedException {
        CountDownLatch routing = new CountDownLatch(1), release = new CountDownLatch(1);
        RoadClient slow = roads((_, _) -> {
            routing.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("never released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            return new RoadClient.Leg(600, 1000);
        });
        RouteInsertions one = new RouteInsertions(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> slow), LOCATOR, 1);
        String body = CalculationJson.write(request());
        Thread first = new Thread(() -> one.insert("acme", body));
        first.start();
        try {
            assertTrue(routing.await(10, TimeUnit.SECONDS), "the first call is routing");
            var refused = one.insert("acme", body);
            assertEquals(429, refused.status());
            assertEquals(1, refused.retryAfterSeconds());
            assertEquals(PublicResponses.ErrorCode.BUSY, PublicRequests.read(refused.json(), PublicResponses.Problem.class).error());
        } finally {
            release.countDown();
            first.join(10_000);
        }
        assertEquals(200, one.insert("acme", body).status(), "the slot is free again once the first call has answered");
        assertThrows(IllegalArgumentException.class, () -> new RouteInsertions(new MetroRouting(Required.value(Map.of("omaha", "http://a:1")), _ -> slow), LOCATOR, 0));
    }

    @Test void everyTechnicianDayOfSeveralDatesIsAnsweredInDateOrder() {
        ObjectNode request = request();
        ObjectNode snapshot = object(request, "snapshot");
        ArrayNode days = (ArrayNode) Required.value(snapshot.get("technicianDays"));
        ObjectNode next = ((ObjectNode) Required.value(days.get(1))).deepCopy();
        next.put("serviceDate", "2026-10-13");
        object(next, "shift").put("start", "2026-10-13T08:00:00-05:00").put("end", "2026-10-13T17:00:00-05:00");
        next.putArray("absences");
        days.add(next);
        RouteInsertionResult result = answer(insertions(uniform(600)), request);
        assertEquals(List.of("2026-10-12/tech-1", "2026-10-12/tech-2", "2026-10-13/tech-2"),
                result.technicianDays().stream().map(day -> Required.value(day).serviceDate() + "/" + Required.value(day).technicianId()).toList());
        InsertionGap gap = Required.value(Required.value(result.technicianDays().get(2)).gaps().getFirst());
        assertEquals(Required.value(Instant.parse("2026-10-13T08:30:00-05:00")), gap.starts().getFirst().plannedStart());
        assertEquals(15, gap.starts().size(), "no absence that day: every half hour from 08:30 to 15:30");
    }
}
