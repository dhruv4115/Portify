package com.protify.portfolio.valuation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Fractional powers and square roots on {@link BigDecimal}, for {@link AnalyticsService}'s
 * {@code (1 + TWR)^(365/days) − 1} and its annualised volatility.
 *
 * <p><b>Why this class exists at all.</b> {@code BigDecimal.pow} takes an {@code int} exponent,
 * and {@code 365/days} is almost never an integer. The one-line alternative is
 * {@code Math.pow(base.doubleValue(), …)}, and /CLAUDE.md's "Never" list bans {@code double}
 * anywhere near a financial calculation — the ArchUnit rule only checks method signatures, so a
 * {@code double} local here would pass the build and still be the wrong answer. Everything below
 * is {@code BigDecimal} at {@link #MC}'s 34 significant digits, which is far more than the four
 * decimal places the results are reported to.
 *
 * <p>{@code pow(base, exp) = exp(exp × ln base)}. {@code ln} uses the {@code atanh} series
 * {@code ln x = 2·(z + z³/3 + z⁵/5 + …)} with {@code z = (x−1)/(x+1)}, after halving {@code x}
 * into {@code [0.5, 2)} so {@code |z| ≤ 1/3} and the series converges quickly. {@code exp} uses
 * the Taylor series after subtracting the nearest multiple of {@code ln 2}, for the same reason.
 */
final class Compounding {

    /** 34 significant digits — {@code MathContext.DECIMAL128}'s precision, {@code HALF_UP} to
     * match {@link com.protify.portfolio.common.money.MoneyUtils#ROUNDING}. */
    static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** {@code ln 2} to 50 decimal places — more than {@link #MC} can consume, so the reduction
     * step never becomes the precision bottleneck. */
    private static final BigDecimal LN_2 =
            new BigDecimal("0.69314718055994530941723212145817656807550013436026");

    /** The series stop once a term is smaller than this; at {@link #MC}'s 34 digits that is
     * comfortably below the last digit either series can represent. */
    private static final BigDecimal EPSILON = new BigDecimal("1E-40");

    private static final int MAX_TERMS = 1_000;

    private Compounding() {
    }

    /**
     * {@code base}<sup>{@code exponent}</sup> for a strictly positive {@code base}.
     *
     * @throws IllegalArgumentException if {@code base} is zero or negative — the caller decides
     *         what a wiped-out or negative growth factor means, since neither has a real power
     *         for an arbitrary fractional exponent
     */
    static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        if (base.signum() <= 0) {
            throw new IllegalArgumentException("pow requires a positive base, got " + base);
        }
        if (exponent.signum() == 0) {
            return BigDecimal.ONE;
        }
        // Exact short-circuit: annualising a period of exactly one year must return the period
        // return itself, digit for digit, not a 34-digit approximation of it.
        if (exponent.compareTo(BigDecimal.ONE) == 0) {
            return base;
        }
        if (base.compareTo(BigDecimal.ONE) == 0) {
            return BigDecimal.ONE;
        }
        return exp(exponent.multiply(ln(base), MC));
    }

    /** Natural logarithm of a strictly positive {@code x}. */
    static BigDecimal ln(BigDecimal x) {
        if (x.signum() <= 0) {
            throw new IllegalArgumentException("ln requires a positive argument, got " + x);
        }

        // x = m · 2^k with m in [0.5, 2), so ln x = k·ln2 + ln m and |z| below stays ≤ 1/3.
        int k = 0;
        BigDecimal m = x;
        while (m.compareTo(TWO) >= 0) {
            m = m.divide(TWO, MC);
            k++;
        }
        while (m.compareTo(HALF) < 0) {
            m = m.multiply(TWO, MC);
            k--;
        }

        BigDecimal z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC);
        BigDecimal zSquared = z.multiply(z, MC);
        BigDecimal term = z;
        BigDecimal sum = z;
        for (int n = 1; n < MAX_TERMS; n++) {
            term = term.multiply(zSquared, MC);
            BigDecimal next = term.divide(new BigDecimal(2 * n + 1), MC);
            if (next.abs().compareTo(EPSILON) < 0) {
                break;
            }
            sum = sum.add(next, MC);
        }

        return sum.multiply(TWO, MC).add(LN_2.multiply(new BigDecimal(k), MC), MC);
    }

    /** {@code e}<sup>{@code x}</sup>. */
    static BigDecimal exp(BigDecimal x) {
        if (x.signum() == 0) {
            return BigDecimal.ONE;
        }

        // x = k·ln2 + r with |r| ≤ ln2/2, so the Taylor series below converges in a few dozen
        // terms however large x is.
        int k = x.divide(LN_2, 0, RoundingMode.HALF_UP).intValueExact();
        BigDecimal r = x.subtract(LN_2.multiply(new BigDecimal(k), MC), MC);

        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        for (int n = 1; n < MAX_TERMS; n++) {
            term = term.multiply(r, MC).divide(new BigDecimal(n), MC);
            if (term.abs().compareTo(EPSILON) < 0) {
                break;
            }
            sum = sum.add(term, MC);
        }

        BigDecimal twoToTheK = TWO.pow(Math.abs(k), MC);
        return k >= 0 ? sum.multiply(twoToTheK, MC) : sum.divide(twoToTheK, MC);
    }

    /** Square root of a non-negative {@code x}, at {@link #MC}'s precision. */
    static BigDecimal sqrt(BigDecimal x) {
        if (x.signum() < 0) {
            throw new IllegalArgumentException("sqrt requires a non-negative argument, got " + x);
        }
        return x.sqrt(MC);
    }
}
