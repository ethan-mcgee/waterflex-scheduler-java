package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment-wide cap on newly reserved choices, independent of search and scarcity policy. */
@Component
public final class BookingOfferLimit {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(BookingOfferLimit.class));
    private final int value;

    public BookingOfferLimit(@Value("${booking.offer.limit:1}") @Nullable String configured) {
        value = switch (configured) {
            case "1" -> 1;
            case "2" -> 1;
            case "4" -> 1;
            case null, default -> throw new IllegalArgumentException(
                    "BOOKING_OFFER_LIMIT (booking.offer.limit) must be exactly 1, 2, or 4; blank values are invalid");
        };
        LOG.info("Booking offer limit: {}", value);
    }

    public int value() { return value; }
}
