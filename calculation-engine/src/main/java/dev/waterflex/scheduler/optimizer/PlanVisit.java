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
    public @Nullable TechRoute getTechnician() { return technician; }
    public void setTechnician(@Nullable TechRoute technician) { this.technician = technician; }
}
