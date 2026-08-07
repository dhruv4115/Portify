package com.protify.portfolio.api.insights;

import com.protify.portfolio.api.dto.InsightsResponse;
import com.protify.portfolio.api.holding.HoldingValuation;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.api.mapper.InsightsMapper;
import com.protify.portfolio.insights.Engine;
import com.protify.portfolio.insights.HoldingSummary;
import com.protify.portfolio.insights.InsightsClient;
import com.protify.portfolio.insights.InsightsResult;
import com.protify.portfolio.insights.PortfolioSummary;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Assembles the aggregates {@link InsightsClient} needs and shapes what comes back
 * (day-5-dev-C.md D5-C2, API_CONTRACT.md §18).
 *
 * <p><b>What this class does not build is the point of it.</b> {@link PortfolioSummary} has no
 * field for a transaction id, a trade date or a note, so "never send transaction-level data to
 * an external LLM" is enforced by the type rather than by a rule someone has to remember here.
 * The most granular thing that can leave this method is one symbol's total market value.
 *
 * <p>Numbers come from {@link ValuationService} and {@link HoldingViewService} — the same two
 * sources behind {@code /valuation} and {@code /holdings} — so a summary can never quote a
 * total the rest of the product disagrees with.
 */
@Service
public class InsightsViewService {

    private final PortfolioService portfolioService;
    private final ValuationService valuationService;
    private final HoldingViewService holdingViewService;
    private final InsightsClient insightsClient;
    private final InsightsMapper insightsMapper;

    public InsightsViewService(
            PortfolioService portfolioService,
            ValuationService valuationService,
            HoldingViewService holdingViewService,
            InsightsClient insightsClient,
            InsightsMapper insightsMapper) {
        this.portfolioService = portfolioService;
        this.valuationService = valuationService;
        this.holdingViewService = holdingViewService;
        this.insightsClient = insightsClient;
        this.insightsMapper = insightsMapper;
    }

    /** Whether the feature is on at all — the controller's 501 decision (§18). */
    public boolean isEnabled() {
        return insightsClient.isEnabled();
    }

    /**
     * Resolves the portfolio first, so an unowned id is a 404 before any summary is built and
     * before anything at all leaves the process.
     */
    public InsightsResponse generate(long userId, long portfolioId, String horizon, String tone) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);

        ValuationResult valuation = valuationService.valuate(
                userId, portfolioId, asOf, portfolio.baseCurrency());

        List<HoldingSummary> holdings = holdingViewService
                .valuations(userId, portfolioId, portfolio.baseCurrency()).stream()
                // A closed position and an unpriceable one both carry no market value to reason
                // about; feeding either in produces a highlight about nothing.
                .filter(v -> v.marketValue() != null && v.holding().quantity().signum() != 0)
                .map(InsightsViewService::toHoldingSummary)
                .toList();

        PortfolioSummary summary = new PortfolioSummary(
                portfolio.baseCurrency(),
                zeroIfNull(valuation.marketValue()),
                zeroIfNull(valuation.cashBalance()),
                holdings);

        // Never throws and never returns null, flag on or off — see InsightsClient.
        InsightsResult generated = insightsClient.generate(summary, horizon, tone);
        return insightsMapper.toResponse(portfolioId, variants(generated, summary), Instant.now());
    }

    /**
     * Both readings when there are genuinely two, one when there is one.
     *
     * <p>The rule-based summary is computed from the <em>same</em> {@link PortfolioSummary} that
     * was sent to the model, so the two sides of a client's toggle are two descriptions of one
     * set of numbers. Asking for them in separate requests would let a price refresh land in
     * between, and the reader would read that difference as the engines disagreeing.
     *
     * <p>When {@code generated} is already rule-based — the flag is off, or the call timed out,
     * failed, or the service answered with its own deterministic path — there is nothing to
     * compare it against. It is returned alone rather than paired with a freshly computed twin,
     * because two identical summaries behind a toggle would imply a choice that is not there.
     * (The two would not even be reliably identical: the Python service's generator has more
     * highlight types than the Java fallback, so pairing them could show two <em>different</em>
     * rule-based readings both labelled the same thing.)
     */
    private List<InsightsResult> variants(InsightsResult generated, PortfolioSummary summary) {
        return generated.engine() == Engine.LLM
                ? List.of(generated, insightsClient.ruleBased(summary))
                : List.of(generated);
    }

    private static HoldingSummary toHoldingSummary(HoldingValuation valuation) {
        return new HoldingSummary(
                valuation.instrument().symbol(),
                valuation.instrument().currency(),
                valuation.marketValue(),
                zeroIfNull(valuation.costBasis()));
    }

    /**
     * {@link ValuationResult#marketValue()} is {@code null} when nothing could be priced.
     * The rule-based generators divide by this total, so it arrives as zero rather than
     * {@code null} — and both of them already treat a zero total as "no basis for a
     * concentration or FX claim" and stay quiet, which is the correct answer for a portfolio
     * nobody could price.
     */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }
}
