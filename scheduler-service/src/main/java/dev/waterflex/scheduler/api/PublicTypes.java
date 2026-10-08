package dev.waterflex.scheduler.api;

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

    /** Coordinates are authoritative when present; an address alone is geocoded by the scheduler. */
    public record Location(@Nullable Double lat, @Nullable Double lng, @Nullable Address address) {
        public Location {
            if ((lat == null) != (lng == null)) throw new IllegalArgumentException("lat and lng must be given together");
            if (lat == null && address == null) throw new IllegalArgumentException("Location needs coordinates or an address");
            if (lat != null && (!Double.isFinite(lat) || Math.abs(lat) > 90)) throw new IllegalArgumentException("Invalid lat");
            if (lng != null && (!Double.isFinite(lng) || Math.abs(lng) > 180)) throw new IllegalArgumentException("Invalid lng");
        }
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
                              Integer durationMinutes, Window window, Location location, Integer sequence) {
        public Appointment {
            Input.id(id, "appointment.id");
            Input.id(technicianId, "appointment.technicianId");
            Input.present(serviceDate, "appointment.serviceDate");
            Input.id(serviceId, "appointment.serviceId");
            Input.integer(durationMinutes, "appointment.durationMinutes", 1, 720);
            Input.present(window, "appointment.window");
            Input.present(location, "appointment.location");
            Input.integer(sequence, "appointment.sequence", 0, Integer.MAX_VALUE);
        }

        public Key key() { return new Key(technicianId, serviceDate); }
    }

    /** One technician on one service date: the unit of optimistic concurrency. */
    public record Key(String technicianId, LocalDate serviceDate) { }

    /**
     * Every fact a calculation needs. Cross-references are checked here so a calculation never meets a dangling or
     * duplicated fact.
     */
    public record Snapshot(String metroId, String timeZone, Rates rates, List<Technician> technicians,
                           List<TechnicianDay> technicianDays, List<Appointment> appointments) {
        public Snapshot {
            Input.id(metroId, "snapshot.metroId");
            Input.zone(timeZone);
            Input.present(rates, "snapshot.rates");
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
