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
    /**
     * This visit's road matrix number with the serial of the matrix that gave it, the serial in the high half and the
     * number in the low half, so scoring reads one field of the visit itself instead of probing the identity map or
     * following a reference to a separate record; 0 is no number yet (no matrix has serial 0). Volatile keeps the long
     * whole (JLS 17.7), so a reader on another thread sees either an earlier pair or this one, never half of each.
     */
    private volatile long numbered;

    /**
     * This visit's window as nanoseconds after the epoch and its duration in nanoseconds, so a timing leg subtracts two
     * longs instead of reading two instants. Computed once, by the constructor that sets the window and duration, which
     * never change after it. A planning clone is made with the no-arg constructor and then given every field, this one
     * among them (Timefold docs 10.9.6.1; records are never deep cloned), so it never recomputes. Null when a window end
     * lies past a long nanosecond count of the epoch (after 2262): the search then reads the instants. The field is not
     * final because the cloner writes it after construction.
     */
    record Times(long windowStart, long windowEnd, long duration) { }
    private @Nullable Times times;

    public PlanVisit() { }
    public PlanVisit(String id, String serviceId, Instant windowStart, Instant windowEnd, int durationMinutes,
                     @Nullable String originalTechnicianId, @Nullable Instant originalPlannedStart) {
        this.id = id; this.serviceId = serviceId; this.windowStart = windowStart; this.windowEnd = windowEnd;
        this.durationMinutes = durationMinutes; this.originalTechnicianId = originalTechnicianId;
        this.originalPlannedStart = originalPlannedStart;
        Long start = RouteTimeline.epochNanos(windowStart), end = RouteTimeline.epochNanos(windowEnd);
        this.times = start == null || end == null ? null : new Times(start, end, RouteTimeline.nanos(durationMinutes));
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
        RoadMatrix roads = facts.roads();
        long known = numbered;
        if ((int) (known >>> 32) == roads.serial()) return (int) known;
        int number = roads.number(getId());
        numbered = (long) roads.serial() << 32 | (number & 0xFFFFFFFFL);
        return number;
    }
    /** These times, or null (see {@link Times}). */
    @Nullable Times times() { return times; }
    public @Nullable TechRoute getTechnician() { return technician; }
    public void setTechnician(@Nullable TechRoute technician) { this.technician = technician; }
}
