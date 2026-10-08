package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.ReservationOffers;
import dev.waterflex.scheduler.api.PublicTypes.Key;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.Snapshot;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import dev.waterflex.scheduler.api.PublicTypes.Window;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Public request bodies. Read them only through {@link #read}, which applies the strict external JSON rules. */
public final class PublicRequests {
    private PublicRequests() { }

    /** Unknown fields, duplicate keys, numeric money and missing required values all fail with IllegalArgumentException. */
    public static <T extends Record> T read(String json, Class<T> type) {
        return CalculationJson.readOmittable(json, type);
    }

    public record DailyProposalRequest(String requestId, LocalDate serviceDate, Snapshot snapshot) {
        public DailyProposalRequest {
            Input.requestId(requestId);
            Input.present(serviceDate, "serviceDate");
            Input.present(snapshot, "snapshot");
            for (TechnicianDay day : snapshot.technicianDays())
                if (!day.serviceDate().equals(serviceDate)) throw new IllegalArgumentException("Technician-day " + day.key() + " is outside serviceDate");
        }
    }

    public record Absence(String technicianId, LocalDate serviceDate, Window window) {
        public Absence {
            Input.id(technicianId, "absence.technicianId");
            Input.present(serviceDate, "absence.serviceDate");
            Input.present(window, "absence.window");
        }
    }

    public record RepairProposalRequest(String requestId, Absence absence, Snapshot snapshot) {
        public RepairProposalRequest {
            Input.requestId(requestId);
            Input.present(absence, "absence");
            Input.present(snapshot, "snapshot");
            if (!snapshot.covers(new Key(absence.technicianId(), absence.serviceDate())))
                throw new IllegalArgumentException("Absent technician-day is not in the snapshot");
        }
    }

    public record Job(String id, String serviceId, Integer durationMinutes, Location location) {
        public Job {
            Input.id(id, "job.id");
            Input.id(serviceId, "job.serviceId");
            Input.integer(durationMinutes, "job.durationMinutes", 1, 720);
            Input.present(location, "job.location");
        }
    }

    /** The dates one booking search covers, both included; at most {@link #MAX_DAYS} of them. */
    public record Horizon(LocalDate firstDate, LocalDate lastDate) {
        public static final int MAX_DAYS = 21;

        public Horizon {
            Input.present(firstDate, "horizon.firstDate");
            Input.present(lastDate, "horizon.lastDate");
            if (lastDate.isBefore(firstDate)) throw new IllegalArgumentException("horizon.lastDate is before horizon.firstDate");
            if (java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) >= MAX_DAYS)
                throw new IllegalArgumentException("A horizon covers at most " + MAX_DAYS + " dates");
        }

        boolean contains(LocalDate date) { return !date.isBefore(firstDate) && !date.isAfter(lastDate); }
    }

    /** {@code offerLimit} is the client's own cap on offers per search, 1 to {@link ReservationOffers#MAX_OFFERS}; never assumed. */
    public record BookingOffersRequest(String requestId, Job job, Horizon horizon, Integer offerLimit, Snapshot snapshot) {
        public BookingOffersRequest {
            Input.requestId(requestId);
            Input.present(job, "job");
            Input.present(horizon, "horizon");
            Input.integer(offerLimit, "offerLimit", 1, ReservationOffers.MAX_OFFERS);
            Input.present(snapshot, "snapshot");
            for (TechnicianDay day : snapshot.technicianDays()) {
                if (!horizon.contains(day.serviceDate())) throw new IllegalArgumentException("Technician-day " + day.key() + " is outside the horizon");
                if (day.technicianId().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is also a technician ID");
            }
            for (PublicTypes.Appointment appointment : snapshot.appointments())
                if (appointment.id().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is already an appointment in the snapshot");
        }
    }

    public record RequestOnly(String requestId) {
        public RequestOnly { Input.requestId(requestId); }
    }

    /** Current host timestamps for every technician-day a proposal or offer covers. */
    public record CommitRequest(String requestId, List<TechnicianDayVersion> technicianDays) {
        public CommitRequest {
            Input.requestId(requestId);
            technicianDays = Input.list(technicianDays, "technicianDays");
            if (technicianDays.isEmpty()) throw new IllegalArgumentException("technicianDays must not be empty");
            Set<Key> keys = new HashSet<>();
            for (TechnicianDayVersion day : technicianDays)
                if (!keys.add(day.key())) throw new IllegalArgumentException("Duplicate technician-day " + day.key());
        }
    }
}
