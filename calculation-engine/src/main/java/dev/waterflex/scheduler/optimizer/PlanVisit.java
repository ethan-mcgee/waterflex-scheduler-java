package dev.waterflex.scheduler.optimizer;

import org.jspecify.annotations.Nullable;
import dev.waterflex.scheduler.Required;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;

import java.time.Instant;

@PlanningEntity
public class PlanVisit {
    @PlanningId
    private @Nullable String id;
    private @Nullable String serviceId;
    private @Nullable Instant windowStart;
    private @Nullable Instant windowEnd;
    private @Nullable Integer durationMinutes;
    private @Nullable String originalTechnicianId;
    private @Nullable Instant originalPlannedStart;
    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private @Nullable TechRoute technician;
    /** This visit's road matrix number under one revision, so scoring reads a field instead of probing the identity map. */
    private record Numbered(PlanFacts facts, int number) { }
    // Written once as a whole record, so a reader on another thread sees either nothing or a complete one.
    private @Nullable Numbered numbered;

    public PlanVisit() { }
    public PlanVisit(String id, String serviceId, Instant windowStart, Instant windowEnd, int durationMinutes,
                     @Nullable String originalTechnicianId, @Nullable Instant originalPlannedStart) {
        this.id = id; this.serviceId = serviceId; this.windowStart = windowStart; this.windowEnd = windowEnd;
        this.durationMinutes = durationMinutes; this.originalTechnicianId = originalTechnicianId;
        this.originalPlannedStart = originalPlannedStart;
    }
    public String getId() { return Required.value(id, "PlanVisit.id before initialization"); }
    PlanFacts.Visit facts() {
        return new PlanFacts.Visit(getId(), getServiceId(), getWindowStart(), getWindowEnd(), getDurationMinutes(), getOriginalTechnicianId(), getOriginalPlannedStart());
    }
    public String getServiceId() { return Required.value(serviceId, "PlanVisit.serviceId before initialization"); }
    public Instant getWindowStart() { return Required.value(windowStart, "PlanVisit.windowStart before initialization"); }
    public Instant getWindowEnd() { return Required.value(windowEnd, "PlanVisit.windowEnd before initialization"); }
    public int getDurationMinutes() { return Required.value(durationMinutes, "PlanVisit.durationMinutes before initialization"); }
    public @Nullable String getOriginalTechnicianId() { return originalTechnicianId; }
    public @Nullable Instant getOriginalPlannedStart() { return originalPlannedStart; }
    /** This visit's number in the revision's road matrix, or -1 when no road names it; the identity never changes once set. */
    int roadNumber(PlanFacts facts) {
        Numbered known = numbered;
        if (known != null && known.facts() == facts) return known.number();
        int number = facts.roads().number(getId());
        numbered = new Numbered(facts, number);
        return number;
    }
    public @Nullable TechRoute getTechnician() { return technician; }
    public void setTechnician(@Nullable TechRoute technician) { this.technician = technician; }
}
