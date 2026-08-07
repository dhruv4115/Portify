package com.protify.portfolio.common.error;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a SELL exceeds the current holding. Carries {@code symbol}, {@code requested}
 * and {@code held} so the message is specific — "Cannot sell 50 of AAPL; holding is 20." —
 * rather than a generic "insufficient quantity".
 */
public final class InsufficientQuantityException extends DomainException {

    private final String symbol;
    private final BigDecimal requested;
    private final BigDecimal held;

    public InsufficientQuantityException(String symbol, BigDecimal requested, BigDecimal held) {
        super("Cannot sell %s of %s; holding is %s.".formatted(
                plain(requested), symbol, plain(held)));
        this.symbol = symbol;
        this.requested = requested;
        this.held = held;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    public String symbol() {
        return symbol;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal held() {
        return held;
    }

    @Override
    public String problemType() {
        return "/errors/insufficient-quantity";
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
