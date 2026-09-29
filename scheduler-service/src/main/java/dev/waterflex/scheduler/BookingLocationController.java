package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Coverage and road access do not depend on technician availability. */
@RestController
public class BookingLocationController {
    public record Request(@Nullable Double lat, @Nullable Double lng) {
        public Request {
            if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                    || Math.abs(lat) > 90 || Math.abs(lng) > 180)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid location");
        }
    }
    public enum Status { VALID, OUTSIDE_COVERAGE, UNROUTABLE, ROUTING_UNAVAILABLE }
    public record Response(Status status) { }
    record Circle(RoadClient.Point center, double radius) { }
    private final JdbcTemplate jdbc;
    private final RoadClient roads;
    public BookingLocationController(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    @PostMapping("/v1/book/location/validate")
    public Response validate(@RequestBody Request request) {
        var point = new RoadClient.Point(Required.value(request.lat()), Required.value(request.lng()));
        List<Circle> circles = jdbc.query("SELECT d.lat,d.lng,m.\"serviceRadiusMi\" FROM depot d JOIN metro m ON m.id=d.\"metroId\"",
            (rs, _) -> {
                double radius = Required.number(rs, 3);
                if (radius <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid coverage radius");
                return new Circle(Required.location(rs, 1, 2, HttpStatus.CONFLICT), radius);
            });
        if (circles.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Coverage configuration unavailable");
        if (circles.stream().noneMatch(circle -> within(point, Required.value(circle)))) return new Response(Status.OUTSIDE_COVERAGE);
        try {
            var legs = roads.sparse(Required.value(List.<RoadClient.Pair>of(new RoadClient.Pair("location", point, point))), roads.activeIdentity());
            return new Response(legs.containsKey("location") ? Status.VALID : Status.UNROUTABLE);
        } catch (RoadClient.RoadUnavailable failure) { return new Response(Status.ROUTING_UNAVAILABLE); }
    }

    static boolean within(RoadClient.Point point, Circle circle) {
        double dLat = Math.toRadians(point.lat() - circle.center().lat());
        double dLng = Math.toRadians(point.lng() - circle.center().lng());
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(point.lat()))
            * Math.cos(Math.toRadians(circle.center().lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 3958.8 * Math.asin(Math.sqrt(Math.min(1, a))) <= circle.radius();
    }
}
