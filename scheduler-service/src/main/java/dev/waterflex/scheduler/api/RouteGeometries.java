package dev.waterflex.scheduler.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.waterflex.scheduler.MetroRouting;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.RoadClient;
import dev.waterflex.scheduler.RoadPoint;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.SearchDeadline;
import dev.waterflex.scheduler.api.PublicRequests.RouteEvaluationRequest;
import dev.waterflex.scheduler.api.PublicResponses.*;
import dev.waterflex.scheduler.api.PublicTypes.Appointment;
import dev.waterflex.scheduler.api.PublicTypes.Location;
import dev.waterflex.scheduler.api.PublicTypes.TechnicianDay;
import dev.waterflex.scheduler.api.PublicTypes.Window;
import dev.waterflex.scheduler.optimizer.DailyOperation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * POST /api/v1/routes/geometry. Draws one metro day's routes on roads exactly in the order the snapshot gives them:
 * for each technician-day, the road legs from its start through its appointments to its end, split into segments by
 * its absences, as the dispatch board shows a day. Nothing is optimized or stored.
 */
@Service
public class RouteGeometries {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(RouteGeometries.class));
    /** The routing service draws at most this many points per request; longer routes are drawn in overlapping chunks. */
    private static final int POINTS_PER_REQUEST = 65;

    /** An appointment that starts inside one of its technician's absences; the day cannot be drawn as given. */
    private static final class Overlap extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Overlap(String message) { super(message); }
    }

    private final MetroRouting routing;
    private final DailyPreparation.AddressLocator locator;
    private final SearchAdmission admission;

    @Autowired
    public RouteGeometries(MetroRouting routing, AddressLocation locator, SearchAdmission admission) {
        this(routing, (DailyPreparation.AddressLocator) locator, admission);
    }

    public RouteGeometries(MetroRouting routing, DailyPreparation.AddressLocator locator, SearchAdmission admission) {
        this.routing = routing; this.locator = locator; this.admission = admission;
    }

    public DailyProposals.Reply draw(String tenantId, String body) {
        RouteEvaluationRequest request;
        try { request = PublicRequests.read(body, RouteEvaluationRequest.class); }
        catch (IllegalArgumentException invalid) { return DailyProposals.Reply.of(400, new Problem(ErrorCode.INVALID_REQUEST, DailyProposals.invalidRequest(invalid))); }
        try {
            RouteGeometry geometry = DailyOperation.execute(admission, Required.value(Duration.ofSeconds(20)), () -> draw(request),
                    receipt -> LOG.info("Public route geometry tenant={} {}", tenantId, receipt));
            return DailyProposals.Reply.of(200, geometry);
        } catch (MetroRouting.UnknownMetro unknown) {
            return DailyProposals.Reply.of(422, new Problem(ErrorCode.INCOMPLETE_FACTS, Required.value(unknown.getMessage())));
        } catch (Overlap overlap) {
            return DailyProposals.Reply.of(422, new Problem(ErrorCode.INCOMPLETE_FACTS, Required.value(overlap.getMessage())));
        } catch (RoadClient.RoadUnavailable unavailable) {
            return DailyProposals.Reply.of(503, DailyProposals.routingUnavailable(unavailable));
        } catch (SearchAdmission.Busy busy) {
            return DailyProposals.Reply.of(429, new Problem(ErrorCode.BUSY, "Search capacity exhausted"));
        } catch (SearchDeadline.Expired expired) {
            return DailyProposals.Reply.of(503, new Problem(ErrorCode.CALCULATION_UNAVAILABLE, "The calculation deadline passed"));
        }
    }

    private RouteGeometry draw(RouteEvaluationRequest request) {
        var snapshot = request.snapshot();
        RoadClient roads = routing.client(snapshot.metroId());
        String identity = roads.activeIdentity();
        Map<PublicTypes.Address, @Nullable RoadPoint> located = new HashMap<>();
        List<GeometryRoute> routes = new ArrayList<>();
        List<SkippedTechnicianDay> skipped = new ArrayList<>();
        for (TechnicianDay day : snapshot.technicianDays()) {
            List<Appointment> visits = new ArrayList<>(snapshot.appointments().stream().filter(item -> Required.value(item).key().equals(day.key())).toList());
            visits.sort(Comparator.comparingInt((Appointment item) -> item.sequence()).thenComparing(item -> Required.value(item).id()));
            List<String> unresolved = new ArrayList<>();
            RoadPoint start = locate(day.start(), located), end = locate(day.end(), located);
            if (start == null) unresolved.add("start location");
            if (end == null) unresolved.add("end location");
            List<LocatedStop> stops = new ArrayList<>();
            Map<String, RoadPoint> points = new HashMap<>();
            for (Appointment visit : visits) {
                RoadPoint point = locate(visit.location(), located);
                if (point == null) { unresolved.add("appointment " + visit.id()); continue; }
                points.put(visit.id(), point);
                stops.add(new LocatedStop(visit.id(), visit.sequence(), point.lat(), point.lng()));
            }
            if (!unresolved.isEmpty()) {
                skipped.add(new SkippedTechnicianDay(day.technicianId(), day.serviceDate(), SkipReason.LOCATION_UNRESOLVED,
                        "No coordinates and no certain address match for " + String.join(", ", unresolved) + "; send coordinates to draw this technician-day"));
                continue;
            }
            List<GeometryLeg> legs = new ArrayList<>();
            for (var segment : segments(day, visits).entrySet()) {
                List<RoadPoint> path = new ArrayList<>();
                path.add(Required.value(start));
                for (Appointment visit : segment.getValue()) path.add(Required.value(points.get(visit.id()), "located appointment"));
                path.add(Required.value(end));
                legs.addAll(legs(roads, identity, segment.getKey(), path));
            }
            routes.add(new GeometryRoute(day.technicianId(), day.serviceDate(), Required.value(List.copyOf(stops)), Required.value(List.copyOf(legs))));
        }
        // The board compares this identity with its routes; a graph update while drawing would mix two maps.
        if (!identity.equals(roads.activeIdentity())) throw new RoadClient.RoadUnavailable("Routing identity changed");
        return new RouteGeometry(identity, Required.value(List.copyOf(routes)), Required.value(List.copyOf(skipped)));
    }

    /**
     * The day's appointments by segment: a segment ends at each absence, so an appointment belongs to the segment after
     * every absence that ends by its planned start. Only segments with appointments are drawn.
     */
    static Map<Integer, List<Appointment>> segments(TechnicianDay day, List<Appointment> visits) {
        Map<Integer, List<Appointment>> segments = new TreeMap<>();
        for (Appointment visit : visits) {
            int segment = 0;
            for (Window absence : day.absences()) {
                if (!visit.plannedStart().isBefore(absence.start()) && visit.plannedStart().isBefore(absence.end()))
                    throw new Overlap("Appointment " + visit.id() + " starts during an absence of " + day.technicianId());
                if (!visit.plannedStart().isBefore(absence.end())) segment++;
            }
            segments.computeIfAbsent(segment, _ -> new ArrayList<>()).add(visit);
        }
        return segments;
    }

    private static List<GeometryLeg> legs(RoadClient roads, String identity, int segment, List<RoadPoint> path) {
        List<GeometryLeg> legs = new ArrayList<>();
        for (int offset = 0; offset < path.size() - 1; offset += POINTS_PER_REQUEST - 1) {
            JsonNode drawn = roads.routeGeometry(Required.value(path.subList(offset, Math.min(path.size(), offset + POINTS_PER_REQUEST))), identity).path("legs");
            if (!drawn.isArray()) throw new RoadClient.RoadUnavailable("Malformed road geometry");
            for (int index = 0; index < drawn.size(); index++) {
                JsonNode leg = Required.value(drawn.get(index));
                if (!leg.path("seconds").isIntegralNumber() || !leg.path("meters").isIntegralNumber()) throw new RoadClient.RoadUnavailable("Malformed road geometry");
                legs.add(new GeometryLeg(segment, offset + index, leg.path("seconds").asLong(), leg.path("meters").asLong(), positions(Required.value(leg.path("geometry")))));
            }
        }
        if (legs.size() != path.size() - 1) throw new RoadClient.RoadUnavailable("Road geometry is missing legs");
        return legs;
    }

    private static List<List<Double>> positions(JsonNode geometry) {
        JsonNode positions = geometry.path("coordinates");
        if (!"LineString".equals(geometry.path("type").asText()) || !positions.isArray() || positions.size() < 2)
            throw new RoadClient.RoadUnavailable("Malformed road geometry");
        List<List<Double>> coordinates = new ArrayList<>();
        for (JsonNode position : positions) {
            if (!position.isArray() || position.size() != 2 || !position.get(0).isNumber() || !position.get(1).isNumber())
                throw new RoadClient.RoadUnavailable("Malformed road geometry");
            double lng = position.get(0).asDouble(), lat = position.get(1).asDouble();
            if (!Double.isFinite(lng) || !Double.isFinite(lat) || Math.abs(lng) > 180 || Math.abs(lat) > 90)
                throw new RoadClient.RoadUnavailable("Malformed road geometry");
            coordinates.add(Required.value(List.of(lng, lat)));
        }
        return Required.value(List.copyOf(coordinates));
    }

    private @Nullable RoadPoint locate(Location location, Map<PublicTypes.Address, @Nullable RoadPoint> located) {
        Double lat = location.lat(), lng = location.lng();
        if (lat != null && lng != null) return new RoadPoint(lat, lng);
        PublicTypes.Address address = Required.value(location.address(), "address for a location without coordinates");
        if (!located.containsKey(address)) located.put(address, locator.locate(address));
        return located.get(address);
    }
}
