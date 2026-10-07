package dev.waterflex.scheduler;

import dev.waterflex.scheduler.BookingSnapshot.*;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Optimistic bounds only. Every surviving candidate still needs canonical independent validation. */
final class BookingRouteBounds {
    private record Work(Instant start, Instant end) { }
    private record Key(String technician, List<Visit> visits) { }
    private record OrderKey(String technician, List<String> order) { }
    private record Summary(List<Visit> visits, Instant[] earliest, Instant[] latest, long[] prefixPaid, long[] suffixPaid, boolean possible) { }
    private final Day day;
    private final Rates rates;
    private final Map<String, List<Work>> intervals = new HashMap<>();
    private final Map<String, Long> buffered = new HashMap<>();
    private final Map<Key, Summary> summaries = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.@Nullable Entry<Key, Summary> eldest) { return size() > 2048; }
    };
    private final Map<OrderKey, Summary> immutableSummaries = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.@Nullable Entry<OrderKey, Summary> eldest) { return size() > 2048; }
    };
    BookingRouteBounds(Day day, Rates rates) {
        this.day = day; this.rates = rates;
        day.technicians().forEach((id, technician) -> intervals.put(id, working(Required.value(technician))));
    }
    boolean insertion(String technicianId, List<String> order, Map<String, Visit> facts, Visit visit, int position) {
        Technician technician = Required.value(day.technicians().get(technicianId));
        if (!technician.services().contains(visit.serviceId())) return false;
        var base = summary(technician, order, facts);
        if (!base.possible()) return false;
        Visit previous = position == 0 ? null : base.visits().get(position - 1);
        Visit next = position == order.size() ? null : base.visits().get(position);
        long incoming = previous == null ? travel(technicianId, visit.id()) : connection(technicianId, previous.id(), visit.id());
        long outgoing = next == null ? travel(visit.id(), technicianId + ":return") : connection(technicianId, visit.id(), next.id());
        if (incoming == Long.MAX_VALUE || outgoing == Long.MAX_VALUE) return false;
        long lowerPaid = add(add(base.prefixPaid()[position], base.suffixPaid()[position]), add(add(incoming, outgoing), visit.durationMinutes()));
        if (lowerPaid > technician.maxDailyMinutes()) return false;
        Instant ready = previous == null ? technician.shiftStart() : base.earliest()[position - 1].plusSeconds(previous.durationMinutes() * 60L);
        Instant earliest = forward(technicianId, later(Required.value(ready.plusSeconds(incoming * 60)), visit.windowStart()), visit.durationMinutes());
        if (earliest == null || !earliest.isBefore(visit.windowEnd())) return false;
        Instant following = earliest.plusSeconds((visit.durationMinutes() + outgoing) * 60);
        return next == null ? !following.isAfter(technician.shiftEnd().plusSeconds(technician.maxOvertimeMinutes() * 60L))
                : !following.isAfter(base.latest()[position]);
    }
    boolean possible(String technicianId, List<String> order, Map<String, Visit> facts) {
        return summary(Required.value(day.technicians().get(technicianId)), order, facts).possible();
    }
    private Summary summary(Technician technician, List<String> order, Map<String, Visit> facts) {
        OrderKey immutableKey = facts == day.visits() ? new OrderKey(technician.id(), Required.value(List.copyOf(order))) : null;
        if (immutableKey != null) {
            Summary cached = immutableSummaries.get(immutableKey);
            if (cached != null) return cached;
        }
        List<Visit> visits = new ArrayList<>();
        for (String id : order) visits.add(Required.value(facts.get(id), "bounded route visit"));
        Key key = new Key(technician.id(), Required.value(List.copyOf(visits)));
        Summary cached = summaries.get(key);
        if (cached != null) {
            if (immutableKey != null) immutableSummaries.put(immutableKey, cached);
            return cached;
        }
        int count = visits.size(); Instant[] earliest = new Instant[count], latest = new Instant[count];
        long[] prefix = new long[count + 1], suffix = new long[count + 1];
        boolean possible = true;
        Instant ready = technician.shiftStart(); String previous = technician.id();
        for (int i = 0; i < count; i++) {
            Visit visit = Required.value(visits.get(i));
            long incoming = i == 0 ? travel(previous, visit.id()) : connection(technician.id(), previous, visit.id());
            if (incoming == Long.MAX_VALUE || !technician.services().contains(visit.serviceId())) { possible = false; break; }
            Instant arrival = forward(technician.id(), later(Required.value(ready.plusSeconds(incoming * 60)), visit.windowStart()), visit.durationMinutes());
            if (arrival == null || !arrival.isBefore(visit.windowEnd())) { possible = false; break; }
            earliest[i] = arrival; ready = Required.value(arrival.plusSeconds(visit.durationMinutes() * 60L));
            prefix[i + 1] = add(prefix[i], add(incoming, visit.durationMinutes())); previous = visit.id();
        }
        Instant following = Required.value(technician.shiftEnd().plusSeconds(technician.maxOvertimeMinutes() * 60L));
        String next = technician.id() + ":return";
        if (possible) for (int i = count - 1; i >= 0; i--) {
            Visit visit = Required.value(visits.get(i));
            long outgoing = i == count - 1 ? travel(visit.id(), next) : connection(technician.id(), visit.id(), next);
            if (outgoing == Long.MAX_VALUE) { possible = false; break; }
            Instant bound = earlier(Required.value(following.minusSeconds((outgoing + visit.durationMinutes()) * 60)), Required.value(visit.windowEnd().minusNanos(1)));
            Instant arrival = backward(technician.id(), bound, visit.durationMinutes());
            if (arrival == null || arrival.isBefore(visit.windowStart()) || arrival.isBefore(earliest[i])) { possible = false; break; }
            latest[i] = arrival; following = arrival;
            suffix[i] = add(suffix[i + 1], add(outgoing, visit.durationMinutes())); next = visit.id();
        }
        if (count > 0 && possible && add(prefix[count], travel(previous, technician.id() + ":return")) > technician.maxDailyMinutes()) possible = false;
        Summary result = new Summary(key.visits(), earliest, latest, prefix, suffix, possible);
        summaries.put(key, result);
        if (immutableKey != null) immutableSummaries.put(immutableKey, result);
        return result;
    }
    private long connection(String technician, String from, String to) {
        // Across an absence, endpoint travel may be cheaper than the direct arc. No triangle inequality is assumed.
        return Math.min(travel(from, to), add(travel(from, technician + ":return"), travel(technician, to)));
    }
    private long travel(String from, String to) {
        String pair = from + ">" + to; Long cached = buffered.get(pair); if (cached != null) return cached;
        day.roads().require(from, to);
        var road = day.roads().legs().get(pair);
        long minutes = road == null ? Long.MAX_VALUE : Monetary.bufferedMinutes(road.seconds(), rates.travelBufferPct(), rates.travelBufferMinutes());
        buffered.put(pair, minutes); return minutes;
    }
    private @Nullable Instant forward(String technician, Instant earliest, int duration) {
        for (Work work : Required.value(intervals.get(technician))) {
            Instant start = later(earliest, work.start());
            if (!start.plusSeconds(duration * 60L).isAfter(work.end())) return start;
        }
        return null;
    }
    private @Nullable Instant backward(String technician, Instant latest, int duration) {
        var work = Required.value(intervals.get(technician));
        for (int i = work.size() - 1; i >= 0; i--) {
            Work interval = Required.value(work.get(i));
            Instant start = earlier(latest, Required.value(interval.end().minusSeconds(duration * 60L)));
            if (!start.isBefore(interval.start())) return start;
        }
        return null;
    }
    private static List<Work> working(Technician technician) {
        List<Work> work = new ArrayList<>(); Instant cursor = technician.shiftStart();
        Instant end = Required.value(technician.shiftEnd().plusSeconds(technician.maxOvertimeMinutes() * 60L));
        var absences = new ArrayList<>(technician.absences()); absences.sort(Comparator.comparing(absence -> absence.start()));
        for (var absence : absences) {
            Instant before = earlier(absence.start(), end);
            if (cursor.isBefore(before)) work.add(new Work(cursor, before));
            cursor = later(cursor, absence.end()); if (!cursor.isBefore(end)) break;
        }
        if (cursor.isBefore(end)) work.add(new Work(cursor, end)); return Required.value(List.copyOf(work));
    }
    private static long add(long first, long second) { return first == Long.MAX_VALUE || second == Long.MAX_VALUE ? Long.MAX_VALUE : Math.addExact(first, second); }
    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
}
