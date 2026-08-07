package com.protify.portfolio.marketdata.provider;

import java.math.BigDecimal;
import java.util.List;

/** Minimal shape of Yahoo's {@code /v8/finance/chart/{symbol}} response — only the fields this
 * adapter reads. */
record YahooChartResponse(YahooChart chart) {

    record YahooChart(List<YahooResult> result, Object error) {
    }

    record YahooResult(YahooMeta meta, List<Long> timestamp, YahooIndicators indicators) {
    }

    record YahooMeta(String currency, String symbol) {
    }

    record YahooIndicators(List<YahooQuote> quote) {
    }

    record YahooQuote(List<BigDecimal> close) {
    }
}
