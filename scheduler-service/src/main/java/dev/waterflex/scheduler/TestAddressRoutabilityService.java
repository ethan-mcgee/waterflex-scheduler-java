package dev.waterflex.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TestAddressRoutabilityService {
    public record Candidate(String id, String serviceCode, double lat, double lng) { }
    record Technician(String id, String serviceCode, RoadClient.Point home) { }
    public record Result(String id, boolean routable) { }

    private final JdbcTemplate jdbc;
    private final RoadClient roads;

    public TestAddressRoutabilityService(JdbcTemplate jdbc, RoadClient roads) { this.jdbc = jdbc; this.roads = roads; }

    public List<Result> check(List<Candidate> candidates) {
        List<Technician> technicians = jdbc.query("SELECT t.id, s.code, t.\"homeLat\", t.\"homeLng\" FROM technician t JOIN technician_qualification q ON q.\"technicianId\"=t.id JOIN service_catalog s ON s.id=q.\"serviceId\" WHERE t.active=true AND t.\"metroId\"='metro-omaha' AND s.active=true ORDER BY t.id,s.code",
                (rs, _) -> new Technician(Required.string(rs, 1), Required.string(rs, 2), Required.location(rs, 3, 4, org.springframework.http.HttpStatus.CONFLICT)));
        Map<String, RoadClient.Point> points = new LinkedHashMap<>();
        technicians.forEach(tech -> points.put("tech:" + tech.id(), tech.home()));
        candidates.forEach(candidate -> points.put("candidate:" + candidate.id(), new RoadClient.Point(candidate.lat(), candidate.lng())));
        Map<String, RoadClient.Leg> matrix = roads.matrix(points);
        return evaluate(candidates, technicians, matrix);
    }

    static List<Result> evaluate(List<Candidate> candidates, List<Technician> technicians, Map<String, RoadClient.Leg> matrix) {
        List<Result> results = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String candidateKey = "candidate:" + candidate.id();
            boolean routable = technicians.stream().filter(tech -> tech.serviceCode().equals(candidate.serviceCode())).anyMatch(tech -> {
                String technicianKey = "tech:" + tech.id();
                return matrix.containsKey(technicianKey + ">" + candidateKey) && matrix.containsKey(candidateKey + ">" + technicianKey);
            });
            results.add(new Result(candidate.id(), routable));
        }
        return Required.value(results);
    }
}
