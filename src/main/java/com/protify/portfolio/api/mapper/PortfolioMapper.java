package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.PortfolioResponse;
import com.protify.portfolio.api.portfolio.PortfolioSummary;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.portfolio.Portfolio;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection (day-2-dev-C.md D2-C2).
 * Persistence type ({@link Portfolio}) in, DTO out; {@link PortfolioSummary} is the valuation
 * seam (day-4-dev-C.md: backed for real by {@code ValuationService} since Day 4), not a
 * persistence type.
 *
 * <p>{@code marketValue}/{@code totalValue}/{@code unrealisedPnl} stay {@code null} when
 * {@link PortfolioSummary} reports them {@code null} — that means at least one open position
 * exists and none of them could be priced; substituting zero would report the portfolio as
 * worthless rather than as unpriced (same rule as {@code ValuationMapper}).
 */
@Component
public class PortfolioMapper {

    public PortfolioResponse toResponse(Portfolio portfolio, PortfolioSummary summary) {
        var currency = portfolio.baseCurrency();
        BigDecimal marketValue = summary.marketValue();
        BigDecimal cashBalance = summary.cashBalance();
        BigDecimal totalValue = (marketValue == null || cashBalance == null)
                ? null
                : marketValue.add(cashBalance);

        return new PortfolioResponse(
                portfolio.id(),
                portfolio.name(),
                currency,
                summary.holdingCount(),
                money(marketValue, currency),
                money(summary.costBasis(), currency),
                money(cashBalance, currency),
                money(totalValue, currency),
                money(summary.unrealisedPnl(), currency),
                money(summary.realisedPnl(), currency),
                summary.unrealisedPnlPct(),
                summary.dataQuality(),
                portfolio.createdAt(),
                portfolio.updatedAt());
    }

    private static MoneyDto money(BigDecimal amount, CurrencyCode currency) {
        return amount == null ? null : MoneyDto.of(amount, currency);
    }
}
