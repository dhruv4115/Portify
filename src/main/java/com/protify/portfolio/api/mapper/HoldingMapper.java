package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.holding.HoldingValuation;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection. Pure: every figure arrives on
 * {@link HoldingValuation}, so this class performs no lookup and needs no mock to test.
 *
 * <p>The two currencies never cross (API_CONTRACT.md §0.3): {@code avgCost} and {@code lastPrice}
 * are tagged with {@code valuation.instrument().currency()}, everything else with the target
 * {@code currency}.
 */
@Component
public class HoldingMapper {

    private final InstrumentMapper instrumentMapper;

    public HoldingMapper(InstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    /**
     * @param totalMarketValue the sum of every holding's market value in {@code currency}, which
     *                         {@code weightPct} is a share of — passed in rather than computed
     *                         here, because one row cannot know the portfolio it belongs to
     */
    public HoldingResponse toResponse(HoldingValuation valuation, BigDecimal totalMarketValue,
            CurrencyCode currency, LocalDate asOf) {
        CurrencyCode nativeCurrency = valuation.instrument().currency();
        BigDecimal marketValue = valuation.marketValue();
        BigDecimal costBasis = valuation.costBasis();
        BigDecimal unrealisedPnl = marketValue == null ? null : marketValue.subtract(costBasis);

        return new HoldingResponse(
                instrumentMapper.toResponse(valuation.instrument()),
                MoneyUtils.quantity(valuation.holding().quantity()).toPlainString(),
                // straight from holding.avg_cost, never converted (non-negotiable #13)
                MoneyDto.of(valuation.holding().avgCost(), nativeCurrency),
                valuation.lastPrice() == null ? null : MoneyDto.of(valuation.lastPrice(), nativeCurrency),
                marketValue == null ? null : MoneyDto.of(marketValue, currency),
                MoneyDto.of(costBasis, currency),
                unrealisedPnl == null ? null : MoneyDto.of(unrealisedPnl, currency),
                // null, not Infinity and not 0, when cost basis is zero (TEST_PLAN.md §4.2)
                marketValue == null ? null : plain(MoneyUtils.pctChange(costBasis, marketValue)),
                MoneyDto.of(valuation.realisedPnl(), currency),
                weightPct(marketValue, totalMarketValue),
                // null when native == base: there is no rate to report, not a rate of 1
                (nativeCurrency == currency || valuation.fxRate() == null)
                        ? null
                        : MoneyUtils.fxRate(valuation.fxRate()).toPlainString(),
                DataQualityDto.of(valuation.priceAsOf(), valuation.rateAsOf(), asOf));
    }

    /**
     * Share of the portfolio's market value, scale 4. An unpriced holding and a portfolio worth
     * nothing both weigh zero rather than {@code null}: §10 types this field as a plain string,
     * and a chart legend has nothing to draw for a missing weight anyway.
     */
    private static String weightPct(BigDecimal marketValue, BigDecimal totalMarketValue) {
        BigDecimal share = marketValue == null
                ? null
                : MoneyUtils.safeDivide(marketValue, totalMarketValue, MoneyUtils.MONEY_SCALE + 4);
        if (share == null) {
            return MoneyUtils.money(BigDecimal.ZERO).toPlainString();
        }
        return share.multiply(BigDecimal.valueOf(100)).setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.ROUNDING)
                .toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
