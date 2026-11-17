package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.BoundedBookingSearch;
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

    /** The snapshot holds the absence's date only, as a daily request does, and includes the absent technician-day. */
    /** POST /api/v1/routes/evaluate: one metro day's routes, timed in their current order. Nothing is stored. */
    public record RouteEvaluationRequest(LocalDate serviceDate, Snapshot snapshot) {
        public RouteEvaluationRequest {
            Input.present(serviceDate, "serviceDate");
            Input.present(snapshot, "snapshot");
            for (TechnicianDay day : snapshot.technicianDays())
                if (!day.serviceDate().equals(serviceDate)) throw new IllegalArgumentException("Technician-day " + day.key() + " is outside serviceDate");
        }
    }

    /**
     * The job a route insertion places (spec 1.12.0). {@code window} is the arrival window already promised to the
     * customer, sent when the job is an appointment being moved; null or absent for a job that has no promise yet.
     */
    public record InsertionJob(String id, String serviceId, Integer durationMinutes, Location location,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @org.jspecify.annotations.Nullable Window window) {
        public InsertionJob {
            Input.id(id, "job.id");
            Input.id(serviceId, "job.serviceId");
            Input.integer(durationMinutes, "job.durationMinutes", 1, 720);
            Input.present(location, "job.location");
        }
    }

    /**
     * POST /api/v1/routes/insertions (spec 1.12.0): every place one job could go in the snapshot's routes. The snapshot
     * does not contain the job, and may cover several service dates. {@code windowMinutes} is the client's arrival
     * window length and {@code startStepMinutes} the spacing of the planned starts offered; neither is assumed.
     * {@code routes} asks for the timed stops of every route as well as its totals.
     */
    public record RouteInsertionRequest(Snapshot snapshot, InsertionJob job, Integer windowMinutes, Integer startStepMinutes, Boolean routes) {
        public static final int MAX_TECHNICIAN_DAYS = 200;
        public static final int MIN_WINDOW_MINUTES = 60, MAX_WINDOW_MINUTES = 480;

        public RouteInsertionRequest {
            Input.present(snapshot, "snapshot");
            Input.present(job, "job");
            if (Input.integer(windowMinutes, "windowMinutes", MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES) % 60 != 0)
                throw new IllegalArgumentException("windowMinutes must be whole hours");
            int step = Input.present(startStepMinutes, "startStepMinutes");
            if (step != 15 && step != 30 && step != 60) throw new IllegalArgumentException("startStepMinutes must be 15, 30 or 60");
            Input.present(routes, "routes");
            if (snapshot.technicianDays().size() > MAX_TECHNICIAN_DAYS)
                throw new IllegalArgumentException("A route insertion covers at most " + MAX_TECHNICIAN_DAYS + " technician-days");
            for (TechnicianDay day : snapshot.technicianDays())
                if (day.technicianId().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is also a technician ID");
            for (PublicTypes.Appointment appointment : snapshot.appointments())
                if (appointment.id().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is already an appointment in the snapshot");
        }
    }

    public record RepairProposalRequest(String requestId, Absence absence, Snapshot snapshot) {
        public RepairProposalRequest {
            Input.requestId(requestId);
            Input.present(absence, "absence");
            Input.present(snapshot, "snapshot");
            if (!snapshot.covers(new Key(absence.technicianId(), absence.serviceDate())))
                throw new IllegalArgumentException("Absent technician-day is not in the snapshot");
            new DailyProposalRequest(requestId, absence.serviceDate(), snapshot);
        }

        /** The same facts as a daily request for the absence's date. */
        public DailyProposalRequest day() { return new DailyProposalRequest(requestId, absence.serviceDate(), snapshot); }
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

    /**
     * {@code offerLimit} is the client's own cap on offers per search, 1 to {@link ReservationOffers#MAX_OFFERS}, and
     * {@code windowHours} its arrival window length in whole hours, 1 to 8; neither is assumed.
     */
    public record BookingOffersRequest(String requestId, Job job, Horizon horizon, Integer offerLimit, Integer windowHours, Snapshot snapshot) {
        public static final int MIN_WINDOW_HOURS = BoundedBookingSearch.Request.MIN_WINDOW_MINUTES / 60;
        public static final int MAX_WINDOW_HOURS = BoundedBookingSearch.Request.MAX_WINDOW_MINUTES / 60;
        public BookingOffersRequest {
            Input.requestId(requestId);
            Input.present(job, "job");
            Input.present(horizon, "horizon");
            Input.integer(offerLimit, "offerLimit", 1, ReservationOffers.MAX_OFFERS);
            Input.integer(windowHours, "windowHours", MIN_WINDOW_HOURS, MAX_WINDOW_HOURS);
            Input.present(snapshot, "snapshot");
            for (TechnicianDay day : snapshot.technicianDays()) {
                if (!horizon.contains(day.serviceDate())) throw new IllegalArgumentException("Technician-day " + day.key() + " is outside the horizon");
                if (day.technicianId().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is also a technician ID");
            }
            for (PublicTypes.Appointment appointment : snapshot.appointments())
                if (appointment.id().equals(job.id())) throw new IllegalArgumentException("job.id " + job.id() + " is already an appointment in the snapshot");
        }
    }

    /** The host's current snapshot of the hold's metro and date; every technician-day is on that one date. */
    public record ConfirmRequest(String requestId, Snapshot snapshot) {
        public ConfirmRequest {
            Input.requestId(requestId);
            Input.present(snapshot, "snapshot");
            Set<LocalDate> dates = new HashSet<>();
            for (TechnicianDay day : snapshot.technicianDays()) dates.add(day.serviceDate());
            if (dates.size() > 1) throw new IllegalArgumentException("A confirm snapshot covers one date, the hold's");
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
