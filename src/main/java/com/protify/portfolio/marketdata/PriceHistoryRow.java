package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.PriceSource;

import java.math.BigDecimal;
import java.time.LocalDate;

record PriceHistoryRow(long instrumentId, LocalDate priceDate, BigDecimal closePrice, PriceSource source) {
}
