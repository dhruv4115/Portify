package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.ValuationResponse;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.valuation.ValuationResult;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection. {@link ValuationResult}'s
 * nullable money fields stay nullable: they mean "this portfolio holds positions and none of them
 * could be priced", and substituting zero would report a portfolio as worthless rather than as
 * unpriced.
 *
 * <p>{@code stale} is {@link ValuationResult#stale()} passed through untouched. That flag is
 * stricter than API_CONTRACT.md §0.5's "more than 3 calendar days behind" — {@code
 * ValuationService} raises it whenever any price or rate was forward-filled at all, so a Monday
 * reading Friday's close reports stale. Not relaxed here on purpose: only that service sees the
 * per-instrument dates, so a mapper working from the aggregate cannot tell a weekend apart from a
 * dead provider, and overriding it would mean claiming data is fresh that the layer which
 * computed it says is not. Raised with the team as a thing to reconcile in
 * {@code ValuationService}, where the information actually is.
 */
@Component
public class ValuationMapper {

    public ValuationResponse toResponse(ValuationResult result) {
        CurrencyCode currency = result.currency();
        return new ValuationResponse(
                result.portfolioId(),
                result.asOf(),
                currency,
                money(result.marketValue(), currency),
                money(result.costBasis(), currency),
                money(result.cashBalance(), currency),
                money(result.totalValue(), currency),
                money(result.unrealisedPnl(), currency),
                money(result.realisedPnl(), currency),
                // null when cost basis is zero, never Infinity and never 0 (TEST_PLAN.md §4.2)
                result.unrealisedPnlPct() == null ? null : result.unrealisedPnlPct().toPlainString(),
                result.holdingCount(),
                new DataQualityDto(result.priceAsOf(), result.rateAsOf(), result.stale()));
    }

    private static MoneyDto money(BigDecimal amount, CurrencyCode currency) {
        return amount == null ? null : MoneyDto.of(amount, currency);
    }
}
