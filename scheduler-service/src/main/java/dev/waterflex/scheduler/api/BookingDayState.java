package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BookingSnapshot;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The scheduler's own booking state for one metro-day: the common arrangement of the host's appointments and every
 * active hold, and, per technician, the host timestamp and appointment order the arrangement was reconciled with.
 * Stored as JSON in the thin store and validated again on every read. It holds no master data beyond what a hold needs
 * to be placed again: the held job's service, window, duration and coordinates.
 *
 * @param technicians every technician on the day, by ID
 * @param holds every active hold on the day, by hold ID
 */
public record BookingDayState(Map<String, TechnicianState> technicians, Map<String, HoldState> holds) {
    /**
     * @param version  the host's last-modified instant as nanoseconds since 1970 (see {@link RequestBooking#version})
     * @param expected the host appointments, in order, that WaterFlex Software has or has been told to write
     * @param route    the common arrangement: the same appointments with pending moves applied, plus holds
     */
    public record TechnicianState(Long version, List<String> expected, List<String> route) {
        public TechnicianState {
            if (Input.present(version, "technician version") < 0) throw new IllegalArgumentException("Negative technician version");
            expected = Input.list(expected, "expected route");
            route = Input.list(route, "route");
        }
    }

    /** A held slot: what is needed to place the job again, and who holds it until when. */
    public record HoldState(String jobId, String offerId, String offerSetId, Instant expiresAt, String serviceId, Instant windowStart, Instant windowEnd,
                            Integer durationMinutes, Double lat, Double lng, Instant plannedStart, String technicianId) {
        public HoldState {
            Input.id(jobId, "hold.jobId");
            Input.id(offerId, "hold.offerId");
            Input.id(offerSetId, "hold.offerSetId");
            Input.present(expiresAt, "hold.expiresAt");
            Input.id(serviceId, "hold.serviceId");
            Input.present(windowStart, "hold.windowStart");
            Input.present(windowEnd, "hold.windowEnd");
            if (!windowStart.isBefore(windowEnd)) throw new IllegalArgumentException("Hold window is empty");
            Input.integer(durationMinutes, "hold.durationMinutes", 1, 720);
            new RoadPoint(Input.present(lat, "hold.lat"), Input.present(lng, "hold.lng"));
            Input.present(plannedStart, "hold.plannedStart");
            Input.id(technicianId, "hold.technicianId");
        }

        BookingSnapshot.Visit visit(String holdId) {
            return new BookingSnapshot.Visit(holdId, jobId, serviceId, windowStart, windowEnd, durationMinutes, new RoadPoint(lat, lng),
                    technicianId, plannedStart, true);
        }
    }

    public BookingDayState {
        technicians = Required.value(java.util.Collections.unmodifiableMap(new TreeMap<>(Input.present(technicians, "technicians"))));
        holds = Required.value(java.util.Collections.unmodifiableMap(new TreeMap<>(Input.present(holds, "holds"))));
        Set<String> placed = new HashSet<>(), expected = new HashSet<>();
        for (var entry : technicians.entrySet()) {
            Input.id(entry.getKey(), "technician ID");
            TechnicianState technician = Required.value(entry.getValue());
            for (String id : technician.expected()) {
                if (holds.containsKey(id)) throw new IllegalArgumentException("A hold is in an expected host route");
                if (!expected.add(id)) throw new IllegalArgumentException("Appointment " + id + " is expected twice");
            }
            for (String id : technician.route()) if (!placed.add(id)) throw new IllegalArgumentException("Visit " + id + " is placed twice");
        }
        Set<String> appointments = new HashSet<>(placed);
        appointments.removeAll(holds.keySet());
        if (!appointments.equals(expected)) throw new IllegalArgumentException("The arrangement and the expected host routes cover different appointments");
        if (!placed.containsAll(holds.keySet())) throw new IllegalArgumentException("A hold is not placed in the arrangement");
        for (var entry : holds.entrySet()) {
            String technician = Required.value(entry.getValue()).technicianId();
            if (!Required.value(technicians.get(technician), "hold technician").route().contains(entry.getKey()))
                throw new IllegalArgumentException("Hold " + entry.getKey() + " is not on its technician's route");
        }
    }

    /** The state of a day with no holds and no pending moves: exactly the host's routes. */
    static BookingDayState of(BookingSnapshot.Day host) {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        host.technicians().forEach((id, technician) -> {
            List<String> route = Required.value(host.baseline().routes().get(id), "host route");
            technicians.put(id, new TechnicianState(technician.scheduleVersion(), route, route));
        });
        return new BookingDayState(technicians, Required.value(Map.of()));
    }

    /** The same day without these holds. The moves made for them stay, as in the portal. */
    BookingDayState without(Set<String> ended) {
        Map<String, TechnicianState> remaining = new TreeMap<>();
        technicians.forEach((id, technician) -> remaining.put(id, new TechnicianState(technician.version(), technician.expected(),
                Required.value(technician.route().stream().filter(visit -> !ended.contains(visit)).toList()))));
        Map<String, HoldState> kept = new TreeMap<>(holds);
        kept.keySet().removeAll(ended);
        return new BookingDayState(remaining, kept);
    }

    /** The common arrangement, holds included. */
    BookingSnapshot.Arrangement arrangement() {
        Map<String, List<String>> routes = new TreeMap<>();
        technicians.forEach((id, technician) -> routes.put(id, new ArrayList<>(technician.route())));
        return new BookingSnapshot.Arrangement(routes);
    }
}
