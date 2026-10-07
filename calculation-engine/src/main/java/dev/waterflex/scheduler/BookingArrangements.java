package dev.waterflex.scheduler;
import java.util.*;
/** Pure arrangement transformations, with no reservation authority. */
public final class BookingArrangements {
    private BookingArrangements() { }
    public static BookingSnapshot.Arrangement without(BookingSnapshot.Arrangement source, Set<String> removed) {
        Map<String,List<String>> routes = new TreeMap<>();
        source.routes().forEach((technician, visits) -> routes.put(technician,
                Required.value(visits.stream().filter(id -> !removed.contains(id)).toList())));
        return new BookingSnapshot.Arrangement(routes);
    }
}
