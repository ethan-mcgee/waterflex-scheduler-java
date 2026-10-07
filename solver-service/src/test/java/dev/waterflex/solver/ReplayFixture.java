package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.BookingSnapshot.*;
import dev.waterflex.scheduler.optimizer.*;
import java.time.*;
import java.util.*;
final class ReplayFixture {
    static final Instant CAPTURED = Required.value(Instant.parse("2026-10-25T17:00:00Z"));
    static final LocalDate DAY = Required.value(LocalDate.parse("2026-10-26"));
    static final Instant START = Required.value(ScheduleCutoff.localMinute(DAY,480,false));
    static final RoadPoint POINT = new RoadPoint(41.25,-95.93);
    static BookingCalculation.Input booking() {
        var tech = new Technician("a",START,Required.value(START.plusSeconds(21600)),360,0,Required.value(Set.of("service")),Required.value(List.of()),POINT,POINT,3);
        var visit = new Visit("old","old-job","service",START,Required.value(START.plusSeconds(14400)),60,POINT,"a",START,false);
        Map<String,DayPlan.RoadLeg> roads = new TreeMap<>();
        for (String from : List.of("a","old","new")) for (String to : List.of("old","new","a:return")) if (!from.equals(to))
            roads.put(from+">"+to,new DayPlan.RoadLeg(from.equals("old") ? 180 : 60,100));
        var day = new Day(Required.value(Map.of("a",tech)),Required.value(Map.of("old",visit)),new Arrangement(Required.value(Map.of("a",List.of("old")))),2,new Roads(roads,Required.value(Set.of())));
        var empty = new Day(Required.value(Map.of()),Required.value(Map.of()),new Arrangement(Required.value(Map.of())),0,new Roads(Required.value(Map.of()),Required.value(Set.of())));
        Map<LocalDate,Day> days = new TreeMap<>();
        for (LocalDate date : BookingCalendar.bookingDates(CAPTURED)) days.put(date,date.equals(DAY) ? day : empty);
        var snapshot = new BookingSnapshot("metro",CAPTURED,"config-rev","roads-rev",SchedulingPolicy.Rules.defaults(),new Rates(30,45,0,0,0),days);
        return new BookingCalculation.Input(snapshot,new BoundedBookingSearch.Request("new","service",30,POINT),BookingCalculation.Stage.INSERTION,"INSERTION",Required.value(Set.of()),null,0);
    }
    static CalculationProtocol.Request bookingRequest() { return CalculationProtocol.Request.of("BOOKING",5000,booking()); }
    static CalculationProtocol.Request dailyRequest() throws Exception {
        String draft;
        try (var stream = Required.value(ReplayFixture.class.getResourceAsStream("/daily-dataset-v1.json"))) { draft = new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
        draft = Required.value(draft.replace("TABU","CURRENT_CAPPED"));
        return CalculationProtocol.Request.of("DAILY",20000,new CalculationProtocol.DailyInput(DailyDataset.seal(draft),SchedulingPolicy.Rules.defaults()));
    }
}
