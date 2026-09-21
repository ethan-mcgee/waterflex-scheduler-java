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
                     String originalTechnicianId, Instant originalPlannedStart) {
        this.id = id; this.serviceId = serviceId; this.windowStart = windowStart; this.windowEnd = windowEnd;
        this.durationMinutes = durationMinutes; this.originalTechnicianId = originalTechnicianId;
        this.originalPlannedStart = originalPlannedStart;
    }
    public String getId() { return Required.value(id, "PlanVisit.id before initialization"); }
    public void setId(String id) { this.id = id; }
    public String getServiceId() { return Required.value(serviceId, "PlanVisit.serviceId before initialization"); }
    public Instant getWindowStart() { return Required.value(windowStart, "PlanVisit.windowStart before initialization"); }
    public Instant getWindowEnd() { return Required.value(windowEnd, "PlanVisit.windowEnd before initialization"); }
    public int getDurationMinutes() { return Required.value(durationMinutes, "PlanVisit.durationMinutes before initialization"); }
    public String getOriginalTechnicianId() { return Required.value(originalTechnicianId, "PlanVisit.originalTechnicianId before initialization"); }
    public Instant getOriginalPlannedStart() { return Required.value(originalPlannedStart, "PlanVisit.originalPlannedStart before initialization"); }
    public @Nullable TechRoute getTechnician() { return technician; }
    public void setTechnician(@Nullable TechRoute technician) { this.technician = technician; }
}
