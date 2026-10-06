package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@PlanningEntity
public class TechRoute {
    @PlanningId
    private @Nullable String id;
    private @Nullable Instant shiftStart;
    private @Nullable Instant shiftEnd;
    private @Nullable Integer maxDailyMinutes;
    private @Nullable Integer maxOvertimeMinutes;
    private @Nullable Set<String> qualifiedServiceIds;
    private List<Unavailable> unavailable = new ArrayList<>();
    private boolean frozen;
    @PlanningListVariable(valueRangeProviderRefs = "visits")
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
        return new PlanFacts.Technician(getId(), getShiftStart(), getShiftEnd(), getMaxDailyMinutes(), getMaxOvertimeMinutes(), getQualifiedServiceIds(), getUnavailable());
    }
    void freeze() {
        qualifiedServiceIds = Required.value(Set.copyOf(getQualifiedServiceIds()));
        unavailable = Required.value(List.copyOf(unavailable)); frozen = true;
    }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
}
