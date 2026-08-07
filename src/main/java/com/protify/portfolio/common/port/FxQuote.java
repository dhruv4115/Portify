package com.protify.portfolio.common.port;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FxQuote(CurrencyCode from, CurrencyCode to, LocalDate date,
                       BigDecimal rate, FxSource source) {
}
