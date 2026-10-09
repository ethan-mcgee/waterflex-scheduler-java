package dev.waterflex.scheduler;

/** Combines an insertion search with its optional bounded refinement for the public booking API. */
public final class BookingSearchPipeline {
    private BookingSearchPipeline() { }

    /** Preserve independently validated insertion offers when later optional refinement runs out of time. */
    public static BoundedBookingSearch.Result combine(BoundedBookingSearch.Result insertion, BoundedBookingSearch.Result refined,
            dev.waterflex.scheduler.optimizer.SchedulingPolicy.Rules policy) {
        java.util.Set<BoundedBookingSearch.Candidate> candidates = new java.util.LinkedHashSet<>(insertion.candidates());
        candidates.addAll(refined.candidates());
        int regular = Math.toIntExact(candidates.stream().filter(candidate -> candidate.overtimeDelta() <= 0)
                .map(candidate -> candidate.window()).distinct().count());
        boolean complete = refined.complete();
        // The completed insertion pass already measured the full horizon's confirmed utilization.
        long confirmed = insertion.confirmedRegularMinutes(), capacity = insertion.regularCapacityMinutes();
        java.util.Map<BoundedBookingSearch.Window, BoundedBookingSearch.Coverage> coverage = new java.util.LinkedHashMap<>();
        insertion.coverage().forEach(item -> coverage.put(item.window(), item));
        for (var item : refined.coverage()) {
            var previous = coverage.get(item.window());
            coverage.put(item.window(), previous == null ? item : new BoundedBookingSearch.Coverage(item.window(),
                    Math.max(previous.routesExamined(), item.routesExamined()),
                    previous.arrangementsExamined() + Math.max(0, item.arrangementsExamined() - 1),
                    previous.movesGenerated() + item.movesGenerated(), previous.candidateEvaluations() + item.candidateEvaluations(),
                    previous.complete() && item.complete(), item.stopReason()));
        }
        return new BoundedBookingSearch.Result(Required.value(java.util.List.copyOf(candidates)), Required.value(java.util.List.copyOf(coverage.values())), complete, regular,
                confirmed, capacity, policy.authorizeOvertime(regular, confirmed, capacity, complete), refined.stopReason());
    }
}
