package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.RoadPoint;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the public API treats a location sent with an address and no coordinates ({@code geocoding.mode}). Coordinates
 * from the host are always used as given. COORDINATES_REQUIRED (the default) never locates an address: such a
 * location is not located. NOMINATIM locates it with the scheduler's own Nominatim ({@code geocoding.nominatim-url})
 * and accepts only an exact house ({@link NominatimGeocoder}). Either way an address that is not located is never
 * guessed: its technician-day is left unchanged and reported, or its booking is refused. When Nominatim itself is
 * unavailable the request fails with 503 and can be retried.
 */
@Component
public class AddressLocation implements DailyPreparation.AddressLocator {
    public enum Mode { COORDINATES_REQUIRED, NOMINATIM }

    private final @Nullable NominatimGeocoder geocoder;

    public AddressLocation(@Value("${geocoding.mode:COORDINATES_REQUIRED}") String mode,
                           @Value("${geocoding.nominatim-url:http://localhost:8082}") String nominatimUrl) {
        Mode chosen;
        try { chosen = Mode.valueOf(mode); }
        catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("geocoding.mode must be COORDINATES_REQUIRED or NOMINATIM, not " + mode, unknown);
        }
        geocoder = chosen == Mode.NOMINATIM ? new NominatimGeocoder(nominatimUrl) : null;
    }

    @Override public @Nullable RoadPoint locate(PublicTypes.Address address) {
        NominatimGeocoder current = geocoder;
        return current == null ? null : current.locate(address);
    }
}
