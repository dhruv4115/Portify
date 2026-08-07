package com.protify.portfolio.common.port;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceQuote(String symbol, LocalDate date, BigDecimal close,
                          CurrencyCode currency, PriceSource source) {
}
