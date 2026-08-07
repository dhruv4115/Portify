package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.FxConversion;
import com.protify.portfolio.valuation.spi.PriceLookup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * day-3-dev-A.md D3-A4 — {@code portfolios/{id}/performance}. ADR-0010's three flat queries plus
 * one in-memory merge-walk: never a recursive-CTE / {@code LATERAL} join, so an off-by-one on a
 * boundary date is a breakpoint away, not a query plan. Two of those three queries arrive
 * through the {@link PriceLookup}/{@link FxConversion} ports rather than by importing Dev B's
 * repositories directly (day-4-dev-A.md D4-A1). Only {@code DAILY} granularity is built
 * — {@code WEEKLY}/{@code MONTHLY} is PLAN.md §9 descope rung 4, and Day 3's estimate assumes
 * it's cut.
 *
 * <p>Reuses {@link ValuationFolder}'s {@link ValuationFolder.Accumulator} so the same
 * cost-basis/cash maths that backs {@link ValuationService} backs the chart too (PLAN.md §5:
 * "one engine, three call sites") — advanced one day (and zero or more transactions) at a time
 * instead of re-folded from scratch per point, which is what keeps this {@code O(days +
 * transactions)} rather than {@code O(days × transactions)}.
 */
@Service
public class PerformanceService {

    private static final long MAX_RANGE_YEARS = 5;

    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final InstrumentRepository instrumentRepository;
    private final PriceLookup priceLookup;
    private final FxConversion fxConversion;
    private final ValuationSnapshotService snapshotService;

    public PerformanceService(
            PortfolioService portfolioService,
            TransactionRepository transactionRepository,
            InstrumentRepository instrumentRepository,
            PriceLookup priceLookup,
            FxConversion fxConversion,
            ValuationSnapshotService snapshotService) {
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.instrumentRepository = instrumentRepository;
        this.priceLookup = priceLookup;
        this.fxConversion = fxConversion;
        this.snapshotService = snapshotService;
    }

    public PerformanceResult getPerformance(long userId, long portfolioId, LocalDate from, LocalDate to, CurrencyCode requestedCurrency) {
        if (from.isAfter(to)) {
            throw new ValidationException("invalid-date-range", "from must not be after to.");
        }
        if (from.plusYears(MAX_RANGE_YEARS).isBefore(to)) {
            throw new ValidationException("invalid-date-range", "Range must not exceed 5 years.");
        }

        Portfolio portfolio = portfolioService.getOrThrow(userId, portfolioId);
        CurrencyCode currency = requestedCurrency != null ? requestedCurrency : portfolio.baseCurrency();

        List<Txn> allTxns = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        if (allTxns.isEmpty()) {
            return emptyResult(portfolioId, currency, from, to);
        }

        LocalDate firstTxnDate = ValuationFolder.dateOf(allTxns.get(0));
        LocalDate rangeStart = from.isAfter(firstTxnDate) ? from : firstTxnDate;
        if (rangeStart.isAfter(to)) {
            return emptyResult(portfolioId, currency, from, to);
        }

        Set<Long> instrumentIds = new HashSet<>();
        for (Txn txn : allTxns) {
            if (txn.instrumentId() != null) {
                instrumentIds.add(txn.instrumentId());
            }
        }
        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable = fxConversion.ratesUpTo(to);

        // The cache (day-5-dev-A.md D5-A2). Completed days only — today's close has not happened
        // yet, so today is always folded live no matter what the table holds.
        NavigableMap<LocalDate, ValuationSnapshot> cached =
                snapshotService.completedSnapshots(portfolioId, currency, rangeStart, to);
        boolean anyUncovered = anyDayUncovered(rangeStart, to, cached);

        // The price series is the expensive read: the whole of price_history for every instrument
        // the portfolio has ever held, deliberately unbounded below (ADR-0010). A window that is
        // entirely memoised skips it, and the instrument lookups with it — that, and not the
        // arithmetic, is where a 365-day series gets its time back.
        Map<Long, NavigableMap<LocalDate, BigDecimal>> prices =
                anyUncovered ? priceLookup.seriesUpTo(instrumentIds, to) : Map.of();
        // One IN query, not one findById per instrument (day-5-dev-A.md D5-A3's N+1 hunt).
        Map<Long, Instrument> instruments =
                anyUncovered ? instrumentRepository.findAllByIds(instrumentIds) : Map.of();

        ProjectionContext.FxLookup tradeDateFx = (from2, on) -> tradeDateRate(fxTable, from2, currency, on);
        ValuationFolder.Accumulator accumulator = new ValuationFolder.Accumulator();

        int cursor = 0;
        while (cursor < allTxns.size() && ValuationFolder.dateOf(allTxns.get(cursor)).isBefore(rangeStart)) {
            accumulator.apply(allTxns.get(cursor), tradeDateFx);
            cursor++;
        }

        List<PerformancePoint> points = new ArrayList<>();
        // Deposits minus withdrawals inside the reported window only — transfers before
        // rangeStart are already inside the opening value and are not contributions to it.
        // Shared with AnalyticsService, which needs the same flows broken down by date
        // (day-5-dev-A.md D5-A1).
        BigDecimal netContributions = CashFlowSeries.total(
                CashFlowSeries.byDate(allTxns, tradeDateFx, rangeStart, to));
        LocalDate lastPriceAsOf = null;
        LocalDate lastRateAsOf = null;
        boolean lastFilled = false;

        // Days the fold had to compute, handed back to the cache once the walk is done. Never a
        // gap: a day with no snapshot is folded, not skipped.
        List<ValuationSnapshot> toMemoise = new ArrayList<>();

        for (LocalDate day = rangeStart; !day.isAfter(to); day = day.plusDays(1)) {
            // The accumulator advances over every day either way — it is O(transactions), not
            // O(days), and the first uncovered day needs the positions the covered ones built.
            while (cursor < allTxns.size() && !ValuationFolder.dateOf(allTxns.get(cursor)).isAfter(day)) {
                accumulator.apply(allTxns.get(cursor), tradeDateFx);
                cursor++;
            }

            ValuationSnapshot snapshot = cached.get(day);
            if (snapshot != null) {
                points.add(snapshot.toPoint());
                if (snapshot.priceAsOf() != null) {
                    lastPriceAsOf = snapshot.priceAsOf();
                }
                if (snapshot.rateAsOf() != null) {
                    lastRateAsOf = snapshot.rateAsOf();
                }
                lastFilled = snapshot.filled();
                continue;
            }

            DayValuation dayValuation = valueDay(accumulator.snapshot(), instruments, prices, fxTable, currency, day);
            points.add(dayValuation.point());
            if (dayValuation.priceAsOf() != null) {
                lastPriceAsOf = dayValuation.priceAsOf();
            }
            if (dayValuation.rateAsOf() != null) {
                lastRateAsOf = dayValuation.rateAsOf();
            }
            lastFilled = dayValuation.point().filled();

            if (snapshotService.isCompleted(day)) {
                toMemoise.add(ValuationSnapshot.of(portfolioId, currency, dayValuation.point(),
                        dayValuation.priceAsOf(), dayValuation.rateAsOf()));
            }
        }
        snapshotService.store(toMemoise);

        PerformanceSummary summary = summarize(points, netContributions);
        return new PerformanceResult(portfolioId, currency, from, to, points, summary, lastPriceAsOf, lastRateAsOf, lastFilled);
    }

    /** {@code cached} only ever holds dates inside {@code [from, to]}, one per date, so counting
     * is enough — no need to walk the calendar to find the first hole. */
    private static boolean anyDayUncovered(LocalDate from, LocalDate to,
            NavigableMap<LocalDate, ValuationSnapshot> cached) {
        return cached.size() < ChronoUnit.DAYS.between(from, to) + 1;
    }

    private record DayValuation(PerformancePoint point, LocalDate priceAsOf, LocalDate rateAsOf) {
    }

    private DayValuation valueDay(
            ValuationFolder.Snapshot snapshot,
            Map<Long, Instrument> instruments,
            Map<Long, NavigableMap<LocalDate, BigDecimal>> prices,
            NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable,
            CurrencyCode currency,
            LocalDate day) {

        BigDecimal marketValueSum = BigDecimal.ZERO;
        BigDecimal costBasisSum = BigDecimal.ZERO;
        boolean filled = false;
        LocalDate priceAsOf = null;
        LocalDate rateAsOf = null;

        Map.Entry<LocalDate, Map<CurrencyCode, BigDecimal>> fxEntry = fxTable.floorEntry(day);

        for (Map.Entry<Long, ValuationFolder.InstrumentPosition> entry : snapshot.positions().entrySet()) {
            ValuationFolder.InstrumentPosition position = entry.getValue();
            if (position.quantity().signum() <= 0) {
                continue;
            }
            costBasisSum = costBasisSum.add(position.costBasis());

            Instrument instrument = instruments.get(entry.getKey());
            NavigableMap<LocalDate, BigDecimal> priceSeries = prices.get(entry.getKey());
            if (instrument == null || priceSeries == null) {
                continue;
            }
            Map.Entry<LocalDate, BigDecimal> priceEntry = priceSeries.floorEntry(day);
            if (priceEntry == null) {
                continue;
            }
            if (priceEntry.getKey().isBefore(day)) {
                filled = true;
            }
            priceAsOf = earlierOf(priceAsOf, priceEntry.getKey());

            BigDecimal rate;
            if (instrument.currency() == currency) {
                // Identity conversion needs no fx_rate row and is never forward-filled.
                rate = BigDecimal.ONE;
                rateAsOf = earlierOf(rateAsOf, day);
            } else {
                if (fxEntry == null) {
                    continue;
                }
                rate = crossRate(fxEntry.getValue(), instrument.currency(), currency);
                if (rate == null) {
                    continue;
                }
                if (fxEntry.getKey().isBefore(day)) {
                    filled = true;
                }
                rateAsOf = earlierOf(rateAsOf, fxEntry.getKey());
            }

            BigDecimal nativeMarketValue = position.quantity().multiply(priceEntry.getValue());
            marketValueSum = marketValueSum.add(nativeMarketValue.multiply(rate));
        }

        BigDecimal cashBalance = snapshot.cashBalance();
        BigDecimal marketValue = MoneyUtils.money(marketValueSum);
        BigDecimal costBasis = MoneyUtils.money(costBasisSum);
        BigDecimal totalValue = MoneyUtils.money(marketValue.add(cashBalance));
        BigDecimal unrealisedPnl = MoneyUtils.money(marketValue.subtract(costBasis));

        PerformancePoint point = new PerformancePoint(day, marketValue, costBasis, cashBalance, totalValue, unrealisedPnl, filled);
        return new DayValuation(point, priceAsOf, rateAsOf);
    }

    private PerformanceSummary summarize(List<PerformancePoint> points, BigDecimal netContributions) {
        if (points.isEmpty()) {
            return new PerformanceSummary(MoneyUtils.money(BigDecimal.ZERO), MoneyUtils.money(BigDecimal.ZERO),
                    MoneyUtils.money(BigDecimal.ZERO), null, MoneyUtils.money(netContributions));
        }
        BigDecimal start = points.get(0).totalValue();
        BigDecimal end = points.get(points.size() - 1).totalValue();
        BigDecimal change = MoneyUtils.money(end.subtract(start));
        BigDecimal pct = MoneyUtils.pctChange(start, end);
        return new PerformanceSummary(start, end, change, pct, MoneyUtils.money(netContributions));
    }

    private PerformanceResult emptyResult(long portfolioId, CurrencyCode currency, LocalDate from, LocalDate to) {
        return new PerformanceResult(portfolioId, currency, from, to, List.of(), summarize(List.of(), BigDecimal.ZERO),
                null, null, false);
    }

    /** Trade-date FX for the accumulator and net-contributions maths — never {@code null}
     * (the {@link ProjectionContext.FxLookup} contract), so an unresolvable rate falls back to
     * identity rather than corrupting the running cash/cost-basis totals. */
    private static BigDecimal tradeDateRate(
            NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable, CurrencyCode from, CurrencyCode to, LocalDate date) {
        Map.Entry<LocalDate, Map<CurrencyCode, BigDecimal>> entry = fxTable.floorEntry(date);
        if (entry == null) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = crossRate(entry.getValue(), from, to);
        return rate == null ? BigDecimal.ONE : rate;
    }

    /** {@code ratesOnDate} is USD-pivot ("USD → quote"); {@code cross(from→to) = rate(USD→to) /
     * rate(USD→from)} (ADR-0011). {@code null} when a currency's rate is missing that day —
     * callers decide the fallback, since a market-value contribution should be skipped rather
     * than defaulted to 1, unlike a cash-flow conversion. */
    private static BigDecimal crossRate(Map<CurrencyCode, BigDecimal> ratesOnDate, CurrencyCode from, CurrencyCode to) {
        if (from == to) {
            return BigDecimal.ONE;
        }
        BigDecimal fromRate = from == CurrencyCode.USD ? BigDecimal.ONE : ratesOnDate.get(from);
        BigDecimal toRate = to == CurrencyCode.USD ? BigDecimal.ONE : ratesOnDate.get(to);
        if (fromRate == null || toRate == null) {
            return null;
        }
        return MoneyUtils.fxRate(toRate.divide(fromRate, 16, MoneyUtils.ROUNDING));
    }

    private static LocalDate earlierOf(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return a.isBefore(b) ? a : b;
    }
}
