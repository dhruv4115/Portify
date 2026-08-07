package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import java.math.BigDecimal;

/**
 * API_CONTRACT.md §0.2: {@code amount} is a JSON <em>string</em>, never a bare number and
 * never a {@code double}. JavaScript parses bare JSON numbers as IEEE-754 doubles, which
 * cannot represent decimal money exactly, so a number here would silently corrupt the value
 * between this server and a browser. Every other DTO in this codebase composes {@code Money}
 * through this type rather than exposing a {@code BigDecimal} field directly.
 */
public record MoneyDto(String amount, CurrencyCode currency) {

    public static MoneyDto of(BigDecimal amount, CurrencyCode currency) {
        return new MoneyDto(MoneyUtils.money(amount).toPlainString(), currency);
    }

    public static MoneyDto zero(CurrencyCode currency) {
        return of(BigDecimal.ZERO, currency);
    }
}
