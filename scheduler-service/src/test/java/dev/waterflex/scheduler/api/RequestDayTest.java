package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.optimizer.DayPlan;
import dev.waterflex.scheduler.optimizer.PlanFacts;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestDayTest {
    private static final RoadPoint GEOCODED = new RoadPoint(41.2587, -95.9378);

    private static DailyProposalRequest request() {
        return PublicRequests.read(PublicApiContractTest.example("DailyProposalRequest"), DailyProposalRequest.class);
    }

    private static RequestDay day(DailyProposalRequest request, List<PublicTypes.Address> located) {
        return RequestDay.of(request, address -> { located.add(address); return GEOCODED; });
    }

    /** Every directed pair is routable: 60 seconds and 100 meters. */
    private static Map<String, DayPlan.RoadLeg> everyLeg(RequestDay day) {
        Map<String, DayPlan.RoadLeg> legs = new HashMap<>();
        for (String from : day.points().keySet()) for (String to : day.points().keySet())
            if (!from.equals(to)) legs.put(from + ">" + to, new DayPlan.RoadLeg(60, 100));
        return legs;
    }

    @Test void pointsUseTheDatabaseKeysAndOnlyAddressOnlyLocationsAreGeocoded() {
        List<PublicTypes.Address> located = new ArrayList<>();
        RequestDay day = day(request(), located);
        assertEquals(List.of("tech-1", "tech-1:return", "tech-2", "tech-2:return", "appt-7", "appt-8"), List.copyOf(day.points().keySet()));
        assertEquals(new RoadPoint(41.2565, -95.9345), day.points().get("tech-1"));
        assertEquals(new RoadPoint(41.2603, -96.0731), day.points().get("appt-7"));
        assertEquals(GEOCODED, day.points().get("appt-8"));
        assertEquals(1, located.size());
        assertEquals("1200 Example St", located.getFirst().line1());
    }

    @Test void thePlanCarriesEverySnapshotFactAndNeverAllowsOvertime() {
        RequestDay day = day(request(), new ArrayList<>());
        DayPlan plan = day.plan(everyLeg(day));
        PlanFacts facts = plan.getFacts();
        assertEquals(List.of("tech-1", "tech-2"), facts.technicians().stream().map(technician -> Required.value(technician).id()).toList());
        PlanFacts.Technician first = Required.value(facts.technicians().getFirst()), second = Required.value(facts.technicians().get(1));
        assertEquals(at("2026-10-12T13:00:00Z"), first.start());
        assertEquals(at("2026-10-12T22:00:00Z"), first.end());
        assertEquals(540, first.maxDaily());
        assertEquals(0, first.maxOvertime());
        assertEquals(0, second.maxOvertime());
        assertEquals(Set.of("softener-install", "softener-service"), first.qualifications());
        assertEquals(List.of(), first.absences());
        assertEquals(List.of(new TechRoute.Unavailable(at("2026-10-12T17:00:00Z"), at("2026-10-12T18:00:00Z"))), second.absences());
        PlanFacts.Visit visit = Required.value(facts.demand().getFirst());
        assertEquals(new PlanFacts.Visit("appt-7", "softener-service", at("2026-10-12T13:00:00Z"), at("2026-10-12T17:00:00Z"),
                60, "tech-1", at("2026-10-12T13:20:00Z")), visit);
        assertEquals(List.of("appt-7", "appt-8"), Required.value(plan.getRoutes().getFirst()).getVisits().stream().map(stop -> Required.value(stop).getId()).toList());
        assertEquals(List.of(), Required.value(plan.getRoutes().get(1)).getVisits());
        assertEquals(new BigDecimal("0.2"), facts.travelBufferPct().stripTrailingZeros());
        assertEquals(new BigDecimal("0.67"), facts.mileagePerMile().stripTrailingZeros());
        assertEquals(2, facts.travelBufferMinutes());
        assertEquals(Set.of(), facts.unreachable());
        assertEquals(30, facts.matrix().size());
        assertEquals(DayPlan.Mode.ASSIGNED, plan.getMode());
    }

    @Test void pairsRoutingDidNotReturnAreUnroutable() {
        RequestDay day = day(request(), new ArrayList<>());
        Map<String, DayPlan.RoadLeg> legs = everyLeg(day);
        legs.remove("appt-7>appt-8");
        legs.remove("tech-2>appt-8");
        assertEquals(Set.of("appt-7>appt-8", "tech-2>appt-8"), day.plan(legs).getFacts().unreachable());
    }

    @Test void aLegOutsideTheRequestedPointsIsARoutingFault() {
        RequestDay day = day(request(), new ArrayList<>());
        Map<String, DayPlan.RoadLeg> legs = everyLeg(day);
        legs.put("appt-7>appt-9", new DayPlan.RoadLeg(60, 100));
        assertThrows(IllegalStateException.class, () -> day.plan(legs));
        Map<String, DayPlan.RoadLeg> self = everyLeg(day);
        self.put("appt-7>appt-7", new DayPlan.RoadLeg(0, 0));
        assertThrows(IllegalStateException.class, () -> day.plan(self));
    }

    @Test void inputOrderDoesNotChangeThePlan() {
        ObjectNode reordered = object(CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest")));
        ObjectNode snapshot = object(reordered.get("snapshot"));
        for (String list : List.of("technicianDays", "appointments")) {
            ArrayNode items = (ArrayNode) Required.value(snapshot.get(list));
            ArrayNode reversed = snapshot.putArray(list);
            for (int i = items.size() - 1; i >= 0; i--) reversed.add(Required.value(items.get(i)));
        }
        DailyProposalRequest request = PublicRequests.read(CalculationJson.write(reordered), DailyProposalRequest.class);
        RequestDay original = day(request(), new ArrayList<>()), shuffled = day(request, new ArrayList<>());
        assertEquals(original.plan(everyLeg(original)).getFacts(), shuffled.plan(everyLeg(shuffled)).getFacts());
    }

    @Test void anAppointmentSharingATechnicianIdentityIsRejected() {
        ObjectNode request = object(CalculationJson.tree(PublicApiContractTest.example("DailyProposalRequest")));
        ArrayNode appointments = (ArrayNode) Required.value(object(request.get("snapshot")).get("appointments"));
        object(appointments.get(0)).put("id", "tech-2");
        DailyProposalRequest colliding = PublicRequests.read(CalculationJson.write(request), DailyProposalRequest.class);
        assertThrows(IllegalArgumentException.class, () -> day(colliding, new ArrayList<>()));
    }

    private static Instant at(String text) { return Required.value(Instant.parse(text)); }

    private static ObjectNode object(com.fasterxml.jackson.databind.@org.jspecify.annotations.Nullable JsonNode node) {
        assertInstanceOf(ObjectNode.class, node);
        return (ObjectNode) Required.value(node);
    }
}
