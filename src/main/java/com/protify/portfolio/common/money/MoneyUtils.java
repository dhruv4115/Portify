package com.protify.portfolio.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Every rounding decision in the codebase resolves here, and nowhere else.
 */
public final class MoneyUtils {

    public static final int MONEY_SCALE = 4;
    public static final int QUANTITY_SCALE = 6;
    public static final int FX_SCALE = 8;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtils() {
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal quantity(BigDecimal v) {
        return v.setScale(QUANTITY_SCALE, ROUNDING);
    }

    public static BigDecimal fxRate(BigDecimal v) {
        return v.setScale(FX_SCALE, ROUNDING);
    }

    public static boolean isZero(BigDecimal v) {
        return v.compareTo(BigDecimal.ZERO) == 0;
    }

    public static boolean isNegative(BigDecimal v) {
        return v.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * {@code null} rather than throwing on division by zero — an empty portfolio and a
     * cash-only portfolio both hit this in normal operation, not just as an edge case.
     */
    public static BigDecimal safeDivide(BigDecimal a, BigDecimal b, int scale) {
        if (isZero(b)) {
            return null;
        }
        return a.divide(b, scale, ROUNDING);
    }

    /**
     * Percentage change from {@code from} to {@code to}, e.g. {@code pctChange(100, 110)} is
     * {@code 10.0000}. {@code null} when {@code from} is zero — a named customer requirement,
     * not an omission. Never {@code Infinity}, never {@code 0}, never an exception.
     */
    public static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (isZero(from)) {
            return null;
        }
        BigDecimal ratio = to.subtract(from).divide(from, MONEY_SCALE + 4, ROUNDING);
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(MONEY_SCALE, ROUNDING);
    }
}
