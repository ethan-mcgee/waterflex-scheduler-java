package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import dev.waterflex.scheduler.api.PublicTypes.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Public response bodies. Constructors enforce the guarantees the contract promises to WaterFlex Software. */
public final class PublicResponses {
    private static final Pattern SHA256 = dev.waterflex.scheduler.Required.value(Pattern.compile("[0-9a-f]{64}"));
    private PublicResponses() { }

    public record WhoAmI(String tenantId) {
        public WhoAmI { Input.present(tenantId, "tenantId"); }
    }

    public record PlannedStop(String appointmentId, Integer sequence, Instant plannedStart, Instant plannedEnd) {
        public PlannedStop {
            Input.id(appointmentId, "appointmentId");
            Input.integer(sequence, "sequence", 0, Integer.MAX_VALUE);
            Input.present(plannedStart, "plannedStart");
            Input.present(plannedEnd, "plannedEnd");
            if (!plannedStart.isBefore(plannedEnd)) throw new IllegalArgumentException("plannedStart must be before plannedEnd");
        }
    }

    public record PlannedRoute(String technicianId, LocalDate serviceDate, List<PlannedStop> stops) {
        public PlannedRoute {
            Input.id(technicianId, "technicianId");
            Input.present(serviceDate, "serviceDate");
            stops = Input.list(stops, "stops");
        }
    }

    public enum Decision { IMPROVED, NO_IMPROVEMENT, REJECTED_BY_POLICY }

    public enum SkipReason { LOCATION_UNRESOLVED }

    /** A technician-day left exactly as it is; it does not appear in the proposal's routes. */
    public record SkippedTechnicianDay(String technicianId, LocalDate serviceDate, SkipReason reason, String message) {
        public SkippedTechnicianDay {
            Input.id(technicianId, "technicianId");
            Input.present(serviceDate, "serviceDate");
            Input.present(reason, "reason");
            if (Input.present(message, "message").isBlank()) throw new IllegalArgumentException("Blank message");
        }
    }

    /** A proposal never carries overtime: the scheduler never assigns it. */
    public record DailyProposal(String proposalId, String inputRevision, Decision decision, String reason, List<PlannedRoute> routes,
                                List<String> unresolvedAppointmentIds, List<SkippedTechnicianDay> skippedTechnicianDays,
                                Long costCents, Integer overtimeMinutes) {
        public DailyProposal {
            Input.id(proposalId, "proposalId");
            if (!SHA256.matcher(Input.present(inputRevision, "inputRevision")).matches()) throw new IllegalArgumentException("inputRevision must be a SHA-256 hex digest");
            Input.present(decision, "decision");
            if (Input.present(reason, "reason").isBlank()) throw new IllegalArgumentException("Blank reason");
            routes = Input.list(routes, "routes");
            unresolvedAppointmentIds = Input.uniqueIds(unresolvedAppointmentIds, "unresolvedAppointmentIds");
            skippedTechnicianDays = Input.list(skippedTechnicianDays, "skippedTechnicianDays");
            Set<String> routed = new HashSet<>();
            for (PlannedRoute route : routes) if (!routed.add(route.technicianId())) throw new IllegalArgumentException("Duplicate route for " + route.technicianId());
            for (SkippedTechnicianDay skipped : skippedTechnicianDays)
                if (!routed.add(skipped.technicianId())) throw new IllegalArgumentException("Technician " + skipped.technicianId() + " is both routed and skipped");
            if (Input.present(costCents, "costCents") < 0) throw new IllegalArgumentException("costCents must not be negative");
            Input.integer(overtimeMinutes, "overtimeMinutes", 0, 0);
        }
    }

    /**
     * The snapshot's routes timed in their current order: every appointment's planned start and end, and whether the
     * day holds every window, shift and paid limit. A day that does not hold has no routes, since it cannot be timed as
     * given. Technician-days that could not be located are skipped, not timed.
     */
    public record RouteEvaluation(Boolean feasible, List<PlannedRoute> routes, List<SkippedTechnicianDay> skippedTechnicianDays,
                                  Long costCents, Integer overtimeMinutes) {
        public RouteEvaluation {
            Input.present(feasible, "feasible");
            routes = Input.list(routes, "routes");
            if (!feasible && !routes.isEmpty()) throw new IllegalArgumentException("A day that does not hold has no timed routes");
            skippedTechnicianDays = Input.list(skippedTechnicianDays, "skippedTechnicianDays");
            Set<String> routed = new HashSet<>();
            for (PlannedRoute route : routes) if (!routed.add(route.technicianId())) throw new IllegalArgumentException("Duplicate route for " + route.technicianId());
            for (SkippedTechnicianDay skipped : skippedTechnicianDays)
                if (!routed.add(skipped.technicianId())) throw new IllegalArgumentException("Technician " + skipped.technicianId() + " is both routed and skipped");
            if (Input.present(costCents, "costCents") < 0) throw new IllegalArgumentException("costCents must not be negative");
            Input.integer(overtimeMinutes, "overtimeMinutes", 0, 1440);
        }
    }

    /** A located appointment on a drawn route. */
    /** A drawn stop and where its coordinates came from. */
    public record LocatedStop(String appointmentId, Integer sequence, Double lat, Double lng, PublicTypes.CoordinateSource source) {
        public LocatedStop {
            Input.id(appointmentId, "appointmentId");
            Input.integer(sequence, "sequence", 0, Integer.MAX_VALUE);
            coordinate(lat, 90, "lat");
            coordinate(lng, 180, "lng");
            Input.present(source, "source");
        }
    }

    /** One road leg of a route segment, as [lng, lat] positions. */
    public record GeometryLeg(Integer segment, Integer legIndex, Long seconds, Long meters, List<List<Double>> coordinates) {
        public GeometryLeg {
            Input.integer(segment, "segment", 0, Integer.MAX_VALUE);
            Input.integer(legIndex, "legIndex", 0, Integer.MAX_VALUE);
            if (Input.present(seconds, "seconds") < 0 || Input.present(meters, "meters") < 0) throw new IllegalArgumentException("seconds and meters must not be negative");
            coordinates = Input.list(coordinates, "coordinates");
            if (coordinates.size() < 2) throw new IllegalArgumentException("A leg has at least two positions");
            for (List<Double> position : coordinates) {
                if (position.size() != 2) throw new IllegalArgumentException("A position is [lng, lat]");
                coordinate(position.get(0), 180, "lng");
                coordinate(position.get(1), 90, "lat");
            }
        }
    }

    /**
     * A technician-day drawn on roads: its located stops in order, and the legs of each segment from start to end.
     * Approved absences split the day into segments, numbered in time order.
     */
    public record GeometryRoute(String technicianId, LocalDate serviceDate, List<LocatedStop> stops, List<GeometryLeg> legs) {
        public GeometryRoute {
            Input.id(technicianId, "technicianId");
            Input.present(serviceDate, "serviceDate");
            stops = Input.list(stops, "stops");
            legs = Input.list(legs, "legs");
            if (stops.isEmpty() != legs.isEmpty()) throw new IllegalArgumentException("A route is drawn exactly when it has stops");
        }
    }

    public record RouteGeometry(String routingIdentity, List<GeometryRoute> routes, List<SkippedTechnicianDay> skippedTechnicianDays) {
        public RouteGeometry {
            if (Input.present(routingIdentity, "routingIdentity").isBlank()) throw new IllegalArgumentException("Blank routingIdentity");
            routes = Input.list(routes, "routes");
            skippedTechnicianDays = Input.list(skippedTechnicianDays, "skippedTechnicianDays");
            Set<String> drawn = new HashSet<>();
            for (GeometryRoute route : routes) if (!drawn.add(route.technicianId())) throw new IllegalArgumentException("Duplicate route for " + route.technicianId());
            for (SkippedTechnicianDay skipped : skippedTechnicianDays)
                if (!drawn.add(skipped.technicianId())) throw new IllegalArgumentException("Technician " + skipped.technicianId() + " is both drawn and skipped");
        }
    }

    private static void coordinate(@Nullable Double value, double limit, String field) {
        if (value == null || !Double.isFinite(value) || Math.abs(value) > limit) throw new IllegalArgumentException("Invalid " + field);
    }

    public record Offer(String offerId, LocalDate serviceDate, Window window) {
        public Offer {
            Input.id(offerId, "offerId");
            Input.present(serviceDate, "serviceDate");
            Input.present(window, "window");
        }
    }

    /**
     * How a booking search's refinement pass ended: it finished; its cap, the deadline or the job's few windows cut it
     * short; it did not run (the insertion pass used the whole deadline, or the configured variant has none); or routing
     * or a remote engine failed it.
     */
    public enum Refinement { COMPLETE, SHORTENED, SKIPPED, UNAVAILABLE, SHED }

    /**
     * Technician-days that could not be located are left out of the search and listed in {@code skippedTechnicianDays}.
     * {@code refinement} is null only for an offer set answered before spec 1.2.0, and {@code incompleteDates} only for one
     * answered before 1.3.0, when replayed from storage; each is then left out of the JSON, never invented.
     */
    public record OfferSet(String offerSetId, Instant expiresAt, List<Offer> offers, Boolean searchComplete, List<SkippedTechnicianDay> skippedTechnicianDays,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable Refinement refinement,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable List<LocalDate> incompleteDates) {
        public OfferSet {
            Input.id(offerSetId, "offerSetId");
            Input.present(expiresAt, "expiresAt");
            offers = Input.list(offers, "offers");
            if (offers.size() > 4) throw new IllegalArgumentException("At most four offers");
            Set<String> ids = new HashSet<>();
            for (Offer offer : offers) if (!ids.add(offer.offerId())) throw new IllegalArgumentException("Duplicate offer " + offer.offerId());
            Input.present(searchComplete, "searchComplete");
            skippedTechnicianDays = Input.list(skippedTechnicianDays, "skippedTechnicianDays");
            if (incompleteDates != null) {
                incompleteDates = Input.list(incompleteDates, "incompleteDates");
                if (!new java.util.ArrayList<>(new java.util.TreeSet<>(incompleteDates)).equals(incompleteDates))
                    throw new IllegalArgumentException("incompleteDates must be ascending without repeats");
                if (!incompleteDates.isEmpty() && searchComplete) throw new IllegalArgumentException("A complete search has no incomplete dates");
            }
        }
    }

    public record Hold(String holdId, String offerId, Instant expiresAt) {
        public Hold {
            Input.id(holdId, "holdId");
            Input.id(offerId, "offerId");
            Input.present(expiresAt, "expiresAt");
        }
    }

    public record Released(Boolean released) {
        public Released {
            if (!Boolean.TRUE.equals(released)) throw new IllegalArgumentException("released must be true");
        }
    }

    public record Assignment(String appointmentId, String technicianId, LocalDate serviceDate, Integer sequence,
                             Instant plannedStart, Instant plannedEnd) {
        public Assignment {
            Input.id(appointmentId, "appointmentId");
            Input.id(technicianId, "technicianId");
            Input.present(serviceDate, "serviceDate");
            Input.integer(sequence, "sequence", 0, Integer.MAX_VALUE);
            Input.present(plannedStart, "plannedStart");
            Input.present(plannedEnd, "plannedEnd");
            if (!plannedStart.isBefore(plannedEnd)) throw new IllegalArgumentException("plannedStart must be before plannedEnd");
        }
    }

    public record CommitReceipt(String receiptId, List<Assignment> assignments, List<TechnicianDayVersion> technicianDays) {
        public CommitReceipt {
            Input.id(receiptId, "receiptId");
            assignments = Input.list(assignments, "assignments");
            technicianDays = Input.list(technicianDays, "technicianDays");
        }
    }

    public enum ErrorCode { INVALID_REQUEST, NOT_FOUND, STALE, NOT_COMMITTABLE, HOLD_UNAVAILABLE, INCOMPLETE_FACTS, BUSY, ROUTING_UNAVAILABLE, CALCULATION_UNAVAILABLE, CANCELLED }

    public record Problem(ErrorCode error, String message) {
        public Problem {
            Input.present(error, "error");
            if (Input.present(message, "message").isEmpty()) throw new IllegalArgumentException("Empty message");
        }
    }

    /** How far a daily or repair proposal request has come (spec 1.4.0). */
    public enum RequestState { QUEUED, RUNNING, COMPLETED }

    /** The operations whose requests report their status. */
    public enum RequestOperation { DAILY_PROPOSAL, REPAIR_PROPOSAL }

    /**
     * The answer a request ended with: the HTTP status and body the call answers (201 with the proposal, or a Problem),
     * and for a refusal worth retrying, the seconds to wait first.
     */
    public record RequestResult(Integer status, Object body,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable Integer retryAfterSeconds) {
        public RequestResult {
            Input.integer(status, "status", 200, 599);
            Input.present(body, "body");
            if (status == 201 ? !(body instanceof DailyProposal) : !(body instanceof Problem))
                throw new IllegalArgumentException("A request result is 201 with its proposal, or an error status with a Problem");
            if (retryAfterSeconds != null) Input.integer(retryAfterSeconds, "retryAfterSeconds", 1, 300);
        }
    }

    /** {@code GET /api/v1/requests/{requestId}}, and the 202 answer to a request sent with {@code Prefer: respond-async}. */
    public record RequestStatus(String requestId, RequestOperation operation, RequestState state,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable Instant queuedAt,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable Instant startedAt,
                                @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Nullable RequestResult result) {
        public RequestStatus {
            Input.requestId(requestId);
            Input.present(operation, "operation");
            Input.present(state, "state");
            if ((state == RequestState.COMPLETED) != (result != null)) throw new IllegalArgumentException("A request has a result exactly when it is COMPLETED");
            if (state == RequestState.QUEUED && startedAt != null) throw new IllegalArgumentException("A queued request has not started");
        }
    }

    public record StaleProblem(String error, String message, List<TechnicianDayVersion> changed) {
        public StaleProblem {
            if (!"STALE".equals(error)) throw new IllegalArgumentException("error must be STALE");
            if (Input.present(message, "message").isEmpty()) throw new IllegalArgumentException("Empty message");
            changed = Input.list(changed, "changed");
            if (changed.isEmpty()) throw new IllegalArgumentException("changed must not be empty");
        }
    }
}
