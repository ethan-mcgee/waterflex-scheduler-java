package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.Visit;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Customer selection/release orchestration; routing finishes before the locked commit begins. */
@Service
public final class ReservationLifecycleService {
    private record Offer(String holdId, String offerId, String jobId, LocalDate day, String metroId, Instant expiresAt,
            @Nullable Instant releasedAt, @Nullable String setId, @Nullable String selectedOfferId,
            @Nullable Instant supersededAt, String status, RoadClient.Point address) { }
    private record Cancellation(String jobId, LocalDate day, String metroId, boolean cancelled) { }
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    private final BookingSnapshotLoader loader;
    private final ReservationTransition transitions;
    private final ReservationCommit commits;
    public ReservationLifecycleService(JdbcTemplate jdbc, RoadClient roads, BookingSnapshotLoader loader,
            ReservationTransition transitions, ReservationCommit commits) {
        this.jdbc = jdbc; this.roads = roads; this.loader = loader; this.transitions = transitions; this.commits = commits;
    }

    public BookingService.Selection select(String jobId, String offerId) {
        Offer offer = offer("h.\"jobId\"=? AND h.\"offerToken\"=?", jobId, offerId);
        BookingService.Confirmation confirmation;
        if (offer.status().equals("SCHEDULED")) {
            if (!offerId.equals(offer.selectedOfferId())) throw conflict("A different offer was selected");
            confirmation = appointment(jobId);
        } else confirmation = confirm(offer, true);
        return new BookingService.Selection(offer.holdId(), offer.expiresAt(), confirmation.appointmentId(), confirmation.windowStart(), confirmation.windowEnd());
    }

    public BookingService.Confirmation confirm(String holdId) {
        Offer offer = offer("h.id=?", holdId);
        if (offer.setId() != null && (!offer.offerId().equals(offer.selectedOfferId()) || offer.supersededAt() != null))
            throw conflict("A different offer was selected");
        if (offer.status().equals("SCHEDULED")) return appointment(offer.jobId());
        return confirm(offer, false);
    }

    private BookingService.Confirmation confirm(Offer offer, boolean selecting) {
        pending(offer);
        List<LocalDate> dates = dates(offer.jobId());
        if (dates.isEmpty()) throw conflict("Offer expired before confirmation");
        String identity = roads.activeIdentity();
        var facts = loader.loadDates(offer.metroId(), dates, Required.value(Instant.now()), identity);
        String id = Required.value(UUID.randomUUID().toString());
        var proposals = transitions.prepare(facts, offer.jobId(), new ReservationTransition.Confirmation(offer.holdId(), id));
        var selected = Required.value(proposals.get(offer.day()), "selected date");
        Visit visit = Required.value(selected.day().visits().get(id), "selected appointment");
        if (!visit.location().equals(offer.address())) throw conflict("Job location changed after reservation");
        Instant arrival = Required.value(selected.validation().arrivals().get(id), "selected arrival");
        try {
            return commits.commit(facts, offer.jobId(), "", false, proposals, true, () -> {
                Offer current = offer("h.id=?", offer.holdId());
                pending(current);
                if (!current.equals(offer)) throw conflict("Selected offer changed");
                if (selecting && offer.setId() != null) jdbc.update("UPDATE booking_offer_set SET \"selectedOfferId\"=? WHERE id=?", offer.offerId(), offer.setId());
                jdbc.update("INSERT INTO appointment (id,\"jobId\",\"technicianId\",\"serviceDate\",\"windowStart\",\"windowEnd\",\"plannedStart\",\"plannedEnd\",sequence,\"updatedAt\") VALUES (?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP)",
                        id, offer.jobId(), visit.originalTechnicianId(), stamp(offer.day()), stamp(visit.windowStart()), stamp(visit.windowEnd()),
                        stamp(arrival), stamp(Required.value(arrival.plusSeconds(visit.durationMinutes() * 60L))));
                jdbc.update("UPDATE job SET status='SCHEDULED',\"manualFollowUpStatus\"=NULL,\"manualFollowUpReason\"=NULL,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", offer.jobId());
                jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"releasedAt\" IS NULL", offer.jobId());
                return new BookingService.Confirmation(id, visit.windowStart(), visit.windowEnd());
            });
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 409) throw exception;
            Offer current = offer("h.id=?", offer.holdId());
            if (current.status().equals("SCHEDULED") && current.offerId().equals(current.selectedOfferId())) return appointment(current.jobId());
            throw exception;
        }
    }

    public Map<String, Boolean> release(String jobId, String offerId) {
        Offer offer = offer("h.\"jobId\"=? AND h.\"offerToken\"=?", jobId, offerId);
        if (!offer.status().equals("PENDING")) throw conflict("Job already booked");
        if (offer.supersededAt() != null) return Required.value(Map.of("success", true));
        sameActiveSet(offer);
        List<LocalDate> dates = dates(jobId);
        // Include the expired offer's day to independently check removal shortcuts and surviving holds.
        if (!dates.contains(offer.day())) { dates = new ArrayList<>(dates); dates.add(offer.day()); dates.sort(Comparator.naturalOrder()); }
        var facts = loader.loadDates(offer.metroId(), dates, Required.value(Instant.now()), roads.activeIdentity());
        var proposals = transitions.prepare(facts, jobId, null);
        return commits.commit(facts, jobId, "", false, proposals, false, () -> {
            Offer current = offer("h.id=?", offer.holdId());
            if (!current.status().equals("PENDING")) throw conflict("Job already booked");
            sameActiveSet(current);
            if (offer.setId() != null) jdbc.update("UPDATE booking_offer_set SET \"supersededAt\"=CURRENT_TIMESTAMP WHERE id=?", offer.setId());
            jdbc.update("UPDATE slot_hold SET \"releasedAt\"=CURRENT_TIMESTAMP WHERE \"jobId\"=? AND \"releasedAt\" IS NULL", jobId);
            return Required.value(Map.of("success", true));
        });
    }

    public Map<String, Object> cancel(String appointmentId, String reason) {
        if (reason.isBlank() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cancellation reason required");
        var rows = jdbc.query("SELECT a.\"jobId\",a.\"serviceDate\",p.\"metroId\",a.\"cancelledAt\" IS NOT NULL FROM appointment a "
                + "JOIN LATERAL (SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=a.\"technicianId\" AND \"effectiveDate\"<=a.\"serviceDate\" ORDER BY \"effectiveDate\" DESC LIMIT 1) d ON true JOIN depot p ON p.id=d.\"depotId\" WHERE a.id=?",
                (rs, _) -> new Cancellation(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant().atZone(ZoneOffset.UTC).toLocalDate()),
                        Required.string(rs, 3), Required.bool(rs, 4)), appointmentId);
        if (rows.size() != 1) throw conflict("Appointment or dated depot is missing");
        Cancellation cancellation = Required.value(rows.getFirst());
        if (cancellation.cancelled()) return Required.value(Map.<String, Object>of("success", true, "appointmentId", appointmentId, "alreadyCancelled", true));
        List<LocalDate> dates = new ArrayList<>(); dates.add(cancellation.day());
        var facts = loader.loadDates(cancellation.metroId(), dates, Required.value(Instant.now()), roads.activeIdentity());
        var proposals = transitions.cancel(facts, appointmentId);
        return commits.cancellation(facts, cancellation.jobId(), proposals, () -> {
            if (jdbc.update("UPDATE appointment SET \"cancelledAt\"=CURRENT_TIMESTAMP,\"cancellationReason\"=?,\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=? AND \"cancelledAt\" IS NULL",
                    reason.trim(), appointmentId) != 1) throw conflict("Appointment changed during cancellation");
            jdbc.update("UPDATE job SET status='CANCELLED',\"updatedAt\"=CURRENT_TIMESTAMP WHERE id=?", cancellation.jobId());
            return Required.value(Map.<String, Object>of("success", true, "appointmentId", appointmentId, "alreadyCancelled", false));
        });
    }

    private List<LocalDate> dates(String jobId) {
        return Required.value(jdbc.query("SELECT DISTINCT \"serviceDate\" FROM slot_hold WHERE \"jobId\"=? AND \"releasedAt\" IS NULL ORDER BY \"serviceDate\"",
                (rs, _) -> Required.value(Required.timestamp(rs, 1).toInstant().atZone(ZoneOffset.UTC).toLocalDate()), jobId));
    }

    private void sameActiveSet(Offer offer) {
        if (Required.query(jdbc, "SELECT count(*) FROM slot_hold WHERE \"jobId\"=? AND \"releasedAt\" IS NULL AND \"expiresAt\">clock_timestamp() AND \"offerSetId\" IS DISTINCT FROM ?",
                Integer.class, offer.jobId(), offer.setId()) > 0) throw conflict("A newer offer set exists for this job");
    }

    private Offer offer(String predicate, @Nullable Object... parameters) {
        var rows = jdbc.query("SELECT h.id,h.\"offerToken\",h.\"jobId\",h.\"serviceDate\",p.\"metroId\",h.\"expiresAt\",h.\"releasedAt\",s.id,s.\"selectedOfferId\",s.\"supersededAt\",j.status::text,ad.lat,ad.lng "
                + "FROM slot_hold h JOIN job j ON j.id=h.\"jobId\" JOIN address ad ON ad.id=j.\"addressId\" LEFT JOIN booking_offer_set s ON s.id=h.\"offerSetId\" "
                + "JOIN LATERAL (SELECT \"depotId\" FROM technician_depot_assignment WHERE \"technicianId\"=h.\"technicianId\" AND \"effectiveDate\"<=h.\"serviceDate\" ORDER BY \"effectiveDate\" DESC LIMIT 1) a ON true JOIN depot p ON p.id=a.\"depotId\" WHERE " + predicate,
                (rs, _) -> new Offer(Required.string(rs, 1), Required.string(rs, 2), Required.string(rs, 3),
                        Required.value(Required.timestamp(rs, 4).toInstant().atZone(ZoneOffset.UTC).toLocalDate()), Required.string(rs, 5),
                        Required.value(Required.timestamp(rs, 6).toInstant()), instant(rs.getTimestamp(7)), rs.getString(8), rs.getString(9),
                        instant(rs.getTimestamp(10)), Required.string(rs, 11), Required.location(rs, 12, 13, HttpStatus.CONFLICT)), parameters);
        if (rows.size() != 1) throw conflict("Reserved offer is missing or ambiguous");
        return Required.value(rows.getFirst());
    }

    private BookingService.Confirmation appointment(String jobId) {
        var rows = jdbc.query("SELECT id,\"windowStart\",\"windowEnd\" FROM appointment WHERE \"jobId\"=? AND \"cancelledAt\" IS NULL",
                (rs, _) -> new BookingService.Confirmation(Required.string(rs, 1), Required.value(Required.timestamp(rs, 2).toInstant()), Required.value(Required.timestamp(rs, 3).toInstant())), jobId);
        if (rows.size() != 1) throw conflict("Confirmed appointment is missing or ambiguous");
        return Required.value(rows.getFirst());
    }
    private static void pending(Offer offer) {
        if (!offer.status().equals("PENDING") || offer.releasedAt() != null || offer.supersededAt() != null || !offer.expiresAt().isAfter(Instant.now()))
            throw conflict("Offer is no longer available");
    }
    private static @Nullable Instant instant(@Nullable Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp stamp(LocalDate day) { return stamp(Required.value(day.atStartOfDay(ZoneOffset.UTC).toInstant())); }
    private static Timestamp stamp(Instant value) { return Required.value(Timestamp.from(value)); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
