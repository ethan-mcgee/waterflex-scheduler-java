package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expiry removes capacity through the same independently validated transition as an explicit release. */
@Component
public final class ReservationExpiry {
    private record Expired(String setId, String jobId, String offerId) { }
    private final JdbcTemplate jdbc;
    private final ReservationLifecycleService lifecycle;
    private final SearchAdmission admission;
    private String cursor = "";
    public ReservationExpiry(JdbcTemplate jdbc, ReservationLifecycleService lifecycle, SearchAdmission admission) {
        this.jdbc = jdbc; this.lifecycle = lifecycle; this.admission = admission;
    }
    @Scheduled(fixedDelayString = "${booking.reservations.expiry-poll-ms:5000}")
    public void expire() {
        try {
            List<Expired> batch = jdbc.query("SELECT s.id,s.\"jobId\",o.id FROM booking_offer_set s JOIN job j ON j.id=s.\"jobId\" JOIN LATERAL (SELECT id FROM booking_offer WHERE \"offerSetId\"=s.id ORDER BY id LIMIT 1) o ON true WHERE s.id>? AND j.status='PENDING' AND s.\"supersededAt\" IS NULL AND s.\"expiresAt\"<=clock_timestamp() AND EXISTS (SELECT 1 FROM slot_hold h WHERE h.\"offerSetId\"=s.id AND h.\"releasedAt\" IS NULL) ORDER BY s.id LIMIT 4",
                    (rs, _) -> new Expired(Required.string(rs, 1), Required.string(rs, 2), Required.string(rs, 3)), cursor);
            if (batch.isEmpty()) { cursor = ""; return; }
            for (Expired expired : batch) {
                // Rotate past failures so one infeasible removal cannot starve unrelated expired sets.
                cursor = expired.setId();
                SearchDeadline deadline = new SearchDeadline(Required.value(Duration.ofSeconds(5)));
                try (var _ = admission.acquire(SearchAdmission.Kind.BACKGROUND, deadline)) {
                    deadline.within(() -> lifecycle.release(expired.jobId(), expired.offerId()));
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(ReservationExpiry.class).warn("Expired offer set {} requires validated release retry", expired.setId(), failure);
                }
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(ReservationExpiry.class).warn("Reservation expiry scan unavailable", failure);
        }
    }
}
