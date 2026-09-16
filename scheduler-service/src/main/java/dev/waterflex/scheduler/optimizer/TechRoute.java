package dev.waterflex.scheduler.optimizer;

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
    private String id;
    private Instant shiftStart;
    private Instant shiftEnd;
    private int maxDailyMinutes;
    private int maxOvertimeMinutes;
    private Set<String> qualifiedServiceIds;
    @PlanningListVariable(valueRangeProviderRefs = "visits")
    private List<PlanVisit> visits = new ArrayList<>();

    public TechRoute() { }
    public TechRoute(String id, Instant shiftStart, Instant shiftEnd, int maxDailyMinutes, int maxOvertimeMinutes,
                     Set<String> qualifiedServiceIds) {
        this.id = id; this.shiftStart = shiftStart; this.shiftEnd = shiftEnd;
        this.maxDailyMinutes = maxDailyMinutes; this.maxOvertimeMinutes = maxOvertimeMinutes;
        this.qualifiedServiceIds = qualifiedServiceIds;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getShiftStart() { return shiftStart; }
    public Instant getShiftEnd() { return shiftEnd; }
    public int getMaxDailyMinutes() { return maxDailyMinutes; }
    public int getMaxOvertimeMinutes() { return maxOvertimeMinutes; }
    public Set<String> getQualifiedServiceIds() { return qualifiedServiceIds; }
    public List<PlanVisit> getVisits() { return visits; }
    public void setVisits(List<PlanVisit> visits) { this.visits = visits; }
}
