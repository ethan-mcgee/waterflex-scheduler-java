package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.optimizer.DailyCalculation;
import dev.waterflex.scheduler.optimizer.DailySearch;
import dev.waterflex.scheduler.optimizer.SchedulingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Shapes shared by the public requests and responses. Component names and nullability must match the schemas in
 * docs/api/openapi-v1.yaml; PublicApiContractTest enforces this.
 */
public final class PublicTypes {
    private PublicTypes() { }

    public record Address(String line1, @Nullable String line2, String city, String state, String postalCode) {
        public Address {
            text(line1, "address.line1");
            text(city, "address.city");
            if (Input.present(state, "address.state").length() != 2) throw new IllegalArgumentException("address.state must be two letters");
            if (text(postalCode, "address.postalCode").length() < 5) throw new IllegalArgumentException("address.postalCode is too short");
        }

        private static String text(@Nullable String value, String field) {
            String text = Input.present(value, field);
            if (text.isBlank()) throw new IllegalArgumentException("Blank " + field);
            return text;
        }
    }

    /** Where a located point's coordinates came from: the host's own, or the scheduler's address lookup. */
    public enum CoordinateSource { HOST, NOMINATIM }

    /** Coordinates are authoritative when present; an address alone is geocoded by the scheduler. */
    public record Location(@Nullable Double lat, @Nullable Double lng, @Nullable Address address) {
        public Location {
            if ((lat == null) != (lng == null)) throw new IllegalArgumentException("lat and lng must be given together");
            if (lat == null && address == null) throw new IllegalArgumentException("Location needs coordinates or an address");
            if (lat != null && (!Double.isFinite(lat) || Math.abs(lat) > 90)) throw new IllegalArgumentException("Invalid lat");
            if (lng != null && (!Double.isFinite(lng) || Math.abs(lng) > 180)) throw new IllegalArgumentException("Invalid lng");
        }

        /**
         * The source of this location's point once it is located: the host's coordinates when sent, otherwise the
         * scheduler's address lookup, the only way an address-only location is ever located.
         */
        public CoordinateSource source() { return lat == null ? CoordinateSource.NOMINATIM : CoordinateSource.HOST; }
    }

    public record Window(Instant start, Instant end) {
        public Window {
            Input.present(start, "window.start");
            Input.present(end, "window.end");
            if (!start.isBefore(end)) throw new IllegalArgumentException("window.start must be before window.end");
        }
    }

    public record Rates(BigDecimal regularHourly, BigDecimal overtimeHourly, BigDecimal mileagePerMile,
                        BigDecimal travelBufferPct, Integer travelBufferMinutes) {
        public Rates {
            Input.nonNegative(regularHourly, "regularHourly");
            Input.nonNegative(overtimeHourly, "overtimeHourly");
            Input.nonNegative(mileagePerMile, "mileagePerMile");
            Input.nonNegative(travelBufferPct, "travelBufferPct");
            Input.integer(travelBufferMinutes, "travelBufferMinutes", 0, 120);
        }
    }

    /**
     * The client's own scheduling choices. A fairness budget of zero means fairness may never add cost. The daily
     * search method and its cost and fairness search seconds apply to daily and repair proposals; a fairness time of
     * zero skips the fairness phase, and a repair (one phase) may spend both times.
     * Every policy rule is the client's own; the scheduler holds none. {@code bookingDeadlineMs} bounds a booking search
     * or confirm end to end at the scheduler; when the insertion pass finds more distinct regular arrival windows than
     * {@code regularWindowThreshold}, the refinement is optional and kept short, otherwise it may use the whole deadline.
     */
    /**
     * A client's policy. {@code bookingRefinementMs} is optional (spec 1.3.0): the client's budget for a booking search's
     * optional refinement, 0 to skip it; null means the operator's configured {@code booking.search.refinement-ms}.
     */
    public record Policy(BigDecimal fairnessBudget, DailySearch dailySearch, Integer costSearchSeconds, Integer fairnessSearchSeconds,
                         Integer bookingDeadlineMs, Integer regularWindowThreshold, @Nullable Integer bookingRefinementMs) {
        /** Bounds of a client's booking deadline, in milliseconds; the upper one is the latency target. */
        public static final int MIN_BOOKING_DEADLINE_MS = 1000, MAX_BOOKING_DEADLINE_MS = 5000;
        /** A threshold above every window a 21-date horizon of whole-hour windows can hold, so any real choice fits. */
        public static final int MAX_REGULAR_WINDOW_THRESHOLD = 1000;
        /** The largest optional refinement budget a client may ask for, in milliseconds (3000 since spec 1.5.0, 1000 before). */
        public static final int MAX_BOOKING_REFINEMENT_MS = dev.waterflex.scheduler.BookingCalculation.MAX_REFINEMENT_MS;

        public Policy {
            if (Input.nonNegative(fairnessBudget, "policy.fairnessBudget").compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("policy.fairnessBudget must be at most 1");
            Input.present(dailySearch, "policy.dailySearch");
            int cost = Input.integer(costSearchSeconds, "policy.costSearchSeconds", DailyCalculation.Allocation.MIN_COST_SECONDS, DailyCalculation.Allocation.MAX_SEARCH_SECONDS);
            int fairness = Input.integer(fairnessSearchSeconds, "policy.fairnessSearchSeconds", 0, DailyCalculation.Allocation.MAX_SEARCH_SECONDS);
            if (cost + fairness > DailyCalculation.Allocation.MAX_SEARCH_SECONDS)
                throw new IllegalArgumentException("policy.costSearchSeconds and policy.fairnessSearchSeconds together must be at most "
                        + DailyCalculation.Allocation.MAX_SEARCH_SECONDS);
            Input.integer(bookingDeadlineMs, "policy.bookingDeadlineMs", MIN_BOOKING_DEADLINE_MS, MAX_BOOKING_DEADLINE_MS);
            Input.integer(regularWindowThreshold, "policy.regularWindowThreshold", 0, MAX_REGULAR_WINDOW_THRESHOLD);
            if (bookingRefinementMs != null) Input.integer(bookingRefinementMs, "policy.bookingRefinementMs", 0, MAX_BOOKING_REFINEMENT_MS);
        }

        /** The calculation's policy rules, all from this client. */
        public SchedulingPolicy.Rules rules() {
            return new SchedulingPolicy.Rules(regularWindowThreshold, fairnessBudget, bookingDeadlineMs);
        }
    }

    public record Technician(String id, List<String> qualifications) {
        public Technician {
            Input.id(id, "technician.id");
            qualifications = Input.uniqueIds(qualifications, "technician.qualifications");
        }
    }

    public record TechnicianDay(String technicianId, LocalDate serviceDate, Instant lastModified, Window shift,
                                List<Window> absences, Location start, Location end, Integer maxPaidMinutes) {
        public TechnicianDay {
            Input.id(technicianId, "technicianDay.technicianId");
            Input.present(serviceDate, "technicianDay.serviceDate");
            Input.present(lastModified, "technicianDay.lastModified");
            Input.present(shift, "technicianDay.shift");
            absences = Input.list(absences, "technicianDay.absences");
            Input.present(start, "technicianDay.start");
            Input.present(end, "technicianDay.end");
            Input.integer(maxPaidMinutes, "technicianDay.maxPaidMinutes", 1, 1440);
        }

        public Key key() { return new Key(technicianId, serviceDate); }
    }

    public record Appointment(String id, String technicianId, LocalDate serviceDate, String serviceId,
                              Integer durationMinutes, Window window, Location location, Integer sequence,
                              Instant plannedStart) {
        public Appointment {
            Input.id(id, "appointment.id");
            Input.id(technicianId, "appointment.technicianId");
            Input.present(serviceDate, "appointment.serviceDate");
            Input.id(serviceId, "appointment.serviceId");
            Input.integer(durationMinutes, "appointment.durationMinutes", 1, 720);
            Input.present(window, "appointment.window");
            Input.present(location, "appointment.location");
            Input.integer(sequence, "appointment.sequence", 0, Integer.MAX_VALUE);
            Input.present(plannedStart, "appointment.plannedStart");
        }

        public Key key() { return new Key(technicianId, serviceDate); }
    }

    /** One technician on one service date: the unit of optimistic concurrency. */
    public record Key(String technicianId, LocalDate serviceDate) { }

    /**
     * Every fact a calculation needs. Cross-references are checked here so a calculation never meets a dangling or
     * duplicated fact.
     */
    public record Snapshot(String metroId, String timeZone, Rates rates, Policy policy, List<Technician> technicians,
                           List<TechnicianDay> technicianDays, List<Appointment> appointments) {
        public Snapshot {
            Input.id(metroId, "snapshot.metroId");
            Input.zone(timeZone);
            Input.present(rates, "snapshot.rates");
            Input.present(policy, "snapshot.policy");
            technicians = Input.list(technicians, "snapshot.technicians");
            technicianDays = Input.list(technicianDays, "snapshot.technicianDays");
            appointments = Input.list(appointments, "snapshot.appointments");
            Set<String> technicianIds = new HashSet<>();
            for (Technician technician : technicians)
                if (!technicianIds.add(technician.id())) throw new IllegalArgumentException("Duplicate technician " + technician.id());
            Set<Key> days = new HashSet<>();
            for (TechnicianDay day : technicianDays) {
                if (!technicianIds.contains(day.technicianId())) throw new IllegalArgumentException("Technician-day for unknown technician " + day.technicianId());
                if (!days.add(day.key())) throw new IllegalArgumentException("Duplicate technician-day " + day.key());
            }
            Set<String> appointmentIds = new HashSet<>();
            Map<Key, Set<Integer>> sequences = new HashMap<>();
            for (Appointment appointment : appointments) {
                if (!appointmentIds.add(appointment.id())) throw new IllegalArgumentException("Duplicate appointment " + appointment.id());
                if (!days.contains(appointment.key())) throw new IllegalArgumentException("Appointment " + appointment.id() + " has no technician-day");
                if (!sequences.computeIfAbsent(appointment.key(), _ -> new HashSet<>()).add(appointment.sequence()))
                    throw new IllegalArgumentException("Duplicate sequence on " + appointment.key());
            }
        }

        public boolean covers(Key key) {
            for (TechnicianDay day : technicianDays) if (day.key().equals(key)) return true;
            return false;
        }
    }

    public record TechnicianDayVersion(String technicianId, LocalDate serviceDate, Instant lastModified) {
        public TechnicianDayVersion {
            Input.id(technicianId, "technicianDay.technicianId");
            Input.present(serviceDate, "technicianDay.serviceDate");
            Input.present(lastModified, "technicianDay.lastModified");
        }

        public Key key() { return new Key(technicianId, serviceDate); }
    }
}
