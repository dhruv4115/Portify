package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.PerformanceInterval;
import com.protify.portfolio.api.dto.PerformancePointDto;
import com.protify.portfolio.api.dto.PerformanceResponse;
import com.protify.portfolio.api.dto.PerformanceSummaryDto;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.valuation.PerformancePoint;
import com.protify.portfolio.valuation.PerformanceResult;
import com.protify.portfolio.valuation.PerformanceSummary;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection.
 *
 * <p>{@code points} is passed in separately from {@code result} because the caller may have
 * sampled it down to a weekly or monthly series; {@code summary} is always
 * {@link PerformanceResult}'s own, computed over the full daily series, so {@code startValue}
 * stays the value at the start of the requested window rather than the value at the end of the
 * first week.
 *
 * <p>{@code stale} is {@link PerformanceResult#stale()} passed through — see
 * {@link ValuationMapper} for why this layer does not second-guess it.
 */
@Component
public class PerformanceMapper {

    public PerformanceResponse toResponse(PerformanceResult result, List<PerformancePoint> points,
            PerformanceInterval interval) {
        CurrencyCode currency = result.currency();
        return new PerformanceResponse(
                result.portfolioId(),
                currency,
                result.from(),
                result.to(),
                interval,
                points.stream().map(point -> toDto(point, currency)).toList(),
                toDto(result.summary(), currency),
                new DataQualityDto(result.priceAsOf(), result.rateAsOf(), result.stale()));
    }

    private static PerformancePointDto toDto(PerformancePoint point, CurrencyCode currency) {
        return new PerformancePointDto(
                point.date(),
                MoneyDto.of(point.marketValue(), currency),
                MoneyDto.of(point.costBasis(), currency),
                MoneyDto.of(point.cashBalance(), currency),
                MoneyDto.of(point.totalValue(), currency),
                MoneyDto.of(point.unrealisedPnl(), currency),
                point.filled());
    }

    private static PerformanceSummaryDto toDto(PerformanceSummary summary, CurrencyCode currency) {
        return new PerformanceSummaryDto(
                MoneyDto.of(summary.startValue(), currency),
                MoneyDto.of(summary.endValue(), currency),
                MoneyDto.of(summary.absoluteChange(), currency),
                // null when the window opened at zero — not Infinity and not 0 (§4.2)
                summary.percentChange() == null ? null : summary.percentChange().toPlainString(),
                MoneyDto.of(summary.netContributions(), currency));
    }
}
