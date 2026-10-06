package dev.waterflex.scheduler.optimizer;

import dev.waterflex.scheduler.Required;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Pure policy calculations. Callers supply independently validated, confirmed work. */
public final class SchedulingPolicy {
    public static final String VERSION = "zero-overtime-four-hour-v2";
    private static final MathContext DECIMAL = Required.value(MathContext.DECIMAL128);
    private SchedulingPolicy() { }

    public record Rules(int regularWindowThreshold, BigDecimal utilizationThreshold,
                        BigDecimal fairnessAllowance, int bookingDeadlineMs) {
        public Rules {
            Objects.requireNonNull(utilizationThreshold);
            Objects.requireNonNull(fairnessAllowance);
            if (regularWindowThreshold < 0 || utilizationThreshold.signum() < 0
                    || utilizationThreshold.compareTo(BigDecimal.ONE) > 0
                    || fairnessAllowance.signum() < 0 || fairnessAllowance.compareTo(BigDecimal.ONE) > 0
                    || bookingDeadlineMs < 1000 || bookingDeadlineMs > 5000)
                throw new IllegalArgumentException("Invalid scheduling policy");
        }
        public static Rules defaults() {
            return new Rules(2, new BigDecimal("0.90"), new BigDecimal("0.02"), 5000);
        }
        public long costCeiling(long referenceCost) {
            if (referenceCost <= 0) return referenceCost;
            long ceiling = BigDecimal.valueOf(referenceCost).multiply(BigDecimal.ONE.add(fairnessAllowance))
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
            if (ceiling > dev.waterflex.scheduler.Monetary.MAX_CENTS) throw new ArithmeticException("Cost ceiling exceeds exact JSON integer range");
            return ceiling;
        }
        public boolean authorizeOvertime(int distinctRegularWindows, long confirmedRegularMinutes,
                                         long availableRegularMinutes, boolean searchComplete) {
            if (distinctRegularWindows < 0 || confirmedRegularMinutes < 0 || availableRegularMinutes < 0
                    || confirmedRegularMinutes > availableRegularMinutes)
                throw new IllegalArgumentException("Invalid confirmed utilization");
            return false; // Policy prohibition survives algorithm rollback and saved scarcity settings.
        }
    }

    public record Workload(String technicianId, long paidMinutes, long regularCapacityMinutes,
                           BigDecimal utilization) { }
    public record Fairness(BigDecimal variance, BigDecimal maximumUtilization, List<Workload> workloads) { }
    public record Metrics(long overtimeMinutes, long costCents, Fairness fairness) { }
    public record Decision(boolean accepted, String reason, long referenceCostCents,
                           long overtimeTargetMinutes, long costCeilingCents) { }

    public static Fairness fairness(List<Workload> workloads) {
        long capacity = 0, paid = 0;
        Set<String> ids = new HashSet<>();
        for (Workload item : workloads) {
            if (!ids.add(item.technicianId()) || item.regularCapacityMinutes() <= 0 || item.paidMinutes() < 0)
                throw new IllegalArgumentException("Invalid workload");
            capacity = Math.addExact(capacity, item.regularCapacityMinutes());
            paid = Math.addExact(paid, item.paidMinutes());
        }
        if (capacity == 0) return new Fairness(Required.value(BigDecimal.ZERO), Required.value(BigDecimal.ZERO), Required.value(List.of()));
        BigDecimal mean = BigDecimal.valueOf(paid).divide(BigDecimal.valueOf(capacity), DECIMAL);
        BigDecimal variance = BigDecimal.ZERO, maximum = BigDecimal.ZERO;
        List<Workload> normalized = new ArrayList<>();
        for (Workload item : workloads) {
            BigDecimal utilization = BigDecimal.valueOf(item.paidMinutes()).divide(BigDecimal.valueOf(item.regularCapacityMinutes()), DECIMAL);
            BigDecimal difference = utilization.subtract(mean);
            variance = variance.add(difference.multiply(difference, DECIMAL).multiply(BigDecimal.valueOf(item.regularCapacityMinutes()), DECIMAL));
            maximum = maximum.max(utilization);
            normalized.add(new Workload(item.technicianId(), item.paidMinutes(), item.regularCapacityMinutes(), Required.value(utilization)));
        }
        return new Fairness(Required.value(variance.divide(BigDecimal.valueOf(capacity), DECIMAL)), Required.value(maximum), Required.value(List.copyOf(normalized)));
    }

    /** Union absences, clip to the regular shift, then cap by the paid daily limit. */
    public static long regularCapacity(TechRoute route) {
        if (!route.getShiftStart().isBefore(route.getShiftEnd()) || route.getMaxDailyMinutes() <= 0 || route.getMaxOvertimeMinutes() < 0)
            throw new IllegalArgumentException("Invalid technician capacity");
        Instant cursor = route.getShiftStart();
        long available = 0;
        List<TechRoute.Unavailable> absences = new ArrayList<>(route.getUnavailable());
        absences.sort(Comparator.comparing((TechRoute.Unavailable absence) -> absence.start()));
        for (TechRoute.Unavailable absence : absences) {
            if (!absence.start().isBefore(absence.end())) throw new IllegalArgumentException("Invalid absence");
            Instant start = absence.start().isBefore(route.getShiftEnd()) ? absence.start() : route.getShiftEnd();
            if (cursor.isBefore(start)) available += Duration.between(cursor, start).toMinutes();
            if (cursor.isBefore(absence.end())) cursor = absence.end();
            if (!cursor.isBefore(route.getShiftEnd())) break;
        }
        if (cursor.isBefore(route.getShiftEnd())) available += Duration.between(cursor, route.getShiftEnd()).toMinutes();
        return Math.min(available, route.getMaxDailyMinutes());
    }

    public static Metrics measure(DayPlan plan) {
        RouteEvaluator.Result total = RouteEvaluator.evaluate(plan);
        if (!total.feasible()) throw new IllegalArgumentException("Policy requires a feasible schedule");
        Set<String> services = new HashSet<>();
        plan.getVisits().forEach(visit -> services.add(visit.getServiceId()));
        List<Workload> workloads = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) {
            long capacity = regularCapacity(Required.value(route));
            if (capacity == 0 || Collections.disjoint(services, route.getQualifiedServiceIds())) continue;
            var result = RouteEvaluator.evaluate(new DayPlan(Required.value(List.of(route)), route.getVisits(), plan.getMatrix(),
                    plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile(), plan.getTravelBufferPct(), plan.getTravelBufferMinutes()));
            workloads.add(new Workload(route.getId(), result.paidMinutes(), capacity, Required.value(BigDecimal.ZERO)));
        }
        return new Metrics(total.overtimeMinutes(), total.costCents(), fairness(workloads));
    }

    /** The same reference and rules must be persisted at preview and checked again at apply. */
    public static Decision compare(Metrics baseline, Metrics candidate, Metrics reference, Rules rules) {
        long ceiling = rules.costCeiling(reference.costCents());
        String reason;
        if (candidate.overtimeMinutes() != 0) reason = "OVERTIME_PROHIBITED";
        else if (candidate.overtimeMinutes() != reference.overtimeMinutes()) reason = "OVERTIME_TARGET_MISMATCH";
        else if (candidate.costCents() > ceiling) reason = "COST_CEILING_EXCEEDED";
        else if (candidate.overtimeMinutes() < baseline.overtimeMinutes()) reason = "OVERTIME_REDUCTION";
        else if (baseline.costCents() <= ceiling && candidate.fairness().variance().compareTo(baseline.fairness().variance()) > 0)
            reason = "FAIRNESS_REGRESSION_WITHIN_ALLOWANCE";
        else if (candidate.fairness().variance().compareTo(baseline.fairness().variance()) < 0) reason = "FAIRNESS_IMPROVEMENT";
        else if (candidate.costCents() < baseline.costCents()) reason = "COST_REDUCTION";
        else reason = "NO_POLICY_IMPROVEMENT";
        return new Decision(Set.of("OVERTIME_REDUCTION", "FAIRNESS_IMPROVEMENT", "COST_REDUCTION").contains(reason), reason,
                reference.costCents(), reference.overtimeMinutes(), ceiling);
    }

    /** Final ties between independently validated, policy-equivalent daily candidates. */
    public static int compareArrangements(DayPlan first, DayPlan second) {
        var left = assignments(first); var right = assignments(second);
        long leftChanges = left.stream().filter(item -> !item.technician().equals(item.original())).count();
        long rightChanges = right.stream().filter(item -> !item.technician().equals(item.original())).count();
        int changed = Long.compare(leftChanges, rightChanges);
        if (changed != 0) return changed;
        var order = Comparator.comparing((Assignment item) -> item.window()).thenComparing(item -> item.technician())
                .thenComparingInt(item -> item.position()).thenComparing(item -> item.visit());
        left.sort(order); right.sort(order);
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int compared = order.compare(left.get(i), right.get(i));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.size(), right.size());
    }
    private record Assignment(Instant window, String technician, int position, String visit, String original) { }
    private static List<Assignment> assignments(DayPlan plan) {
        List<Assignment> result = new ArrayList<>();
        for (TechRoute route : plan.getRoutes()) for (int position = 0; position < route.getVisits().size(); position++) {
            PlanVisit visit = Required.value(route.getVisits().get(position));
            result.add(new Assignment(visit.getWindowStart(), route.getId(), position, visit.getId(), visit.getOriginalTechnicianId()));
        }
        return result;
    }
}
