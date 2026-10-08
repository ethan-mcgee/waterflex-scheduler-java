package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicRequests.DailyProposalRequest;
import dev.waterflex.scheduler.api.PublicResponses.SkippedTechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.Appointment;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.optimizer.DayPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Routes a daily request through its metro's routing service and builds the solver input. No database access. */
public final class DailyPreparation {
    private DailyPreparation() { }

    /** Locates an address-only location with certainty, or returns null. It never guesses. */
    public interface AddressLocator { @Nullable RoadPoint locate(PublicTypes.Address address); }

    /**
     * The solver input for the technician-days that could be located, the routing identity its legs came from, the
     * road points it was routed over, and the technician-days left exactly as they are.
     */
    public record Prepared(DayPlan plan, String routingIdentity, Map<String, RoadPoint> points, List<SkippedTechnicianDay> skipped) {
        public Prepared {
            points = Required.value(Map.copyOf(points));
            skipped = Required.value(List.copyOf(skipped));
        }
    }

    /**
     * Fails with {@link MetroRouting.UnknownMetro} before any geocoding when the metro has no routing service, and
     * with {@link RoadClient.RoadUnavailable} when routing is down or its identity changes while the legs are fetched.
     * A technician-day with a location that cannot be located is left out of the plan and reported, so one bad
     * address never stops the rest of the day.
     */
    public static Prepared prepare(DailyProposalRequest request, MetroRouting routing, AddressLocator locator) {
        PublicTypes.Snapshot snapshot = request.snapshot();
        RoadClient roads = routing.client(snapshot.metroId());
        Map<PublicTypes.Address, @Nullable RoadPoint> located = new HashMap<>();
        List<TechnicianDay> keptDays = new ArrayList<>();
        List<Appointment> keptAppointments = new ArrayList<>();
        List<SkippedTechnicianDay> skipped = new ArrayList<>();
        for (TechnicianDay day : snapshot.technicianDays()) {
            List<String> unresolved = new ArrayList<>();
            if (!locatable(day.start(), locator, located)) unresolved.add("start location");
            if (!locatable(day.end(), locator, located)) unresolved.add("end location");
            List<Appointment> appointments = new ArrayList<>();
            for (Appointment appointment : snapshot.appointments()) {
                if (!appointment.key().equals(day.key())) continue;
                appointments.add(appointment);
                if (!locatable(appointment.location(), locator, located)) unresolved.add("appointment " + appointment.id());
            }
            if (unresolved.isEmpty()) { keptDays.add(day); keptAppointments.addAll(appointments); continue; }
            skipped.add(new SkippedTechnicianDay(day.technicianId(), day.serviceDate(), PublicResponses.SkipReason.LOCATION_UNRESOLVED,
                    "No coordinates and no certain address match for " + String.join(", ", unresolved) + "; send coordinates to include this technician-day"));
        }
        var kept = new DailyProposalRequest(request.requestId(), request.serviceDate(), new PublicTypes.Snapshot(snapshot.metroId(), snapshot.timeZone(),
                snapshot.rates(), snapshot.policy(), snapshot.technicians(), Required.value(List.copyOf(keptDays)), Required.value(List.copyOf(keptAppointments))));
        RequestDay day = RequestDay.of(kept, address -> Required.value(located.get(address), "located address"));
        String identity = roads.activeIdentity();
        Map<String, DayPlan.RoadLeg> reachable = new HashMap<>();
        roads.matrix(day.points(), identity).forEach((pair, leg) -> reachable.put(pair, new DayPlan.RoadLeg(leg.seconds(), leg.meters())));
        // The database path rejects a plan whose routing identity moved during capture; so does this one.
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");
        return new Prepared(day.plan(reachable), identity, day.points(), skipped);
    }

    private static boolean locatable(Location location, AddressLocator locator, Map<PublicTypes.Address, @Nullable RoadPoint> located) {
        if (location.lat() != null && location.lng() != null) return true;
        PublicTypes.Address address = Required.value(location.address(), "address for a location without coordinates");
        if (!located.containsKey(address)) located.put(address, locator.locate(address));
        return located.get(address) != null;
    }
}
