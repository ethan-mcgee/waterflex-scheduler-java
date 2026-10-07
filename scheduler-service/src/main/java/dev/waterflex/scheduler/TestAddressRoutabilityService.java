package dev.waterflex.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TestAddressRoutabilityService {
    public record Candidate(String id, String serviceCode, double lat, double lng) { }
    record Technician(String id, String serviceCode, RouteEndpoints endpoints) { }
    public record Result(String id, boolean routable) { }

    private final JdbcTemplate jdbc;
    private final RoadClient roads;

    public TestAddressRoutabilityService(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    public List<Result> check(List<Candidate> candidates) {
        List<Technician> technicians = jdbc.query("SELECT t.id, s.code," + RouteEndpoints.COLUMNS + " FROM technician t" + RouteEndpoints.JOINS + " JOIN technician_qualification q ON q.\"technicianId\"=t.id JOIN service_catalog s ON s.id=q.\"serviceId\" WHERE t.active=true AND p.\"metroId\"='metro-omaha' AND s.active=true ORDER BY t.id,s.code",
                (rs, _) -> new Technician(dev.waterflex.scheduler.DatabaseFacts.string(rs, 1), dev.waterflex.scheduler.DatabaseFacts.string(rs, 2), RouteEndpoints.from(rs, 3)),
                java.sql.Timestamp.from(java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                java.sql.Timestamp.from(java.time.LocalDate.now(java.time.ZoneId.of("America/Chicago")).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        Map<String, RoadPoint> points = new LinkedHashMap<>();
        technicians.forEach(tech -> { points.put("tech:" + tech.id(), tech.endpoints().departure()); points.put("return:" + tech.id(), tech.endpoints().returnTo()); });
        candidates.forEach(candidate -> points.put("candidate:" + candidate.id(), new RoadPoint(candidate.lat(), candidate.lng())));
        Map<String, RoadClient.Leg> matrix = roads.matrix(points);
        return evaluate(candidates, technicians, matrix);
    }

    static List<Result> evaluate(List<Candidate> candidates, List<Technician> technicians, Map<String, RoadClient.Leg> matrix) {
        List<Result> results = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String candidateKey = "candidate:" + candidate.id();
            boolean routable = technicians.stream().filter(tech -> tech.serviceCode().equals(candidate.serviceCode())).anyMatch(tech -> {
                String technicianKey = "tech:" + tech.id();
                return matrix.containsKey(technicianKey + ">" + candidateKey) && matrix.containsKey(candidateKey + ">return:" + tech.id());
            });
            results.add(new Result(candidate.id(), routable));
        }
        return Required.value(results);
    }
}
