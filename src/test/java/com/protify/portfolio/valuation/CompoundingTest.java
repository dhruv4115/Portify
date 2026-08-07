package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * {@link Compounding} replaces {@code Math.pow} so that no {@code double} touches the annualised
 * return (/CLAUDE.md's "Never" list). That is only worth doing if it is actually right, so every
 * assertion here is against a value that can be checked by hand or looked up, not against
 * whatever the implementation happens to produce.
 */
class CompoundingTest {

    private static BigDecimal at(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.HALF_UP);
    }

    /** {@code 1.1^5 = 1.61051} exactly — a five-digit decimal, so an implementation that is even
     * slightly wrong cannot round its way into agreeing. */
    @Test
    void shouldRaiseToAnIntegerPowerExactly() {
        BigDecimal result = Compounding.pow(new BigDecimal("1.1"), new BigDecimal("5"));
        assertThat(at(result, 10)).isEqualByComparingTo("1.6105100000");
    }

    /** A fractional exponent is the whole reason this class exists — {@code BigDecimal.pow} takes
     * an {@code int}. √2 to ten places. */
    @Test
    void shouldRaiseToAFractionalPower() {
        BigDecimal result = Compounding.pow(new BigDecimal("2"), new BigDecimal("0.5"));
        assertThat(at(result, 10)).isEqualByComparingTo("1.4142135624");
    }

    /** The annualisation case: 73 five-day periods make up a year, so a 10% five-day return
     * annualises to {@code 1.1^73}. Checks the reduction steps hold up over a large exponent. */
    @Test
    void shouldRaiseToALargeExponentWithoutLosingPrecision() {
        BigDecimal result = Compounding.pow(new BigDecimal("1.1"), new BigDecimal("73"));
        // 1.1^73 = 1051.15347... — cross-checked against 1.1^70 * 1.1^3.
        BigDecimal viaParts = new BigDecimal("1.1").pow(70, Compounding.MC)
                .multiply(new BigDecimal("1.331"), Compounding.MC);
        assertThat(at(result, 6)).isEqualByComparingTo(at(viaParts, 6));
    }

    /** Annualising a window of exactly one year must return the window's own return, digit for
     * digit — not a 34-digit approximation that rounds to it. */
    @Test
    void shouldReturnTheBaseUnchangedForAnExponentOfOne() {
        BigDecimal base = new BigDecimal("1.10270160");
        assertThat(Compounding.pow(base, BigDecimal.ONE)).isEqualByComparingTo(base);
    }

    @Test
    void shouldReturnOneForAnExponentOfZero() {
        assertThat(Compounding.pow(new BigDecimal("7.5"), BigDecimal.ZERO)).isEqualByComparingTo("1");
    }

    @Test
    void shouldReturnOneForABaseOfOne() {
        assertThat(Compounding.pow(BigDecimal.ONE, new BigDecimal("0.37"))).isEqualByComparingTo("1");
    }

    @Test
    void shouldHandleANegativeExponent() {
        // 1.25^-1 = 0.8
        assertThat(at(Compounding.pow(new BigDecimal("1.25"), new BigDecimal("-1")), 10))
                .isEqualByComparingTo("0.8000000000");
    }

    @Test
    void shouldComputeNaturalLogarithmsAcrossTheReductionBoundaries() {
        // Values either side of the [0.5, 2) window ln() reduces into, so both the halving and
        // the doubling loop are exercised.
        assertThat(at(Compounding.ln(new BigDecimal("2")), 12)).isEqualByComparingTo("0.693147180560");
        assertThat(at(Compounding.ln(new BigDecimal("0.25")), 12)).isEqualByComparingTo("-1.386294361120");
        assertThat(at(Compounding.ln(new BigDecimal("1000")), 12)).isEqualByComparingTo("6.907755278982");
        assertThat(Compounding.ln(BigDecimal.ONE)).isEqualByComparingTo("0");
    }

    @Test
    void shouldComputeExponentials() {
        assertThat(at(Compounding.exp(BigDecimal.ONE), 12)).isEqualByComparingTo("2.718281828459");
        assertThat(at(Compounding.exp(new BigDecimal("-2")), 12)).isEqualByComparingTo("0.135335283237");
        assertThat(Compounding.exp(BigDecimal.ZERO)).isEqualByComparingTo("1");
    }

    @Test
    void shouldRoundTripThroughLogarithmAndExponential() {
        BigDecimal x = new BigDecimal("1234.5678");
        assertThat(at(Compounding.exp(Compounding.ln(x)), 20)).isEqualByComparingTo(at(x, 20));
    }

    @Test
    void shouldComputeSquareRoots() {
        assertThat(at(Compounding.sqrt(new BigDecimal("365")), 10)).isEqualByComparingTo("19.1049731745");
        assertThat(at(Compounding.sqrt(new BigDecimal("0.02")), 10)).isEqualByComparingTo("0.1414213562");
        assertThat(Compounding.sqrt(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    /** A negative growth factor and a non-positive logarithm argument are the caller's problem to
     * interpret, not this class's to guess at — {@link AnalyticsService} reports {@code null}. */
    @Test
    void shouldRejectNonPositiveArgumentsRatherThanInventAnAnswer() {
        assertThatThrownBy(() -> Compounding.pow(new BigDecimal("-1.5"), new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Compounding.pow(BigDecimal.ZERO, new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Compounding.ln(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Compounding.sqrt(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
