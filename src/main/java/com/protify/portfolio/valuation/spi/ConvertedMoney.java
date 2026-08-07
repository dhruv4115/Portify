package com.protify.portfolio.valuation.spi;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A converted amount and the date of the rate that produced it — the core-side equivalent of
 * {@code fx.ConversionResult}, so that a platform type never crosses {@link FxConversion}.
 *
 * <p>{@code asOf} is the date of the rate genuinely used, which may be older than the date
 * requested when the last-good {@code fx_rate} row is serving.
 */
public record ConvertedMoney(BigDecimal amount, CurrencyCode currency, LocalDate asOf) {
}
