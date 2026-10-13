package dev.waterflex.scheduler.api;

/**
 * Turns a daily calculation's reason code into the public proposal's {@code reason}: the code, a colon, and a sentence
 * for operators. A code without a sentence here is passed through unchanged, never replaced, so a host can always take
 * the text before the first colon as the code. The codes come from {@code SchedulingPolicy.compare} and
 * {@code DailyCalculation}.
 */
final class ReasonText {
    private ReasonText() { }

    static String describe(String code) {
        if (code.isBlank()) throw new IllegalArgumentException("A calculation reason is required");
        String sentence = switch (code) {
            case "NO_POLICY_IMPROVEMENT" -> "the current routes are already as good as any plan found";
            case "COST_CEILING_EXCEEDED" -> "the fairest plan found costs more than the fairness budget allows";
            case "FAIRNESS_REGRESSION_WITHIN_ALLOWANCE" -> "the cheaper plan found spreads work less evenly than today's routes, which are within the fairness budget";
            case "OVERTIME_PROHIBITED" -> "the plan found would need overtime";
            case "OVERTIME_TARGET_MISMATCH" -> "the plan found does not reach the least overtime the search proved possible";
            case "NO_VALID_CANDIDATE" -> "the search found no plan that passes independent validation";
            case "INCOMPLETE_OR_INFEASIBLE_DEMAND" -> "neither the current routes nor any plan found places every appointment within its constraints";
            case "COST_REDUCTION" -> "lower fleet cost within policy";
            case "FAIRNESS_IMPROVEMENT" -> "work spread more evenly within the fairness budget";
            case "OVERTIME_REDUCTION" -> "less overtime";
            case "COMPLETE_ZERO_OVERTIME_DEMAND" -> "every appointment placed without overtime where the current routes could not be scored";
            default -> null;
        };
        return sentence == null ? code : code + ": " + sentence;
    }
}
