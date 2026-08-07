package com.protify.portfolio.valuation;

import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0011's "base-currency cost basis by replaying transaction history with FX resolved at
 * each transaction's date" — the half of the maths {@code holding.ProjectionEngine} does not
 * cover. That engine's {@code HoldingState.avgCost}/{@code realisedPnl} are deliberately always
 * native (its own javadoc says so, in either run), because a single weighted-average native
 * cost has no one trade date to convert at. So this is a second, independent fold living in
 * {@code valuation/} rather than a change to Day 2's frozen-in-practice, heavily tested engine:
 * quantity is tracked the same way (BUY adds, SELL subtracts), but cost and realised P&L are
 * accumulated in the target currency, using the FX rate on each transaction's own date. {@link
 * ValuationService} and {@code PerformanceService} both fold through this, so the two Day 3 read
 * paths cannot silently disagree on cost basis.
 *
 * <p>Trusts that a well-formed transaction history never contains a SELL that exceeds its
 * holding — {@link com.protify.portfolio.transaction.TransactionService} already guarantees that
 * at write time via {@code ProjectionEngine}, so this fold does not repeat the check.
 */
public final class ValuationFolder {

    private ValuationFolder() {
    }

    public record InstrumentPosition(BigDecimal quantity, BigDecimal costBasis, BigDecimal realisedPnl) {
    }

    /** {@code cashBalance} accumulates every transaction's native cash flow converted to the
     * target currency at that transaction's own date — the same "cost-basis run" cash figure
     * {@code ProjectionResult.cashBalance()} documents, computed independently here so this
     * class has no dependency on {@code holding.ProjectionEngine}. */
    public record Snapshot(Map<Long, InstrumentPosition> positions, BigDecimal cashBalance) {
    }

    public static Snapshot fold(List<Txn> txns, ProjectionContext.FxLookup fxLookup) {
        Accumulator accumulator = new Accumulator();
        List<Txn> sorted = new ArrayList<>(txns);
        sorted.sort(Comparator.comparing(Txn::executedAt).thenComparing(Txn::id, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Txn txn : sorted) {
            accumulator.apply(txn, fxLookup);
        }
        return accumulator.snapshot();
    }

    /** Mutable, resumable version of {@link #fold} — {@code PerformanceService} advances one of
     * these forward a day (and zero or more transactions) at a time instead of re-folding the
     * whole history for every point, per ADR-0010's "advancing a transaction cursor". */
    public static final class Accumulator {

        private final Map<Long, InstrumentPosition> positions = new LinkedHashMap<>();
        private BigDecimal cash = BigDecimal.ZERO;

        public void apply(Txn txn, ProjectionContext.FxLookup fxLookup) {
            BigDecimal fx = fxLookup.rate(txn.currency(), dateOf(txn));
            cash = cash.add(applyCashFlow(txn, fx));
        }

        public Snapshot snapshot() {
            return new Snapshot(Map.copyOf(positions), MoneyUtils.money(cash));
        }

        private BigDecimal applyCashFlow(Txn txn, BigDecimal fx) {
            return switch (txn.txnType()) {
                case BUY -> applyBuy(txn, fx);
                case SELL -> applySell(txn, fx);
                case DIVIDEND -> txn.price().multiply(fx);
                case DEPOSIT -> txn.price().multiply(fx);
                case WITHDRAWAL -> txn.price().multiply(fx).negate();
                case FEE -> txn.price().multiply(fx).negate();
            };
        }

        private BigDecimal applyBuy(Txn txn, BigDecimal fx) {
            InstrumentPosition prev = positions.get(txn.instrumentId());
            BigDecimal prevQty = prev == null ? BigDecimal.ZERO : prev.quantity();
            BigDecimal prevCostBasis = prev == null ? BigDecimal.ZERO : prev.costBasis();
            BigDecimal prevRealised = prev == null ? BigDecimal.ZERO : prev.realisedPnl();

            BigDecimal newQty = prevQty.add(txn.quantity());
            BigDecimal nativeCost = txn.quantity().multiply(txn.price()).add(txn.fees());
            BigDecimal baseCost = nativeCost.multiply(fx);
            BigDecimal newCostBasis = MoneyUtils.money(prevCostBasis.add(baseCost));

            positions.put(txn.instrumentId(),
                    new InstrumentPosition(MoneyUtils.quantity(newQty), newCostBasis, prevRealised));
            return baseCost.negate();
        }

        private BigDecimal applySell(Txn txn, BigDecimal fx) {
            InstrumentPosition prev = positions.get(txn.instrumentId());
            BigDecimal prevQty = prev == null ? BigDecimal.ZERO : prev.quantity();
            BigDecimal prevCostBasis = prev == null ? BigDecimal.ZERO : prev.costBasis();
            BigDecimal prevRealised = prev == null ? BigDecimal.ZERO : prev.realisedPnl();

            BigDecimal newQty = prevQty.subtract(txn.quantity());
            BigDecimal costRemoved = prevQty.signum() == 0
                    ? BigDecimal.ZERO
                    : prevCostBasis.multiply(txn.quantity()).divide(prevQty, MoneyUtils.MONEY_SCALE, MoneyUtils.ROUNDING);
            BigDecimal newCostBasis = MoneyUtils.money(prevCostBasis.subtract(costRemoved));

            BigDecimal nativeProceeds = txn.quantity().multiply(txn.price()).subtract(txn.fees());
            BigDecimal baseProceeds = nativeProceeds.multiply(fx);
            BigDecimal newRealised = MoneyUtils.money(prevRealised.add(baseProceeds.subtract(costRemoved)));

            positions.put(txn.instrumentId(),
                    new InstrumentPosition(MoneyUtils.quantity(newQty), newCostBasis, newRealised));
            return baseProceeds;
        }
    }

    /** Which calendar day a transaction belongs to, in UTC (CLAUDE.md non-negotiable #9).
     * Public because {@code TransactionService} decides how far back to invalidate the valuation
     * cache from exactly this date (day-5-dev-A.md D5-A2) — a second copy of the rule elsewhere
     * is how one of them ends up using the server's local zone. */
    public static java.time.LocalDate dateOf(Txn txn) {
        return txn.executedAt().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
