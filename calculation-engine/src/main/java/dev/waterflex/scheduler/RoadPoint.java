package dev.waterflex.scheduler;
/** Validated coordinates. Routing acquisition belongs to the caller. */
public record RoadPoint(double lat, double lng) {
    public RoadPoint {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new IllegalArgumentException("Invalid road coordinates");
    }
}
