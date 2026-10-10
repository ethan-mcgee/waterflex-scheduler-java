package dev.waterflex.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonetaryTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    @Test void auditExampleAndHalfCentBoundariesAreExact() {
        assertEquals(8509, Monetary.cents(255, 0, 0, d("20.02"), d("0"), d("0")));
        assertEquals(1, Monetary.cents(1, 0, 0, d("0.3"), d("0"), d("0")));
        assertEquals(0, Monetary.cents(1, 0, 0, d("0.299999999999999999999999999999"), d("0"), d("0")));
        assertEquals(1, Monetary.cents(1, 0, 0, d("0.300000000000000000000000000001"), d("0"), d("0")));
    }
    @Test void overtimeAndExactMetersPerMileAgreeWithIndependentIntegerFractions() {
        assertEquals(oracle(255, 17, 1609344, d("20.02"), d("30.03"), d("0.67")),
                Monetary.cents(255, 17, 1609344, d("20.02"), d("30.03"), d("0.67")));
        assertEquals(67000, Monetary.cents(0, 0, 1609344, d("0"), d("0"), d("0.67")));
        Random random = new Random(3042026);
        for (int index = 0; index < 1000; index++) {
            long paid = random.nextInt(100000), overtime = random.nextInt((int) paid + 1), meters = random.nextInt(Integer.MAX_VALUE);
            BigDecimal regular = BigDecimal.valueOf(random.nextInt(100000), random.nextInt(8));
            BigDecimal premium = BigDecimal.valueOf(random.nextInt(100000), random.nextInt(8));
            BigDecimal mileage = BigDecimal.valueOf(random.nextInt(1000), random.nextInt(10));
            assertEquals(oracle(paid, overtime, meters, Required.value(regular), Required.value(premium), Required.value(mileage)),
                    Monetary.cents(paid, overtime, meters, Required.value(regular), Required.value(premium), Required.value(mileage)), "sample " + index);
        }
    }
    @Test void onlyFleetTotalRoundsAndBookingDeltaUsesTwoRoundedTotals() {
        long first = Monetary.cents(1, 0, 0, d("0.3"), d("0"), d("0"));
        long fleet = Monetary.cents(2, 0, 0, d("0.3"), d("0"), d("0"));
        assertEquals(1, fleet); assertEquals(2, first + first);
        assertEquals(0, fleet - first); // Adding a half-cent job to a half-cent fleet does not add a rounded cent.
        BigDecimal sum = Monetary.numerator(127, 0, 123, d("20.02"), d("0"), d("0.67"))
                .add(Monetary.numerator(128, 0, 456, d("20.02"), d("0"), d("0.67")));
        assertEquals(0, sum.compareTo(Monetary.numerator(255, 0, 579, d("20.02"), d("0"), d("0.67"))));
    }
    @Test void invalidRatesResourcesAndOverflowFailInsteadOfSaturating() {
        for (String invalid : new String[]{"-1", "1e-31", "1e35"}) assertThrows(IllegalArgumentException.class, () -> Monetary.rate(d(Required.value(invalid))));
        assertThrows(IllegalArgumentException.class, () -> Monetary.legacy(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Monetary.legacy(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Monetary.cents(1, 2, 0, d("1"), d("1"), d("1")));
        assertThrows(IllegalArgumentException.class, () -> Monetary.cents(-1, 0, 0, d("1"), d("1"), d("1")));
        assertThrows(IllegalArgumentException.class, () -> Monetary.cents(1, 0, -1, d("1"), d("1"), d("1")));
        assertThrows(ArithmeticException.class, () -> Monetary.cents(Long.MAX_VALUE, 0, 0, d("1"), d("1"), d("0")));
        assertThrows(ArithmeticException.class, () -> Monetary.cents(60, 0, 0, d("10000000000000000000000000000000000"), d("1"), d("0")));
        assertEquals(Monetary.MAX_CENTS, Monetary.cents(60, 0, 0, d("90071992547409.91"), d("0"), d("0")));
        assertThrows(ArithmeticException.class, () -> Monetary.cents(60, 0, 0, d("90071992547409.92"), d("0"), d("0")));
    }
    @Test void decimalWireValuesAndLegacyRenderingAreCanonical() throws Exception {
        assertEquals("20.02", Monetary.canonical(Monetary.legacy(20.02)));
        var rates = new BookingSnapshot.Rates(d("20.0200"), d("30.0300"), d("0.6700"), d("0.200"), 5);
        var json = new ObjectMapper().valueToTree(rates);
        assertEquals("20.02", Required.value(json).path("regularHourly").textValue());
        assertEquals("30.03", json.path("overtimeHourly").textValue());
        assertEquals("0.67", json.path("mileagePerMile").textValue());
        assertEquals("0.2", json.path("travelBufferPct").textValue());
        assertEquals("0", Monetary.canonical(d("0.0000")));
    }
    @Test void bufferedTravelUsesExactDecimalCeiling() {
        // Binary arithmetic gives 1800 * 1.1 / 60 = 33.00000000000001 and rounds up to 34.
        assertEquals(33, Monetary.bufferedMinutes(1800, d("0.1"), 0));
        assertEquals(38, Monetary.bufferedMinutes(1800, d("0.1"), 5));
        assertEquals(17, Monetary.bufferedMinutes(900, d("0.1"), 0));
        assertEquals(0, Monetary.bufferedMinutes(0, d("0.25"), 0));
        assertEquals(1, Monetary.bufferedMinutes(1, d("0"), 0));
        assertThrows(IllegalArgumentException.class, () -> Monetary.bufferedMinutes(-1, d("0.1"), 0));
        assertThrows(IllegalArgumentException.class, () -> Monetary.bufferedMinutes(60, d("-0.1"), 0));
        assertThrows(IllegalArgumentException.class, () -> Monetary.bufferedMinutes(60, d("0.1"), -1));
    }
    @Test void bufferedTravelAgreesWithIndependentBasisPointIntegers() {
        Random random = new Random(20261007);
        for (int i = 0; i < 20_000; i++) {
            long seconds = random.nextInt(86_400 * 2); long basis = random.nextInt(10_001); long extra = random.nextInt(31);
            long expected = (seconds * (10_000 + basis) + 599_999) / 600_000 + extra;
            assertEquals(expected, Monetary.bufferedMinutes(seconds, Required.value(BigDecimal.valueOf(basis, 4)), extra), seconds + "s " + basis + "bp");
        }
    }
    /** Independent generic fractions: labor is minutes/60, miles are meters*125/201168. */
    static long oracle(long paid, long overtime, long meters, BigDecimal regular, BigDecimal premium, BigDecimal mileage) {
        Fraction amount = fraction(regular).times(paid - overtime).over(60)
                .plus(fraction(premium).times(overtime).over(60))
                .plus(fraction(mileage).times(meters).times(125).over(201168)).times(100);
        BigInteger[] parts = amount.numerator().divideAndRemainder(amount.denominator());
        return parts[0].add(parts[1].shiftLeft(1).compareTo(amount.denominator()) >= 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }
    private static Fraction fraction(BigDecimal value) {
        return value.scale() >= 0 ? new Fraction(Required.value(value.unscaledValue()), Required.value(BigInteger.TEN.pow(value.scale())))
                : new Fraction(Required.value(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale()))), Required.value(BigInteger.ONE));
    }
    private record Fraction(BigInteger numerator, BigInteger denominator) {
        Fraction times(long multiplier) { return new Fraction(Required.value(numerator.multiply(BigInteger.valueOf(multiplier))), denominator); }
        Fraction over(long divisor) { return new Fraction(numerator, Required.value(denominator.multiply(BigInteger.valueOf(divisor)))); }
        Fraction plus(Fraction other) { return new Fraction(Required.value(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator))), Required.value(denominator.multiply(other.denominator))); }
    }
}
