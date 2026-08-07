package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;

import java.math.BigDecimal;
import java.time.LocalDate;

record FxRateRow(CurrencyCode baseCcy, CurrencyCode quoteCcy, LocalDate rateDate, BigDecimal rate, FxSource source) {
}
