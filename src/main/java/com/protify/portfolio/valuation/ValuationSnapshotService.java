package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * day-5-dev-A.md D5-A2 — the cache policy over {@code portfolio_valuation_daily}.
 *
 * <p><b>The fold is the source of truth; this is a derived cache; the cache is invalidated by the
 * same event that makes it wrong.</b> That is the same relationship {@code txn} has with
 * {@code holding}, and it is the reason the table is safe to use at all: nothing here can produce
 * an answer the fold would not, only produce it faster or not at all.
 *
 * <p>Three rules make that true, and every one of them is load-bearing:
 *
 * <ol>
 *   <li><b>Completed days only.</b> Today's valuation is still moving — a price fetched at 10:00
 *       is not the close. {@link #isCompleted} is the only place in the codebase that decides
 *       what "today" means for a snapshot, so {@link PerformanceService} does not need a
 *       {@link Clock} of its own and the two cannot drift apart.</li>
 *   <li><b>A write or delete invalidates from that transaction's date onward</b>
 *       ({@link #invalidateFrom}). A mid-history delete changes every subsequent day.</li>
 *   <li><b>A missing snapshot is computed, never a gap.</b> {@link PerformanceService} folds any
 *       uncovered day live and hands it back here through {@link #store}, which is best-effort:
 *       a chart must not fail because a cache write did.</li>
 * </ol>
 */
@Service
public class ValuationSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ValuationSnapshotService.class);

    private final ValuationSnapshotRepository repository;
    private final Clock clock;

    public ValuationSnapshotService(ValuationSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** A day is snapshottable once it is over. UTC, like everything else here (CLAUDE.md #9). */
    public boolean isCompleted(LocalDate day) {
        return day.isBefore(LocalDate.now(clock));
    }

    /**
     * Cached valuations for the completed days within {@code [from, to]}. The window is clamped
     * to yesterday before the query runs, so a row for today — which should never exist, but
     * would be wrong if it did — can never be served.
     */
    public NavigableMap<LocalDate, ValuationSnapshot> completedSnapshots(
            long portfolioId, CurrencyCode currency, LocalDate from, LocalDate to) {

        LocalDate lastCompleted = LocalDate.now(clock).minusDays(1);
        LocalDate effectiveTo = to.isAfter(lastCompleted) ? lastCompleted : to;
        if (from.isAfter(effectiveTo)) {
            return new TreeMap<>();
        }
        return repository.findRange(portfolioId, currency, from, effectiveTo);
    }

    /**
     * Memoises days computed by the fold. Best-effort by design: this runs on a read path, and a
     * failed cache write must never turn a working chart into a 500. The next request recomputes
     * the day and tries again.
     */
    public void store(List<ValuationSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            repository.upsertAll(snapshots);
        } catch (RuntimeException e) {
            log.warn("Could not memoise {} valuation snapshot(s) for portfolio {}: {}",
                    snapshots.size(), snapshots.get(0).portfolioId(), e.getMessage());
        }
    }

    /**
     * Drops every snapshot on or after {@code from}.
     *
     * <p>Called from inside {@code TransactionService}'s transaction, so the invalidation commits
     * or rolls back with the write that caused it — a delete that rolled back must not leave the
     * cache emptied, and a delete that committed must not leave it populated
     * (CLAUDE.md non-negotiable #12). Unlike {@link #store}, this one is <b>not</b> best-effort:
     * a failure here has to take the write down with it.
     */
    public void invalidateFrom(long portfolioId, LocalDate from) {
        int dropped = repository.deleteFrom(portfolioId, from);
        if (dropped > 0) {
            log.debug("Invalidated {} valuation snapshot(s) for portfolio {} from {}", dropped, portfolioId, from);
        }
    }

    /** Everything, for a portfolio being deleted outright. */
    public void invalidateAll(long portfolioId) {
        repository.deleteAllForPortfolio(portfolioId);
    }
}
