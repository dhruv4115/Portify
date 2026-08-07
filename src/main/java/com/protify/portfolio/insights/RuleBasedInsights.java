package com.protify.portfolio.insights;

import com.protify.portfolio.common.money.MoneyUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * D5-B2 — "on failure, a rule-based summary generated in Java, so the whole feature degrades
 * twice before it fails": this is the SECOND fallback, used when the Python service itself is
 * unreachable or times out. Deliberately simpler than {@code services/insights/main.py}'s
 * generator (fewer highlight types) — it exists purely so a portfolio page never goes blank,
 * not to duplicate the full Python feature set. Pure Java, deterministic, no I/O.
 */
final class RuleBasedInsights {

    private static final BigDecimal CONCENTRATION_THRESHOLD_PCT = new BigDecimal("40");
    private static final BigDecimal FX_EXPOSURE_NOTABLE_PCT = new BigDecimal("20");
    private static final BigDecimal CASH_DRAG_NOTABLE_PCT = new BigDecimal("10");
    private static final int PCT_SCALE = 1;

    private RuleBasedInsights() {
    }

    static InsightsResult generate(PortfolioSummary summary) {
        List<Highlight> highlights = new ArrayList<>();
        highlights.addAll(concentrationHighlights(summary));
        fxExposureHighlight(summary).ifPresent(highlights::add);
        highlights.addAll(performanceHighlights(summary));
        cashDragHighlight(summary).ifPresent(highlights::add);

        String summaryText = highlights.isEmpty()
                ? "No notable concentration, FX exposure, performance outlier or cash drag detected."
                : highlights.stream().map(Highlight::message).reduce((a, b) -> a + " " + b).orElse("");

        return new InsightsResult(summaryText, highlights, Engine.RULE_BASED);
    }

    private static List<Highlight> concentrationHighlights(PortfolioSummary summary) {
        BigDecimal total = summary.totalMarketValue();
        if (total == null || MoneyUtils.isZero(total) || MoneyUtils.isNegative(total)) {
            return List.of();
        }
        List<Highlight> highlights = new ArrayList<>();
        for (HoldingSummary holding : summary.holdings()) {
            BigDecimal weight = pct(holding.marketValue(), total);
            if (weight.compareTo(CONCENTRATION_THRESHOLD_PCT) > 0) {
                String severity = weight.compareTo(new BigDecimal("60")) > 0 ? "HIGH" : "MEDIUM";
                highlights.add(new Highlight("CONCENTRATION", severity,
                        "%s is %s%% of market value.".formatted(holding.symbol(), weight)));
            }
        }
        return highlights;
    }

    private static Optional<Highlight> fxExposureHighlight(PortfolioSummary summary) {
        BigDecimal total = summary.totalMarketValue();
        if (total == null || MoneyUtils.isZero(total) || MoneyUtils.isNegative(total)) {
            return Optional.empty();
        }
        BigDecimal nonBaseValue = summary.holdings().stream()
                .filter(h -> h.currency() != summary.baseCurrency())
                .map(HoldingSummary::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pct = pct(nonBaseValue, total);
        if (MoneyUtils.isZero(pct) || MoneyUtils.isNegative(pct)) {
            return Optional.empty();
        }
        String severity = pct.compareTo(new BigDecimal("60")) > 0 ? "HIGH"
                : pct.compareTo(FX_EXPOSURE_NOTABLE_PCT) >= 0 ? "MEDIUM" : "LOW";
        return Optional.of(new Highlight("FX_EXPOSURE", severity,
                "%s%% of holdings are non-%s.".formatted(pct, summary.baseCurrency())));
    }

    private static List<Highlight> performanceHighlights(PortfolioSummary summary) {
        List<HoldingSummary> priced = summary.holdings().stream()
                .filter(h -> h.costBasis() != null && !MoneyUtils.isZero(h.costBasis()) && !MoneyUtils.isNegative(h.costBasis()))
                .toList();
        if (priced.isEmpty()) {
            return List.of();
        }
        Comparator<HoldingSummary> byPnlPct = Comparator.comparing(RuleBasedInsights::pnlPct);
        HoldingSummary best = priced.stream().max(byPnlPct).orElseThrow();
        HoldingSummary worst = priced.stream().min(byPnlPct).orElseThrow();

        List<Highlight> highlights = new ArrayList<>();
        highlights.add(new Highlight("PERFORMANCE", "LOW",
                "Best performer: %s (%s%%).".formatted(best.symbol(), signed(pnlPct(best)))));
        if (!worst.symbol().equals(best.symbol())) {
            highlights.add(new Highlight("PERFORMANCE", "LOW",
                    "Worst performer: %s (%s%%).".formatted(worst.symbol(), signed(pnlPct(worst)))));
        }
        return highlights;
    }

    private static Optional<Highlight> cashDragHighlight(PortfolioSummary summary) {
        BigDecimal cash = summary.cashBalance() == null ? BigDecimal.ZERO : summary.cashBalance();
        BigDecimal total = (summary.totalMarketValue() == null ? BigDecimal.ZERO : summary.totalMarketValue()).add(cash);
        if (MoneyUtils.isZero(total) || MoneyUtils.isNegative(total)) {
            return Optional.empty();
        }
        BigDecimal pct = pct(cash, total);
        if (pct.compareTo(CASH_DRAG_NOTABLE_PCT) < 0) {
            return Optional.empty();
        }
        String severity = pct.compareTo(new BigDecimal("30")) > 0 ? "HIGH" : "MEDIUM";
        return Optional.of(new Highlight("CASH_DRAG", severity,
                "%s%% of the portfolio is sitting in cash.".formatted(pct)));
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return part.divide(whole, PCT_SCALE + 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code Comparator.comparing}/{@code BigDecimal} sort correctly without ever going
     * through {@code double} — {@link BigDecimal} implements {@link Comparable} directly. */
    private static BigDecimal pnlPct(HoldingSummary holding) {
        return holding.marketValue().subtract(holding.costBasis())
                .divide(holding.costBasis(), PCT_SCALE + 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }

    /** {@link BigDecimal#toPlainString()} already carries a "-" for negative values; this adds
     * the "+" for non-negative ones so both best and worst performer read consistently. */
    private static String signed(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "") + value.toPlainString();
    }
}
