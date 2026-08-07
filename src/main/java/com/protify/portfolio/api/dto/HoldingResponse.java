package com.protify.portfolio.api.dto;

/**
 * API_CONTRACT.md §10 — and §0.3's currency rule is the whole point of this shape, so it is
 * worth stating twice: {@code avgCost} and {@code lastPrice} are in the instrument's
 * <b>native</b> trading currency, while {@code marketValue}, {@code costBasis},
 * {@code unrealisedPnl} and {@code realisedPnl} are in the portfolio's <b>base</b> currency (or
 * the {@code ?currency=} override). A three-currency portfolio therefore renders three different
 * {@code avgCost} currencies against one currency for every total. Getting it the other way
 * round is the easiest mistake available here and is what {@code HoldingControllerTest} pins
 * down.
 *
 * <p>{@code lastPrice}, {@code marketValue} and {@code unrealisedPnl} are {@code null} when the
 * position genuinely could not be priced from any source — the endpoint still answers 200 with
 * {@code dataQuality.stale: true} (TEST_PLAN.md §4.3), because one unpriceable instrument must
 * not blank the whole portfolio. {@code unrealisedPnlPct} is {@code null} when cost basis is
 * zero (§4.2) — never {@code Infinity}, never {@code 0}.
 */
public record HoldingResponse(
        InstrumentResponse instrument,
        String quantity,
        MoneyDto avgCost,
        MoneyDto lastPrice,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto unrealisedPnl,
        String unrealisedPnlPct,
        MoneyDto realisedPnl,
        String weightPct,
        String fxRate,
        DataQualityDto dataQuality) {
}
