package dev.waterflex.scheduler;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;

@RestController
public class TestAddressRoutabilityController {
    public record Candidate(String id, String serviceCode, Double lat, Double lng) {
        public Candidate {
            id = RequestChecks.text(id, "id"); serviceCode = RequestChecks.text(serviceCode, "serviceCode");
            if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid candidate coordinate");
        }
    }
    public record Request(@Nullable List<@Nullable Candidate> candidates) { }
    public record Response(List<TestAddressRoutabilityService.Result> results) { }
    private final TestAddressRoutabilityService service;
    public TestAddressRoutabilityController(TestAddressRoutabilityService service) { this.service = service; }

    @PostMapping("/internal/test-address-routability")
    public Response check(@RequestBody Request request) {
        List<@Nullable Candidate> raw = request.candidates();
        if (raw == null || raw.isEmpty() || raw.size() > 12) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid address candidates");
        List<TestAddressRoutabilityService.Candidate> candidates = new java.util.ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (@Nullable Candidate candidate : raw) {
            if (candidate == null || !ids.add(candidate.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid address candidates");
            candidates.add(new TestAddressRoutabilityService.Candidate(candidate.id(), candidate.serviceCode(), Required.value(candidate.lat()), Required.value(candidate.lng())));
        }
        return new Response(Required.value(service.check(Required.value(candidates))));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RoadClient.RoadUnavailable.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public java.util.Map<String, String> roadUnavailable(RoadClient.RoadUnavailable error) {
        return Required.value(java.util.Map.of("detail", error.getMessage()));
    }
}
