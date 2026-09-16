package dev.waterflex.scheduler.optimizer;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.InverseRelationShadowVariable;

import java.time.Instant;

@PlanningEntity
public class PlanVisit {
    @PlanningId
    private String id;
    private String serviceId;
    private Instant windowStart;
    private Instant windowEnd;
    private int durationMinutes;
    private String originalTechnicianId;
    private Instant originalPlannedStart;
    @InverseRelationShadowVariable(sourceVariableName = "visits")
    private TechRoute technician;

    public PlanVisit() { }
    public PlanVisit(String id, String serviceId, Instant windowStart, Instant windowEnd, int durationMinutes,
                     String originalTechnicianId, Instant originalPlannedStart) {
        this.id = id; this.serviceId = serviceId; this.windowStart = windowStart; this.windowEnd = windowEnd;
        this.durationMinutes = durationMinutes; this.originalTechnicianId = originalTechnicianId;
        this.originalPlannedStart = originalPlannedStart;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getServiceId() { return serviceId; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public int getDurationMinutes() { return durationMinutes; }
    public String getOriginalTechnicianId() { return originalTechnicianId; }
    public Instant getOriginalPlannedStart() { return originalPlannedStart; }
    public TechRoute getTechnician() { return technician; }
    public void setTechnician(TechRoute technician) { this.technician = technician; }
}
