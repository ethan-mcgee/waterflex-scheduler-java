package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.api.PublicTypes.TechnicianDayVersion;
import dev.waterflex.scheduler.api.PublicTypes.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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

    public record Offer(String offerId, LocalDate serviceDate, Window window) {
        public Offer {
            Input.id(offerId, "offerId");
            Input.present(serviceDate, "serviceDate");
            Input.present(window, "window");
        }
    }

    /** Technician-days that could not be located are left out of the search and listed in {@code skippedTechnicianDays}. */
    public record OfferSet(String offerSetId, Instant expiresAt, List<Offer> offers, Boolean searchComplete, List<SkippedTechnicianDay> skippedTechnicianDays) {
        public OfferSet {
            Input.id(offerSetId, "offerSetId");
            Input.present(expiresAt, "expiresAt");
            offers = Input.list(offers, "offers");
            if (offers.size() > 4) throw new IllegalArgumentException("At most four offers");
            Set<String> ids = new HashSet<>();
            for (Offer offer : offers) if (!ids.add(offer.offerId())) throw new IllegalArgumentException("Duplicate offer " + offer.offerId());
            Input.present(searchComplete, "searchComplete");
            skippedTechnicianDays = Input.list(skippedTechnicianDays, "skippedTechnicianDays");
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

    public enum ErrorCode { INVALID_REQUEST, NOT_FOUND, STALE, NOT_COMMITTABLE, HOLD_UNAVAILABLE, INCOMPLETE_FACTS, BUSY, ROUTING_UNAVAILABLE, CALCULATION_UNAVAILABLE }

    public record Problem(ErrorCode error, String message) {
        public Problem {
            Input.present(error, "error");
            if (Input.present(message, "message").isEmpty()) throw new IllegalArgumentException("Empty message");
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
