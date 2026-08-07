package com.protify.portfolio.holding;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioRepository;
import com.protify.portfolio.support.AbstractIntegrationTest;
import com.protify.portfolio.support.FreshSchema;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * day-6-dev-A.md D6-A2 — <b>the transaction-centric model's proof of correctness</b> (ADR-0002),
 * as one statement: for <b>every</b> portfolio in the database, replaying {@code txn} through
 * {@link ProjectionEngine} reproduces the stored {@code holding} rows exactly — quantity,
 * {@code avg_cost} and {@code realised_pnl}.
 *
 * <p>If this is green, the projection is genuinely derived and has not drifted. If it is red,
 * some write path bypassed the engine, and finding that here is enormously cheaper than finding
 * it in front of an assessor.
 *
 * <p>What makes it a real test rather than a tautology is that the two sides are computed
 * independently. The stored rows come from {@code V5__demo_seed.sql}, written out as literals;
 * the expected rows come from running the engine now. Neither was produced from the other at
 * runtime, so agreement is evidence rather than arithmetic performed twice.
 *
 * <p>Runs against its own schema, like {@code DemoSeedIT}: the shared one is emptied by whichever
 * fixture-building {@code *IT} happens to run first, and "every portfolio in the database" is not
 * a meaningful claim about a database another test just truncated.
 */
class ProjectionRebuildConsistencyIT extends AbstractIntegrationTest {

    private static PortfolioRepository portfolioRepository;
    private static TransactionRepository transactionRepository;
    private static HoldingRepository holdingRepository;

    @BeforeAll
    static void migrate() {
        NamedParameterJdbcTemplate jdbc = FreshSchema.migratedInto("rebuild_it");
        portfolioRepository = new PortfolioRepository(jdbc);
        transactionRepository = new TransactionRepository(jdbc);
        holdingRepository = new HoldingRepository(jdbc);
    }

    @Test
    void everyPortfolioProjectionShouldMatchAFullRebuildFromItsTransactions() {
        List<Portfolio> portfolios = portfolioRepository.findAllForSnapshotJob();
        List<String> diffs = new ArrayList<>();
        int holdingsCompared = 0;

        for (Portfolio portfolio : portfolios) {
            List<Txn> history = transactionRepository.findByPortfolioOrderByExecutedAt(portfolio.id());
            Map<Long, HoldingState> rebuilt = ProjectionEngine
                    .project(history, ProjectionContext.identity(portfolio.baseCurrency()))
                    .holdingsByInstrument();

            Map<Long, Holding> stored = new LinkedHashMap<>();
            for (Holding holding : holdingRepository.findByPortfolio(portfolio.id())) {
                stored.put(holding.instrumentId(), holding);
            }

            holdingsCompared += compare(portfolio, rebuilt, stored, diffs);
        }

        // Reported all at once: one drifted portfolio should not hide the other four, and the
        // shape of the whole diff is what tells you whether a single write path or the engine
        // itself is at fault.
        assertThat(diffs)
                .as("projection drift — replaying txn no longer reproduces holding")
                .isEmpty();

        // Without these the test passes vacuously against an empty database, which is precisely
        // the state a broken seed would leave behind — it would go green for the wrong reason.
        assertThat(portfolios).as("portfolios examined").hasSizeGreaterThanOrEqualTo(2);
        assertThat(holdingsCompared).as("holdings compared").isPositive();
    }

    /** Returns how many holdings were compared; appends a line to {@code diffs} per mismatch. */
    private int compare(Portfolio portfolio, Map<Long, HoldingState> rebuilt,
            Map<Long, Holding> stored, List<String> diffs) {

        Set<Long> instrumentIds = new TreeSet<>(rebuilt.keySet());
        instrumentIds.addAll(stored.keySet());

        for (Long instrumentId : instrumentIds) {
            HoldingState expected = rebuilt.get(instrumentId);
            Holding actual = stored.get(instrumentId);
            String where = "portfolio %d (%s), instrument %d".formatted(
                    portfolio.id(), portfolio.name(), instrumentId);

            if (expected == null) {
                diffs.add(where + ": stored in holding but no transaction history produces it");
                continue;
            }
            if (actual == null) {
                diffs.add(where + ": the rebuild produces a holding that is not stored");
                continue;
            }
            // compareTo, never equals: 0E-6 and 0.000000 are the same quantity and different
            // BigDecimals (CLAUDE.md non-negotiable #1). A sell-to-zero row makes this concrete.
            check(diffs, where, "quantity", expected.quantity(), actual.quantity());
            check(diffs, where, "avg_cost", expected.avgCost(), actual.avgCost());
            check(diffs, where, "realised_pnl", expected.realisedPnl(), actual.realisedPnl());
        }
        return instrumentIds.size();
    }

    private void check(List<String> diffs, String where, String field,
            BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            diffs.add("%s: %s rebuilt as %s but stored as %s".formatted(where, field, expected, actual));
        }
    }
}
