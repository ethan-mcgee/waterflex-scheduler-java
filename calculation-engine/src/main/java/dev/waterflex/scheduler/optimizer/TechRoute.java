package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPinToIndex;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@PlanningEntity
public class TechRoute {
    /**
     * What scoring derives from this route's frozen facts under one revision: its regular capacity and whether it can
     * serve the revision's demand. Kept so a move reads two fields instead of sorting the absences and comparing service
     * sets again. A solver clone shares it with its source, as both score against the same revision.
     */
    record Scoring(PlanFacts facts, long capacity, boolean eligible) { }
    /**
     * This route's shift as the timing search reads it: the shift start as nanoseconds after the epoch (null past a
     * long's reach, when the search reads the instants), and the shift end and working intervals as offsets from the
     * shift start. Computed once, when the route is frozen with its revision (its absences may change until then); a
     * planning clone is given the field by the cloner (Timefold docs 10.9.6.1) and never recomputes. An unfrozen route
     * derives it on every call.
     */
    record Clock(@Nullable Long origin, long shiftEnd, List<RouteTimingSearch.Block> blocks) { }
    /** This route's road matrix number under one revision, so timing reads a field instead of probing the identity map. */
    private record Numbered(PlanFacts facts, int number) { }
    // Each is written once as a whole record, so a reader on another thread sees either nothing or a complete one.
    private @Nullable Scoring scoring;
    private @Nullable Numbered numbered;
    private @Nullable Clock clock;
    @PlanningId
    private @Nullable String id;
    private @Nullable Instant shiftStart;
    private @Nullable Instant shiftEnd;
    private @Nullable Integer maxDailyMinutes;
    private @Nullable Integer maxOvertimeMinutes;
    private @Nullable Set<String> qualifiedServiceIds;
    private List<Unavailable> unavailable = new ArrayList<>();
    private boolean frozen;
    @PlanningPinToIndex private int pinnedPrefix;
    @PlanningListVariable(valueRangeProviderRefs = "visits", allowsUnassignedValues = true)
    private List<PlanVisit> visits = new ArrayList<>();

    public TechRoute() { }
    public TechRoute(String id, Instant shiftStart, Instant shiftEnd, int maxDailyMinutes, int maxOvertimeMinutes,
                     Set<String> qualifiedServiceIds) {
        this.id = id; this.shiftStart = shiftStart; this.shiftEnd = shiftEnd;
        this.maxDailyMinutes = maxDailyMinutes; this.maxOvertimeMinutes = maxOvertimeMinutes;
        this.qualifiedServiceIds = qualifiedServiceIds;
    }
    public String getId() { return Required.value(id, "TechRoute.id before initialization"); }
    public Instant getShiftStart() { return Required.value(shiftStart, "TechRoute.shiftStart before initialization"); }
    public Instant getShiftEnd() { return Required.value(shiftEnd, "TechRoute.shiftEnd before initialization"); }
    public int getMaxDailyMinutes() { return Required.value(maxDailyMinutes, "TechRoute.maxDailyMinutes before initialization"); }
    public int getMaxOvertimeMinutes() { return Required.value(maxOvertimeMinutes, "TechRoute.maxOvertimeMinutes before initialization"); }
    public Set<String> getQualifiedServiceIds() { return Required.value(qualifiedServiceIds, "TechRoute.qualifiedServiceIds before initialization"); }
    public record Unavailable(Instant start, Instant end) { }
    public List<Unavailable> getUnavailable() { return unavailable; }
    public void setUnavailable(List<Unavailable> unavailable) {
        PlanFacts.check(!frozen, "technician facts are immutable after plan initialization");
        this.unavailable = new ArrayList<>(unavailable);
    }
    PlanFacts.Technician facts() {
        PlanFacts.check(pinnedPrefix >= 0 && pinnedPrefix <= visits.size(), "pinned prefix exceeds assigned route");
        return new PlanFacts.Technician(getId(), getShiftStart(), getShiftEnd(), getMaxDailyMinutes(), getMaxOvertimeMinutes(), getQualifiedServiceIds(), getUnavailable(),
                Required.value(visits.subList(0, pinnedPrefix).stream().map(visit -> visit.getId()).toList()));
    }
    /** This route's scoring facts under this revision; kept only once the route is frozen, as its facts may change before. */
    Scoring scoring(PlanFacts facts) {
        Scoring known = scoring;
        if (known != null && known.facts() == facts) return known;
        Scoring derived = new Scoring(facts, SchedulingPolicy.regularCapacity(this), !Collections.disjoint(facts.demandServices(), getQualifiedServiceIds()));
        if (frozen) scoring = derived;
        return derived;
    }
    /** This route's clock: the one computed at freeze, or derived anew while the route is unfrozen. */
    Clock clock() {
        Clock known = clock;
        return known != null ? known : derive();
    }
    private Clock derive() {
        Instant origin = getShiftStart();
        return new Clock(RouteTimeline.epochNanos(origin), RouteTimeline.offset(origin, getShiftEnd()), RouteTimeline.available(this, origin));
    }
    /** This route's number in the revision's road matrix, or -1 when no road names it; the identity never changes once set. */
    int roadNumber(PlanFacts facts) {
        Numbered known = numbered;
        if (known != null && known.facts() == facts) return known.number();
        int number = facts.roads().number(getId());
        numbered = new Numbered(facts, number);
        return number;
    }
    void freeze() {
        qualifiedServiceIds = Required.value(Set.copyOf(getQualifiedServiceIds()));
        unavailable = Required.value(List.copyOf(unavailable)); frozen = true;
        clock = derive();
    }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
    public int getPinnedPrefix() { return pinnedPrefix; }
    public void setPinnedPrefix(int prefix) {
        PlanFacts.check(!frozen, "pinning is immutable after plan initialization");
        PlanFacts.check(prefix >= 0 && prefix <= visits.size(), "pinned prefix exceeds assigned route");
        pinnedPrefix = prefix;
    }
}
