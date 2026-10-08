package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.BoundedBookingSearch;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.api.PublicRequests.BookingOffersRequest;
import dev.waterflex.scheduler.api.PublicResponses.SkippedTechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.Appointment;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import dev.waterflex.scheduler.optimizer.TechRoute;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Builds booking search facts for the host's horizon from a public request instead of the scheduler database. Every
 * date from {@code horizon.firstDate} to {@code horizon.lastDate} is covered, including dates with no technician-day.
 * Roads are left empty; the caller routes the snapshot before searching, as the database path does. No database access.
 */
public final class RequestBooking {
    private RequestBooking() { }

    /** The search facts, the request the engine searches for, and the technician-days left out. */
    public record Built(BookingSnapshot snapshot, BoundedBookingSearch.Request request, List<SkippedTechnicianDay> skipped) {
        public Built { skipped = Required.value(List.copyOf(skipped)); }
    }

    /** The job itself cannot be placed, so no offer can be made for it. */
    public static final class JobUnlocatable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        JobUnlocatable() { super("No coordinates and no certain address match for the job location; send coordinates to book it"); }
    }

    /**
     * Facts that contradict each other. The host's sequence and planned starts must put a technician-day's appointments
     * in the same order, because the booking engine keeps that order and the host writes both.
     */
    public static final class Contradictory extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Contradictory(String message) { super(message); }
    }

    /**
     * A technician-day whose own location, or one of whose appointments' locations, cannot be located with certainty is
     * left out, as in the daily path, so one bad address never stops the rest of the horizon. The scheduler's own
     * policy settings come in {@code policy}; the client's fairness budget replaces the shared one.
     */
    public static Built build(BookingOffersRequest request, SchedulingPolicy.Rules policy, String routingIdentity, Instant capturedAt,
                              DailyPreparation.AddressLocator locator) {
        PublicTypes.Snapshot snapshot = request.snapshot();
        Map<PublicTypes.Address, @Nullable RoadPoint> located = new HashMap<>();
        RoadPoint jobPoint = point(request.job().location(), locator, located);
        if (jobPoint == null) throw new JobUnlocatable();
        Map<String, PublicTypes.Technician> technicians = new HashMap<>();
        for (PublicTypes.Technician technician : snapshot.technicians()) technicians.put(technician.id(), technician);
        Map<PublicTypes.Key, List<Appointment>> appointments = new HashMap<>();
        for (Appointment appointment : snapshot.appointments()) appointments.computeIfAbsent(appointment.key(), _ -> new ArrayList<>()).add(appointment);
        Map<LocalDate, Map<String, BookingSnapshot.Technician>> dayTechnicians = new TreeMap<>();
        Map<LocalDate, Map<String, BookingSnapshot.Visit>> dayVisits = new TreeMap<>();
        Map<LocalDate, Map<String, List<String>>> dayRoutes = new TreeMap<>();
        for (LocalDate date = request.horizon().firstDate(); !date.isAfter(request.horizon().lastDate()); date = Required.value(date.plusDays(1))) {
            dayTechnicians.put(date, new TreeMap<>()); dayVisits.put(date, new TreeMap<>()); dayRoutes.put(date, new TreeMap<>());
        }
        List<SkippedTechnicianDay> skipped = new ArrayList<>();
        for (TechnicianDay day : snapshot.technicianDays()) {
            List<Appointment> listed = appointments.get(day.key());
            List<Appointment> onDay = ordered(day, listed == null ? Required.value(List.<Appointment>of()) : listed);
            List<String> unresolved = new ArrayList<>();
            RoadPoint departure = point(day.start(), locator, located), returnTo = point(day.end(), locator, located);
            if (departure == null) unresolved.add("start location");
            if (returnTo == null) unresolved.add("end location");
            Map<String, RoadPoint> visitPoints = new TreeMap<>();
            for (Appointment appointment : onDay) {
                RoadPoint point = point(appointment.location(), locator, located);
                if (point == null) unresolved.add("appointment " + appointment.id()); else visitPoints.put(appointment.id(), point);
            }
            if (departure == null || returnTo == null || !unresolved.isEmpty()) {
                skipped.add(new SkippedTechnicianDay(day.technicianId(), day.serviceDate(), PublicResponses.SkipReason.LOCATION_UNRESOLVED,
                        "No coordinates and no certain address match for " + String.join(", ", unresolved) + "; send coordinates to offer this technician-day"));
                continue;
            }
            PublicTypes.Technician technician = Required.value(technicians.get(day.technicianId()), "technician for " + day.key());
            List<TechRoute.Unavailable> absences = new ArrayList<>();
            for (PublicTypes.Window absence : day.absences()) absences.add(new TechRoute.Unavailable(absence.start(), absence.end()));
            Required.value(dayTechnicians.get(day.serviceDate()), "horizon date").put(day.technicianId(), new BookingSnapshot.Technician(day.technicianId(),
                    day.shift().start(), day.shift().end(), day.maxPaidMinutes(), 0, Required.value(Set.copyOf(technician.qualifications())),
                    absences, departure, returnTo, version(day.lastModified())));
            List<String> route = new ArrayList<>();
            for (Appointment appointment : onDay) {
                Required.value(dayVisits.get(day.serviceDate()), "horizon date").put(appointment.id(), new BookingSnapshot.Visit(appointment.id(),
                        appointment.id(), appointment.serviceId(), appointment.window().start(), appointment.window().end(), appointment.durationMinutes(),
                        Required.value(visitPoints.get(appointment.id()), "appointment location"), appointment.technicianId(), appointment.plannedStart(), false));
                route.add(appointment.id());
            }
            Required.value(dayRoutes.get(day.serviceDate()), "horizon date").put(day.technicianId(), Required.value(List.copyOf(route)));
        }
        Map<LocalDate, BookingSnapshot.Day> days = new TreeMap<>();
        for (var entry : dayTechnicians.entrySet()) {
            LocalDate date = Required.value(entry.getKey());
            days.put(date, new BookingSnapshot.Day(Required.value(entry.getValue()), Required.value(dayVisits.get(date)),
                    new BookingSnapshot.Arrangement(Required.value(dayRoutes.get(date))), 0,
                    new BookingSnapshot.Roads(Required.value(Map.of()), Required.value(Set.of()))));
        }
        PublicTypes.Rates rates = snapshot.rates();
        var bookingRates = new BookingSnapshot.Rates(rates.regularHourly(), rates.overtimeHourly(), rates.mileagePerMile(), rates.travelBufferPct(),
                rates.travelBufferMinutes());
        String configuration = DailyProposals.sha256(dev.waterflex.scheduler.CalculationJson.write(snapshot));
        var built = new BookingSnapshot(snapshot.metroId(), capturedAt, capturedAt, configuration, routingIdentity,
                DailyProposals.rules(policy, snapshot.policy()), bookingRates, days, Required.value(days.keySet()));
        var job = request.job();
        return new Built(built, new BoundedBookingSearch.Request(job.id(), job.serviceId(), job.durationMinutes(), jobPoint), skipped);
    }

    /**
     * A technician-day's appointments in the host's sequence order, which must also be their planned start order (ties
     * by appointment ID), the order the booking engine reads persisted assignments in.
     */
    private static List<Appointment> ordered(TechnicianDay day, List<Appointment> appointments) {
        TreeMap<Integer, Appointment> bySequence = new TreeMap<>();
        for (Appointment appointment : appointments) bySequence.put(appointment.sequence(), appointment);
        TreeMap<Instant, TreeMap<String, Appointment>> byStart = new TreeMap<>();
        for (Appointment appointment : appointments) byStart.computeIfAbsent(appointment.plannedStart(), _ -> new TreeMap<>()).put(appointment.id(), appointment);
        List<Appointment> sequenced = Required.value(List.copyOf(bySequence.values()));
        List<Appointment> started = new ArrayList<>();
        for (TreeMap<String, Appointment> tied : byStart.values()) started.addAll(tied.values());
        if (!sequenced.equals(started))
            throw new Contradictory("Technician-day " + day.technicianId() + " on " + day.serviceDate() + " has appointments whose sequence and plannedStart disagree");
        return sequenced;
    }

    /** The host's last-modified instant as nanoseconds since the epoch, the booking engine's technician schedule version. */
    static long version(Instant lastModified) {
        if (lastModified.isBefore(Instant.EPOCH)) throw new IllegalArgumentException("technicianDay.lastModified must not be before 1970");
        return Math.addExact(Math.multiplyExact(lastModified.getEpochSecond(), 1_000_000_000L), lastModified.getNano());
    }

    private static @Nullable RoadPoint point(Location location, DailyPreparation.AddressLocator locator, Map<PublicTypes.Address, @Nullable RoadPoint> located) {
        Double lat = location.lat(), lng = location.lng();
        if (lat != null && lng != null) return new RoadPoint(lat, lng);
        PublicTypes.Address address = Required.value(location.address(), "address for a location without coordinates");
        if (!located.containsKey(address)) located.put(address, locator.locate(address));
        return located.get(address);
    }
}
