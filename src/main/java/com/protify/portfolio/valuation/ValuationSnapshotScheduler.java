package com.protify.portfolio.valuation;

import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * day-5-dev-A.md D5-A2's "a nightly job writes one row per portfolio per completed day".
 *
 * <p><b>It has no valuation logic of its own, on purpose.</b> It asks {@link PerformanceService}
 * for the last few days of each portfolio's series, and that read path memoises every completed
 * day it had to fold. A job that computed and wrote snapshots itself would be a second
 * implementation of the fold, free to disagree with the one the chart uses — which is the exact
 * failure mode {@link ValuationSnapshot} exists to rule out.
 *
 * <p>It also lives in its own class rather than on {@link ValuationSnapshotService} because it
 * needs {@link PerformanceService}, which needs {@code ValuationSnapshotService} — putting the
 * two together would be a constructor cycle Spring cannot construct.
 *
 * <p><b>Why it rewrites a window rather than just yesterday.</b> {@code price_history} is
 * backfilled: a provider outage on Tuesday means Tuesday's closes may not land until Thursday,
 * and a snapshot taken in between would freeze a forward-filled value forever. Re-deriving the
 * last {@value #REWRITE_WINDOW_DAYS} days each night costs almost nothing and closes that hole.
 */
@Component
public class ValuationSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(ValuationSnapshotScheduler.class);

    /** Long enough to cover a provider outage across a weekend plus a working day. */
    static final int REWRITE_WINDOW_DAYS = 7;

    private final PortfolioRepository portfolioRepository;
    private final PerformanceService performanceService;
    private final ValuationSnapshotService snapshotService;
    private final Clock clock;

    public ValuationSnapshotScheduler(
            PortfolioRepository portfolioRepository,
            PerformanceService performanceService,
            ValuationSnapshotService snapshotService,
            Clock clock) {
        this.portfolioRepository = portfolioRepository;
        this.performanceService = performanceService;
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    /**
     * Defaulted in code rather than in {@code application.properties}, which is Dev B's file.
     * 02:30 UTC: after the day is unambiguously over everywhere, before the 06:15 FX refresh.
     */
    @Scheduled(cron = "${valuation.snapshot.cron:0 30 2 * * *}")
    public void materialiseCompletedDays() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        LocalDate from = yesterday.minusDays(REWRITE_WINDOW_DAYS - 1L);

        List<Portfolio> portfolios = portfolioRepository.findAllForSnapshotJob();
        int materialised = 0;
        for (Portfolio portfolio : portfolios) {
            try {
                // Drop the window first so the read path genuinely re-derives it rather than
                // handing back the rows it is meant to be refreshing.
                snapshotService.invalidateFrom(portfolio.id(), from);
                performanceService.getPerformance(portfolio.userId(), portfolio.id(), from, yesterday,
                        portfolio.baseCurrency());
                materialised++;
            } catch (RuntimeException e) {
                // One portfolio with an unpriceable instrument must not stop the other 99.
                log.warn("Could not materialise valuations for portfolio {}: {}",
                        portfolio.id(), e.getMessage());
            }
        }
        log.info("Materialised valuation snapshots for {}/{} portfolios over {} to {}",
                materialised, portfolios.size(), from, yesterday);
    }
}
