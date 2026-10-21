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
    /**
     * The cost rates' contract: at most four decimal places, an hourly rate (regular or overtime) of at most 500 and a
     * mileage rate of at most 25 per mile. Every rate is then a whole number of 1e-4 units, so a fleet's cost is exact in
     * long arithmetic: under these caps it fits a long for up to about 11 million paid minutes and 5.9 million km of
     * driving in one day (about 7,600 technicians on full 24-hour days), and a larger day is refused rather than priced.
     */
    public static final int RATE_SCALE = 4;
    public static final BigDecimal MAX_HOURLY = new BigDecimal("500"), MAX_MILEAGE = new BigDecimal("25");
    /**
     * The numerator over 60 * 1609.344 in whole units: with every rate in 1e-4 units, the numerator times 10^7 / 192 is
     * (regular * (paid - overtime) + overtime rate * overtime) * 83820 + mileage * meters * 3125, over 50292 * 10^4.
     */
    private static final long LABOR_UNITS = 83820, MILEAGE_UNITS = 3125, DENOMINATOR_UNITS = 502_920_000L;
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

    /** A cost rate inside the contract ({@link #RATE_SCALE}, {@code cap}), canonical; anything else is refused. */
    public static BigDecimal costRate(BigDecimal value, BigDecimal cap) {
        BigDecimal canonical = rate(value);
        if (canonical.scale() > RATE_SCALE || canonical.compareTo(cap) > 0)
            throw new IllegalArgumentException("A cost rate has at most " + RATE_SCALE + " decimal places and is at most " + cap.toPlainString());
        return canonical;
    }
    public static BigDecimal hourly(BigDecimal value) { return costRate(value, MAX_HOURLY); }
    public static BigDecimal mileage(BigDecimal value) { return costRate(value, MAX_MILEAGE); }

    /** A revision's three cost rates, each also as a whole number of 1e-4 units, the form every cost is computed in. */
    public record Rates(BigDecimal regular, BigDecimal overtime, BigDecimal mileage, long regularUnits, long overtimeUnits, long mileageUnits) {
        public Rates {
            regular = Monetary.hourly(regular); overtime = Monetary.hourly(overtime); mileage = Monetary.mileage(mileage);
            if (regularUnits != units(regular) || overtimeUnits != units(overtime) || mileageUnits != units(mileage))
                throw new IllegalArgumentException("Rate units differ from the decimal rates");
        }
    }
    public static Rates rates(BigDecimal regular, BigDecimal overtime, BigDecimal mileage) {
        BigDecimal hourlyRegular = hourly(regular), hourlyOvertime = hourly(overtime), perMile = mileage(mileage);
        return new Rates(hourlyRegular, hourlyOvertime, perMile, units(hourlyRegular), units(hourlyOvertime), units(perMile));
    }
    /** A contract rate in 1e-4 units: exact, as it has at most four places and is at most 500. */
    private static long units(BigDecimal rate) { return rate.movePointRight(RATE_SCALE).longValueExact(); }
    private static void resources(long paid, long overtime, long meters) {
        if (paid < 0 || overtime < 0 || overtime > paid || meters < 0)
            throw new IllegalArgumentException("Invalid cost resources");
    }
    /** The integer numerator over {@link #DENOMINATOR_UNITS}; a day past a long's range is refused, never approximated. */
    private static long numerator(long paid, long overtime, long meters, Rates rates) {
        try {
            long labor = Math.addExact(Math.multiplyExact(rates.regularUnits(), paid - overtime), Math.multiplyExact(rates.overtimeUnits(), overtime));
            return Math.addExact(Math.multiplyExact(labor, LABOR_UNITS), Math.multiplyExact(Math.multiplyExact(rates.mileageUnits(), meters), MILEAGE_UNITS));
        } catch (ArithmeticException beyond) {
            throw new ArithmeticException("Fleet cost exceeds the cost model's range");
        }
    }
    /** The fleet's cents from its summed resources, rounded half up once: the same cents as the decimal form below. */
    public static long cents(long paid, long overtime, long meters, Rates rates) {
        resources(paid, overtime, meters);
        long numerator = numerator(paid, overtime, meters, rates);
        // Half up for a numerator of at least zero: the quotient, plus one where twice the remainder reaches the denominator.
        long remainder = numerator % DENOMINATOR_UNITS;
        return bounded(numerator / DENOMINATOR_UNITS + (2 * remainder >= DENOMINATOR_UNITS ? 1 : 0));
    }
    /** The order of two resource sets' exact costs, as their integer numerators compare. */
    public static int compare(long paid, long overtime, long meters, long otherPaid, long otherOvertime, long otherMeters, Rates rates) {
        resources(paid, overtime, meters); resources(otherPaid, otherOvertime, otherMeters);
        return Long.compare(numerator(paid, overtime, meters, rates), numerator(otherPaid, otherOvertime, otherMeters, rates));
    }
    private static long bounded(long cents) {
        if (cents < 0 || cents > MAX_CENTS) throw new ArithmeticException("Fleet cents exceed exact JSON integer range");
        return cents;
    }

    /**
     * Exact cents numerator over the common 60 * 1609.344 denominator, for any nonnegative decimal rates: the independent
     * evaluators' form (RouteEvaluator, DayScoreCalculator, SchedulingPolicy), against which the integer form is checked.
     */
    public static BigDecimal numerator(long paid, long overtime, long meters,
            BigDecimal regular, BigDecimal overtimeRate, BigDecimal mileage) {
        resources(paid, overtime, meters);
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
        return bounded(numerator(paid, overtime, meters, regular, overtimeRate, mileage)
                .divide(DENOMINATOR, 0, RoundingMode.HALF_UP).longValueExact());
    }
    /** Buffered leg minutes: exact ceiling of seconds * (1 + pct) / 60, then the per-leg allowance. */
    public static long bufferedMinutes(long seconds, BigDecimal pct, long extraMinutes) {
        if (seconds < 0 || extraMinutes < 0 || pct.signum() < 0) throw new IllegalArgumentException("Invalid travel buffer inputs");
        long minutes = BigDecimal.valueOf(seconds).multiply(BigDecimal.ONE.add(pct))
                .divide(SIXTY, 0, RoundingMode.CEILING).longValueExact();
        return Math.addExact(minutes, extraMinutes);
    }
}
