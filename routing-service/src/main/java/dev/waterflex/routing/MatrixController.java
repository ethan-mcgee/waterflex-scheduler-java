package dev.waterflex.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.DistanceCalcEarth;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class MatrixController {
    public record Point(double lat, double lng) { }
    public record Request(List<Point> origins, List<Point> destinations) { }
    public record Leg(boolean routable, Long seconds, Long meters) { }
    public record Matrix(String mapVersion, List<List<Leg>> legs) { }

    private final GraphHopper hopper;
    private final String mapVersion;
    private final Map<String, Leg> cache = new ConcurrentHashMap<>();

    public MatrixController(@Value("${routing.osm-file:/data/omaha.osm.pbf}") String osmFile,
                            @Value("${routing.graph-dir:/data/graph}") String graphDir,
                            @Value("${routing.map-version:unprepared}") String mapVersion) {
        String activeVersion = mapVersion;
        if (activeVersion.equals("unprepared")) {
            try { activeVersion = Files.readString(Path.of(graphDir).getParent().resolve("map-version")).trim(); }
            catch (Exception ignored) { }
        }
        this.mapVersion = activeVersion;
        if (!new File(osmFile).isFile() && !new File(graphDir, "properties").isFile()) {
            this.hopper = null;
            return;
        }
        this.hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphDir);
        hopper.setEncodedValuesString("car_access, car_average_speed, road_access, road_environment, max_speed, ferry_speed");
        hopper.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        hopper.importOrLoad();
    }

    @PostMapping("/internal/matrix")
    public Matrix matrix(@RequestBody Request request) {
        if (hopper == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Road graph not imported");
        if (request.origins() == null || request.destinations() == null ||
            request.origins().size() > 64 || request.destinations().size() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid matrix dimensions");
        List<List<Leg>> rows = new ArrayList<>();
        for (Point origin : request.origins()) {
            List<Leg> row = new ArrayList<>();
            for (Point destination : request.destinations()) {
                String key = origin.lat() + "," + origin.lng() + ">" + destination.lat() + "," + destination.lng();
                row.add(cache.computeIfAbsent(key, unused -> route(origin, destination)));
            }
            rows.add(row);
        }
        return new Matrix(mapVersion, rows);
    }

    private Leg route(Point origin, Point destination) {
        if (!Double.isFinite(origin.lat()) || !Double.isFinite(origin.lng()) ||
            !Double.isFinite(destination.lat()) || !Double.isFinite(destination.lng()) ||
            Math.abs(origin.lat()) > 90 || Math.abs(destination.lat()) > 90 ||
            Math.abs(origin.lng()) > 180 || Math.abs(destination.lng()) > 180) return new Leg(false, null, null);
        var response = hopper.route(new GHRequest(new GHPoint(origin.lat(), origin.lng()),
                new GHPoint(destination.lat(), destination.lng())).setProfile("car"));
        if (response.hasErrors()) return new Leg(false, null, null);
        var path = response.getBest();
        var snapped = path.getWaypoints();
        if (snapped.size() < 2 ||
            DistanceCalcEarth.DIST_EARTH.calcDist(origin.lat(), origin.lng(), snapped.getLat(0), snapped.getLon(0)) > 1000 ||
            DistanceCalcEarth.DIST_EARTH.calcDist(destination.lat(), destination.lng(), snapped.getLat(snapped.size() - 1), snapped.getLon(snapped.size() - 1)) > 1000)
            return new Leg(false, null, null);
        return new Leg(true, Math.max(1, (path.getTime() + 999) / 1000), Math.round(path.getDistance()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ready", hopper != null, "mapVersion", mapVersion); }

    @PreDestroy
    public void close() { if (hopper != null) hopper.close(); }
}
