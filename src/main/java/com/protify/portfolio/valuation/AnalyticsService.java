package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.FxConversion;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import org.springframework.stereotype.Service;

/**
 * day-5-dev-A.md D5-A1 / BACKLOG PM-18 — portfolio analytics, built on the performance series
 * {@link PerformanceService} already produces rather than on a second walk of the history.
 *
 * <p><b>Time-weighted return is the one worth getting right.</b> A user who deposits ₹100,000 on
 * a good day has not earned a return, and a naive {@code (end − start) / start} says they have.
 * The correct treatment is to chain-link the sub-period returns <em>between</em> cash flows:
 *
 * <pre>
 *   r_i  = (V_i − F_i) / V_(i−1) − 1        one day's return, net of that day's transfers
 *   TWR  = Π (1 + r_i) − 1                   the whole window, compounded
 * </pre>
 *
 * <p>where {@code V_i} is {@link PerformancePoint#totalValue()} — an end-of-day close, so
 * {@code F_i} is already inside it and has to come back out before the day is judged — and
 * {@code F_i} is that day's net external flow from {@link CashFlowSeries}. Everything else here
 * falls out of the same two series: the drawdown, the best and worst day and the volatility are
 * all measured on {@code r_i} or on the index it compounds into, never on raw portfolio value,
 * so a withdrawal is never mistaken for a loss.
 *
 * <p>{@code simpleReturnPct} is reported alongside TWR deliberately — see
 * {@link AnalyticsResult}. Ownership is enforced upstream: this class's first act is to call
 * {@link PerformanceService#getPerformance}, which resolves the portfolio through
 * {@code PortfolioService.getOrThrow}, so another user's portfolio is a 404 before any
 * transaction is read (TEST_PLAN.md §4.7).
 */
@Service
public class AnalyticsService {

    /** Daily returns are divided at far more than money precision: a 365-link product compounds
     * every rounding error in the chain, and the results are reported to four decimal places. */
    private static final int RETURN_SCALE = MoneyUtils.MONEY_SCALE + 8;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /** Calendar days, matching the {@code (1 + TWR)^(365/days) − 1} the brief specifies — not
     * 252 trading days, because this series has a point for every calendar day including
     * weekends, and annualising daily returns has to use the same calendar that produced them. */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private final PerformanceService performanceService;
    private final TransactionRepository transactionRepository;
    private final FxConversion fxConversion;

    public AnalyticsService(
            PerformanceService performanceService,
            TransactionRepository transactionRepository,
            FxConversion fxConversion) {
        this.performanceService = performanceService;
        this.transactionRepository = transactionRepository;
        this.fxConversion = fxConversion;
    }

    public AnalyticsResult analyse(long userId, long portfolioId, LocalDate from, LocalDate to,
            CurrencyCode requestedCurrency) {

        PerformanceResult series = performanceService.getPerformance(userId, portfolioId, from, to, requestedCurrency);
        List<PerformancePoint> points = series.points();
        if (points.isEmpty()) {
            return empty(series);
        }

        LocalDate firstDate = points.get(0).date();
        LocalDate lastDate = points.get(points.size() - 1).date();
        // The window the flows are taken over is the window that was actually computed, not the
        // requested one — a portfolio whose history starts mid-range has no points before it,
        // and a flow with no point to attach to would silently vanish from the chain.
        NavigableMap<LocalDate, BigDecimal> flows = CashFlowSeries.byDate(
                transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId),
                tradeDateFx(series.currency()), firstDate, lastDate);

        Chained chained = chainLink(points, flows);
        long days = ChronoUnit.DAYS.between(firstDate, lastDate);

        return new AnalyticsResult(
                series.portfolioId(),
                series.currency(),
                series.from(),
                series.to(),
                days,
                pct(chained.growth().subtract(BigDecimal.ONE)),
                annualisedPct(chained.growth(), days),
                MoneyUtils.pctChange(points.get(0).totalValue(), points.get(points.size() - 1).totalValue()),
                series.summary().netContributions(),
                chained.drawdown(),
                chained.bestDay(),
                chained.worstDay(),
                volatilityPct(chained.returns()));
    }

    private record Chained(BigDecimal growth, List<BigDecimal> returns, Drawdown drawdown,
            DayReturn bestDay, DayReturn worstDay) {
    }

    /**
     * One pass over the series producing the compounded growth factor, every daily return, the
     * peak-to-trough decline of the index, and the best and worst day. They share a loop because
     * they share the definition of {@code r_i} — computing them separately is how the drawdown
     * ends up measured on a different series than the return it is supposed to describe.
     */
    private static Chained chainLink(List<PerformancePoint> points, NavigableMap<LocalDate, BigDecimal> flows) {
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        LocalDate peakDate = points.get(0).date();

        BigDecimal worstDecline = BigDecimal.ZERO;
        LocalDate drawdownPeakDate = null;
        LocalDate drawdownTroughDate = null;

        List<BigDecimal> returns = new ArrayList<>();
        BigDecimal bestReturn = null;
        BigDecimal worstReturn = null;
        DayReturn bestDay = null;
        DayReturn worstDay = null;

        for (int i = 1; i < points.size(); i++) {
            LocalDate date = points.get(i).date();
            BigDecimal previousValue = points.get(i - 1).totalValue();
            BigDecimal value = points.get(i).totalValue();
            BigDecimal flow = flows.getOrDefault(date, BigDecimal.ZERO);
            BigDecimal marketChange = value.subtract(flow).subtract(previousValue);

            // safeDivide returns null rather than throwing when yesterday closed at exactly zero
            // — a portfolio that went to zero and came back is a real case, and the link is
            // skipped (a return of 0) rather than being infinite.
            BigDecimal ratio = MoneyUtils.safeDivide(value.subtract(flow), previousValue, RETURN_SCALE);
            BigDecimal dayReturn = ratio == null ? BigDecimal.ZERO : ratio.subtract(BigDecimal.ONE);
            returns.add(dayReturn);

            DayReturn candidate = new DayReturn(date, pct(dayReturn), MoneyUtils.money(marketChange));
            if (bestReturn == null || dayReturn.compareTo(bestReturn) > 0) {
                bestReturn = dayReturn;
                bestDay = candidate;
            }
            if (worstReturn == null || dayReturn.compareTo(worstReturn) < 0) {
                worstReturn = dayReturn;
                worstDay = candidate;
            }

            growth = growth.multiply(BigDecimal.ONE.add(dayReturn), Compounding.MC);
            if (growth.compareTo(peak) > 0) {
                peak = growth;
                peakDate = date;
            } else {
                // peak is seeded at 1 and only ever grows, so it is never zero here.
                BigDecimal decline = MoneyUtils.safeDivide(growth, peak, RETURN_SCALE).subtract(BigDecimal.ONE);
                if (decline.compareTo(worstDecline) < 0) {
                    worstDecline = decline;
                    drawdownPeakDate = peakDate;
                    drawdownTroughDate = date;
                }
            }
        }

        return new Chained(growth, returns,
                new Drawdown(pct(worstDecline), drawdownPeakDate, drawdownTroughDate), bestDay, worstDay);
    }

    /** {@code (1 + TWR)^(365/days) − 1}. */
    private static BigDecimal annualisedPct(BigDecimal growth, long days) {
        if (days < 1) {
            // A single-day window has no elapsed time to project a year onto. Its TWR is 0,
            // which is a fact; annualising it would be an invention.
            return null;
        }
        if (growth.signum() < 0) {
            // Only reachable when a withdrawal exceeded the portfolio's own value on some day.
            // A negative growth factor has no real fractional power, so there is no answer.
            return null;
        }
        if (growth.signum() == 0) {
            return pct(BigDecimal.ONE.negate());
        }
        BigDecimal exponent = DAYS_IN_YEAR.divide(BigDecimal.valueOf(days), Compounding.MC);
        return pct(Compounding.pow(growth, exponent).subtract(BigDecimal.ONE));
    }

    /** Sample standard deviation of the daily returns, annualised by {@code √365}. */
    private static BigDecimal volatilityPct(List<BigDecimal> returns) {
        if (returns.size() < 2) {
            // A sample standard deviation of one observation is undefined, not zero.
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal dayReturn : returns) {
            sum = sum.add(dayReturn);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(returns.size()), Compounding.MC);

        BigDecimal squaredDeviations = BigDecimal.ZERO;
        for (BigDecimal dayReturn : returns) {
            BigDecimal deviation = dayReturn.subtract(mean);
            squaredDeviations = squaredDeviations.add(deviation.multiply(deviation, Compounding.MC), Compounding.MC);
        }
        BigDecimal variance = squaredDeviations.divide(BigDecimal.valueOf(returns.size() - 1L), Compounding.MC);
        BigDecimal daily = Compounding.sqrt(variance);
        return pct(daily.multiply(Compounding.sqrt(DAYS_IN_YEAR), Compounding.MC));
    }

    /** A portfolio with no transactions, or a window that closes before its first one, is a
     * valid empty answer with a valid result — never a divide-by-zero (TEST_PLAN.md §4.9). */
    private static AnalyticsResult empty(PerformanceResult series) {
        return new AnalyticsResult(series.portfolioId(), series.currency(), series.from(), series.to(), 0L,
                pct(BigDecimal.ZERO), null, null, series.summary().netContributions(),
                new Drawdown(pct(BigDecimal.ZERO), null, null), null, null, null);
    }

    /** A fraction to a percentage at money scale — {@code 0.102702} becomes {@code 10.2702},
     * the same convention as {@link MoneyUtils#pctChange}. */
    private static BigDecimal pct(BigDecimal fraction) {
        return fraction.multiply(ONE_HUNDRED).setScale(MoneyUtils.MONEY_SCALE, MoneyUtils.ROUNDING);
    }

    /** Transfers convert at their own trade date, never at the window's end (ADR-0011), and
     * identity on an unresolvable rate — the {@link ProjectionContext.FxLookup} contract, and the
     * same lambda {@link ValuationService} and {@link AllocationService} build. */
    private ProjectionContext.FxLookup tradeDateFx(CurrencyCode target) {
        return (from, on) -> fxConversion.rate(from, target, on).orElse(BigDecimal.ONE);
    }
}
