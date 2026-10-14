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
import org.jspecify.annotations.Nullable;

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
     * @param version    the host's last-modified instant as nanoseconds since 1970 (see {@link RequestBooking#version})
     * @param expected   the host appointments, in order, that WaterFlex Software has or has been told to write
     * @param route      the common arrangement: the same appointments with pending moves applied, plus holds
     * @param superseded earlier versions of this technician-day that the state has moved past, oldest first, at most
     *                   {@link #HISTORY}; a snapshot that carries one is older than one already used. Empty for a
     *                   technician-day first seen at {@code version}. Null means a state written before versions were
     *                   remembered: nothing is known about earlier versions.
     * @param unwritten  set by a confirm whose receipt changes this technician-day, until the host writes it: null when
     *                   no receipt is waiting to be written
     */
    public record TechnicianState(Long version, List<String> expected, List<String> route, @Nullable List<Long> superseded,
                                  @Nullable Unwritten unwritten) {
        /** How many superseded versions are remembered per technician-day. */
        static final int HISTORY = 32;

        public TechnicianState {
            if (Input.present(version, "technician version") < 0) throw new IllegalArgumentException("Negative technician version");
            expected = Input.list(expected, "expected route");
            route = Input.list(route, "route");
            if (superseded != null) {
                superseded = Input.list(superseded, "superseded versions");
                if (superseded.size() > HISTORY) throw new IllegalArgumentException("More than " + HISTORY + " superseded versions");
                Set<Long> distinct = new HashSet<>();
                for (Long earlier : superseded) {
                    if (Required.value(earlier) < 0) throw new IllegalArgumentException("Negative superseded version");
                    if (earlier.equals(version)) throw new IllegalArgumentException("The current version is listed as superseded");
                    if (!distinct.add(earlier)) throw new IllegalArgumentException("Superseded version " + earlier + " is listed twice");
                }
            }
        }

        /** The same technician-day with another route, keeping what it knows of the host's versions. */
        TechnicianState routed(List<String> other) {
            return new TechnicianState(version, expected, other, superseded, unwritten);
        }

        /**
         * Whether a host technician-day at {@code hostVersion} with {@code hostRoute} predates records the scheduler
         * already used: a version it moved past, or the route before a receipt the host has not written yet, while that
         * receipt still counts.
         */
        boolean outdatedBy(long hostVersion, List<String> hostRoute, Instant now) {
            if (supersedes(hostVersion)) return true;
            Unwritten pending = unwritten;
            return pending != null && hostVersion == version && pending.until().isAfter(now) && pending.before().equals(hostRoute);
        }

        /** Whether the host's version is one this technician-day has already moved past. */
        boolean supersedes(long hostVersion) {
            List<Long> earlier = superseded;
            return earlier != null && earlier.contains(hostVersion);
        }

        /**
         * The superseded versions once the technician-day moves to {@code next}: the current version joins them (the
         * oldest is forgotten beyond {@link #HISTORY}). Unchanged, and still null for an old state, when it does not move.
         */
        @Nullable List<Long> movedTo(long next) {
            if (next == version) return superseded;
            List<Long> known = superseded;
            List<Long> earlier = new ArrayList<>(known == null ? Required.value(List.<Long>of()) : known);
            earlier.remove(Long.valueOf(next));
            earlier.add(version);
            while (earlier.size() > HISTORY) earlier.removeFirst();
            return Required.value(List.copyOf(earlier));
        }
    }

    /**
     * A held slot: what is needed to place the job again, who holds it until when, and where the job's coordinates came
     * from. A null source means a hold written before sources were recorded; it is never read as HOST.
     */
    public record HoldState(String jobId, String offerId, String offerSetId, Instant expiresAt, String serviceId, Instant windowStart, Instant windowEnd,
                            Integer durationMinutes, Double lat, Double lng, Instant plannedStart, String technicianId,
                            PublicTypes.@Nullable CoordinateSource source) {
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

        /** The same hold on another technician's route. */
        HoldState on(String technician) {
            return new HoldState(jobId, offerId, offerSetId, expiresAt, serviceId, windowStart, windowEnd, durationMinutes, lat, lng, plannedStart, technician, source);
        }

        BookingSnapshot.Visit visit(String holdId) {
            return new BookingSnapshot.Visit(holdId, jobId, serviceId, windowStart, windowEnd, durationMinutes, new RoadPoint(lat, lng),
                    technicianId, plannedStart, true);
        }
    }

    /**
     * A confirm receipt the host has not written yet. Until the host writes it (its timestamp for the technician-day
     * moves) or {@code until} passes, a snapshot with the unchanged timestamp and exactly the {@code before} route was
     * built before the receipt was written, so it is stale rather than a dispatcher's change. After {@code until} the
     * receipt is taken as never written, and such a snapshot is reconciled as a change again.
     *
     * @param before the host route the scheduler expected before the receipt
     * @param until  when the receipt stops counting
     */
    public record Unwritten(List<String> before, Instant until) {
        public Unwritten {
            before = Input.list(before, "unwritten.before");
            Input.present(until, "unwritten.until");
        }
    }

    /** The superseded versions of a technician-day seen for the first time: none. */
    static final List<Long> FIRST_SEEN = Required.value(List.of());

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

    /**
     * The state of a day with no holds and no pending moves: exactly the host's routes. Each technician keeps the
     * versions {@code previous} had moved past, and its version there when the host has moved on from it; a technician
     * {@code previous} does not have starts with none.
     */
    static BookingDayState of(BookingSnapshot.Day host, @Nullable BookingDayState previous) {
        Map<String, TechnicianState> technicians = new TreeMap<>();
        host.technicians().forEach((id, technician) -> {
            List<String> route = Required.value(host.baseline().routes().get(id), "host route");
            TechnicianState before = previous == null ? null : previous.technicians().get(id);
            technicians.put(id, new TechnicianState(technician.scheduleVersion(), route, route,
                    before == null ? FIRST_SEEN : before.movedTo(technician.scheduleVersion()), null));
        });
        return new BookingDayState(technicians, Required.value(Map.of()));
    }

    /** The same day without these holds. The moves made for them stay, as in the portal. */
    BookingDayState without(Set<String> ended) {
        Map<String, TechnicianState> remaining = new TreeMap<>();
        technicians.forEach((id, technician) -> remaining.put(id,
                technician.routed(Required.value(technician.route().stream().filter(visit -> !ended.contains(visit)).toList()))));
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
