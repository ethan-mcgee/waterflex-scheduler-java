package dev.waterflex.scheduler;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Versioned monetary contract. Decimal rates never pass through binary arithmetic. */
public final class Monetary {
    public static final String COST_MODEL = "exact-fleet-half-up-v3";
    public static final long MAX_CENTS = 9_007_199_254_740_991L;
    private static final BigDecimal METERS_PER_MILE = new BigDecimal("1609.344");
    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DENOMINATOR = Required.value(SIXTY.multiply(METERS_PER_MILE));
    private Monetary() { }

    /** Matches NUMERIC(65,30) storage, rejecting values that would be rounded on write. */
    public static BigDecimal rate(BigDecimal value) {
        BigDecimal canonical = Required.value(value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros());
        if (canonical.signum() < 0 || canonical.scale() > 30 || canonical.precision() - canonical.scale() > 35)
            throw new IllegalArgumentException("Invalid or out-of-range decimal monetary rate");
        return canonical;
    }
    /** Explicit legacy fixture bridge; new persisted and wire inputs use decimal strings. */
    public static BigDecimal legacy(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid legacy monetary rate");
        return rate(Required.value(BigDecimal.valueOf(value)));
    }
    public static String canonical(BigDecimal value) { return Required.value(rate(value).toPlainString()); }

    /** Exact cents numerator over the common 60 * 1609.344 denominator, also used for ordering. */
    public static BigDecimal numerator(long paid, long overtime, long meters,
            BigDecimal regular, BigDecimal overtimeRate, BigDecimal mileage) {
        if (paid < 0 || overtime < 0 || overtime > paid || meters < 0)
            throw new IllegalArgumentException("Invalid cost resources");
        if (regular.signum() < 0 || overtimeRate.signum() < 0 || mileage.signum() < 0)
            throw new IllegalArgumentException("Negative monetary rate");
        BigDecimal labor = regular.multiply(BigDecimal.valueOf(paid - overtime))
                .add(overtimeRate.multiply(BigDecimal.valueOf(overtime)));
        return Required.value(labor.multiply(METERS_PER_MILE).multiply(HUNDRED)
                .add(mileage.multiply(BigDecimal.valueOf(meters)).multiply(SIXTY).multiply(HUNDRED)));
    }
    /** Sum fleet resources first; this is the sole half-up rounding boundary. */
    public static long cents(long paid, long overtime, long meters,
            BigDecimal regular, BigDecimal overtimeRate, BigDecimal mileage) {
        long cents = numerator(paid, overtime, meters, regular, overtimeRate, mileage)
                .divide(DENOMINATOR, 0, RoundingMode.HALF_UP).longValueExact();
        if (cents < 0 || cents > MAX_CENTS) throw new ArithmeticException("Fleet cents exceed exact JSON integer range");
        return cents;
    }
    /** Buffered leg minutes: exact ceiling of seconds * (1 + pct) / 60, then the per-leg allowance. */
    public static long bufferedMinutes(long seconds, BigDecimal pct, long extraMinutes) {
        if (seconds < 0 || extraMinutes < 0 || pct.signum() < 0) throw new IllegalArgumentException("Invalid travel buffer inputs");
        long minutes = BigDecimal.valueOf(seconds).multiply(BigDecimal.ONE.add(pct))
                .divide(SIXTY, 0, RoundingMode.CEILING).longValueExact();
        return Math.addExact(minutes, extraMinutes);
    }
    public static BigDecimal dollars(long cents) {
        if (cents < -MAX_CENTS || cents > MAX_CENTS) throw new ArithmeticException("Cents out of range");
        return Required.value(BigDecimal.valueOf(cents, 2));
    }
}
