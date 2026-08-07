package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.FxConversion;
import com.protify.portfolio.valuation.spi.PriceLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * day-3-dev-A.md D3-A3 — {@code portfolios/{id}/valuation}. Runs {@link ValuationFolder} (the
 * base-currency cost-basis fold, ADR-0011) over the transaction history up to {@code asOf}, then
 * prices each open position through {@link PositionPricer}, which is shared with
 * {@link AllocationService} so the two can never disagree about what a holding is worth.
 * That pricer reaches Dev B's market data and FX only through the {@code valuation/spi}
 * ports — see {@link PriceLookup} for why they are declared there rather than in the frozen
 * {@code common} package (day-4-dev-A.md D4-A1).
 *
 * <p>Market value uses the FX rate <b>on {@code asOf}</b>; cost basis (inside the fold) uses the
 * rate on each transaction's own date. That difference is the currency P&L (ADR-0011) — using
 * today's rate for both would erase it.
 */
@Service
public class ValuationService {

    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final PositionPricer positionPricer;
    private final FxConversion fxConversion;

    public ValuationService(
            PortfolioService portfolioService,
            TransactionRepository transactionRepository,
            PositionPricer positionPricer,
            FxConversion fxConversion) {
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.positionPricer = positionPricer;
        this.fxConversion = fxConversion;
    }

    public ValuationResult valuate(long userId, long portfolioId, LocalDate asOf, CurrencyCode requestedCurrency) {
        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();

        List<Txn> allTxns = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        if (!allTxns.isEmpty()) {
            LocalDate firstTxnDate = ValuationFolder.dateOf(allTxns.get(0));
            if (asOf.isBefore(firstTxnDate)) {
                throw new ValidationException("invalid-date-range",
                        "asOf %s is before the portfolio's first transaction on %s.".formatted(asOf, firstTxnDate));
            }
        }

        List<Txn> upToAsOf = allTxns.stream().filter(t -> !ValuationFolder.dateOf(t).isAfter(asOf)).toList();
        ValuationFolder.Snapshot snapshot = ValuationFolder.fold(upToAsOf, tradeDateFxLookup(currency));

        Accumulated acc = new Accumulated();
        Map<Long, BigDecimal> openQuantities = new LinkedHashMap<>();
        for (Map.Entry<Long, ValuationFolder.InstrumentPosition> entry : snapshot.positions().entrySet()) {
            // Realised P&L survives a position being sold down to zero, so it accumulates over
            // every position; only the open ones get priced.
            acc.realisedPnl = acc.realisedPnl.add(entry.getValue().realisedPnl());
            if (entry.getValue().quantity().signum() > 0) {
                openQuantities.put(entry.getKey(), entry.getValue().quantity());
            }
        }

        // One instrument query for the whole portfolio rather than one per position (D5-A3).
        Map<Long, PositionPricer.Priced> priced = positionPricer.priceAll(openQuantities, currency, asOf);
        for (Long instrumentId : openQuantities.keySet()) {
            priceOpenPosition(snapshot.positions().get(instrumentId), priced.get(instrumentId), asOf, acc);
        }

        BigDecimal marketValue = (acc.openPositions > 0 && !acc.anyPriced) ? null : MoneyUtils.money(acc.marketValue);
        BigDecimal costBasis = MoneyUtils.money(acc.costBasis);
        BigDecimal cashBalance = snapshot.cashBalance();
        BigDecimal totalValue = marketValue == null ? null : MoneyUtils.money(marketValue.add(cashBalance));
        BigDecimal unrealisedPnl = marketValue == null ? null : MoneyUtils.money(marketValue.subtract(costBasis));
        BigDecimal unrealisedPnlPct = marketValue == null ? null : MoneyUtils.pctChange(costBasis, marketValue);
        BigDecimal realisedPnl = MoneyUtils.money(acc.realisedPnl);

        return new ValuationResult(portfolioId, asOf, currency, marketValue, costBasis, cashBalance,
                totalValue, unrealisedPnl, realisedPnl, unrealisedPnlPct, acc.openPositions,
                acc.priceAsOf, acc.rateAsOf, acc.stale);
    }

    /** {@code value} is {@code null} when the position could not be priced at all — an unknown
     * instrument, no price anywhere, or no resolvable rate. That degrades the response to
     * {@code stale}; it never blanks the portfolio (TEST_PLAN.md §4.3). */
    private void priceOpenPosition(ValuationFolder.InstrumentPosition position,
            PositionPricer.Priced value, LocalDate asOf, Accumulated acc) {
        acc.openPositions++;
        acc.costBasis = acc.costBasis.add(position.costBasis());

        if (value == null) {
            acc.stale = true;
            return;
        }
        acc.marketValue = acc.marketValue.add(value.marketValue());
        acc.anyPriced = true;
        if (!value.priceAsOf().equals(asOf) || !value.rateAsOf().equals(asOf)) {
            acc.stale = true;
        }
        acc.priceAsOf = earlierOf(acc.priceAsOf, value.priceAsOf());
        acc.rateAsOf = earlierOf(acc.rateAsOf, value.rateAsOf());
    }

    private ProjectionContext.FxLookup tradeDateFxLookup(CurrencyCode target) {
        return (from, on) -> fxConversion.rate(from, target, on).orElse(BigDecimal.ONE);
    }

    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return a.isBefore(b) ? a : b;
    }

    /** Loop-scoped running totals — a private mutable holder beats five separate local
     * variables threaded through a helper method. */
    private static final class Accumulated {
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realisedPnl = BigDecimal.ZERO;
        int openPositions = 0;
        boolean anyPriced = false;
        boolean stale = false;
        LocalDate priceAsOf = null;
        LocalDate rateAsOf = null;
    }
}
