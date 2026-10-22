package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * The directed road legs of one fact revision behind the legacy {@code from>to} map every other reader keeps using,
 * with an index for scoring: each endpoint identity (a technician's start and return, a visit) gets a number, and a
 * leg is an array cell holding its buffered travel minutes computed once. Scoring resolves each visit's number with
 * one probe and reads legs without building a key or touching BigDecimal. The index is built on the first scoring
 * lookup, so a plan evaluated once outside the solver (a booking candidate) pays only for the map copy it always
 * paid for. Content equality is the map's.
 */
final class RoadMatrix extends AbstractMap<String, DayPlan.RoadLeg> {
    /** A leg with the travel minutes the revision's travel buffer makes of it ({@link Monetary#bufferedMinutes}). */
    record Leg(long seconds, long meters, long bufferedMinutes) { }
    /** Identity numbers and the two leg tables: onward to a visit, homeward to a technician's return endpoint. */
    private record Index(Map<String, Integer> numbers, @Nullable Leg[][] onward, @Nullable Leg[][] homeward) { }
    private static final AtomicInteger SERIALS = new AtomicInteger();
    /** Unique among this JVM's matrices and never 0, so an entity can key a cached number by it inside one long. */
    private final int serial;
    private final Map<String, DayPlan.RoadLeg> legs;
    private final BigDecimal travelBufferPct;
    private final long travelBufferMinutes;
    private volatile @Nullable Index index;

    private RoadMatrix(Map<String, DayPlan.RoadLeg> legs, BigDecimal travelBufferPct, long travelBufferMinutes) {
        this.legs = legs; this.travelBufferPct = travelBufferPct; this.travelBufferMinutes = travelBufferMinutes;
        serial = SERIALS.incrementAndGet();
        // Past 2^31 matrices the counter would wrap and two matrices could share a serial; refuse rather than reuse one.
        PlanFacts.check(serial > 0, "road matrix serials exhausted");
    }

    int serial() { return serial; }

    /** Wraps these roads under this travel buffer; a matrix already wrapped under the same buffer is returned as it is. */
    static RoadMatrix of(Map<String, DayPlan.RoadLeg> roads, BigDecimal travelBufferPct, long travelBufferMinutes) {
        if (roads instanceof RoadMatrix same && same.travelBufferPct.equals(travelBufferPct) && same.travelBufferMinutes == travelBufferMinutes) return same;
        Map<String, DayPlan.RoadLeg> legs = Required.value(Map.copyOf(roads));
        for (var entry : legs.entrySet()) { PlanFacts.roadKey(Required.value(entry.getKey())); Required.value(entry.getValue(), "road value"); }
        return new RoadMatrix(legs, travelBufferPct, travelBufferMinutes);
    }

    private Index index() {
        Index built = index;
        if (built == null) {
            synchronized (this) {
                built = index;
                if (built == null) { built = build(); index = built; }
            }
        }
        return built;
    }

    private Index build() {
        Map<String, Integer> numbers = new HashMap<>();
        record Split(String from, String to, boolean home) { }
        Map<String, Split> splits = new HashMap<>();
        for (String key : legs.keySet()) {
            int at = key.indexOf('>');
            String from = Required.value(key.substring(0, at)), to = Required.value(key.substring(at + 1));
            boolean home = to.endsWith(":return");
            if (home) to = Required.value(to.substring(0, to.length() - ":return".length()));
            numbers.putIfAbsent(from, numbers.size()); numbers.putIfAbsent(to, numbers.size());
            splits.put(key, new Split(from, to, home));
        }
        int size = numbers.size();
        @Nullable Leg[][] onward = new @Nullable Leg[size][size], homeward = new @Nullable Leg[size][size];
        for (var entry : legs.entrySet()) {
            Split split = Required.value(splits.get(entry.getKey()));
            DayPlan.RoadLeg road = Required.value(entry.getValue());
            Leg leg = new Leg(road.seconds(), road.meters(), Monetary.bufferedMinutes(road.seconds(), travelBufferPct, travelBufferMinutes));
            (split.home() ? homeward : onward)[Required.value(numbers.get(split.from()))][Required.value(numbers.get(split.to()))] = leg;
        }
        return new Index(Required.value(Map.copyOf(numbers)), onward, homeward);
    }

    /** The number of this identity (a technician or a visit), or -1 when no road names it. */
    int number(String identity) {
        Integer number = index().numbers().get(identity);
        return number == null ? -1 : number;
    }

    /** The leg from this origin number to this visit number, or null when no road is known (or a number is -1). */
    @Nullable Leg leg(int from, int visit) {
        return from < 0 || visit < 0 ? null : index().onward()[from][visit];
    }

    /** The leg from this origin number to this technician number's return endpoint, or null when no road is known. */
    @Nullable Leg home(int from, int technician) {
        return from < 0 || technician < 0 ? null : index().homeward()[from][technician];
    }

    @Override public Set<Map.Entry<String, DayPlan.RoadLeg>> entrySet() { return Required.value(legs.entrySet()); }
    @Override public DayPlan.@Nullable RoadLeg get(@Nullable Object key) { return legs.get(key); }
    @Override public boolean containsKey(@Nullable Object key) { return legs.containsKey(key); }
    @Override public int size() { return legs.size(); }
}
