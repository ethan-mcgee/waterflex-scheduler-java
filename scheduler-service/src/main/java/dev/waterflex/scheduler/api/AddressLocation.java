package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.RoadPoint;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the public API treats a location sent with an address and no coordinates ({@code geocoding.mode}). Only
 * COORDINATES_REQUIRED exists today: such a location is never guessed, and its technician-day is left unchanged and
 * reported. A NOMINATIM mode, which locates the address with the scheduler's own geocoder and accepts only a
 * certain match, is planned; whichever mode WaterFlex Software turns out not to need is removed.
 */
@Component
public class AddressLocation implements DailyPreparation.AddressLocator {
    public enum Mode { COORDINATES_REQUIRED }

    public AddressLocation(@Value("${geocoding.mode:COORDINATES_REQUIRED}") String mode) {
        try { Mode.valueOf(mode); }
        catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("geocoding.mode " + mode + " is not available; only COORDINATES_REQUIRED is implemented", unknown);
        }
    }

    @Override public @Nullable RoadPoint locate(PublicTypes.Address address) { return null; }
}
