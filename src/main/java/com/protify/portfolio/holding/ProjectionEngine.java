package com.protify.portfolio.holding;

import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure fold every stored and computed holdings figure ultimately runs through
 * (PLAN.md §5): a native run is upserted into {@code holding}, a base-currency run feeds
 * valuation, and (Day 3) the same fold with a date cursor produces the performance series.
 * No Spring, no JDBC, no {@code LocalDate.now()} — given the same input it always returns the
 * same output, which is what makes "delete a transaction and rebuild" safe: it is the same
 * code path as an ordinary write, not a special case that can drift from it.
 *
 * <p>FX (via {@link ProjectionContext#fxLookup()}) is applied only to the cash accumulator.
 * {@code avgCost}/{@code realisedPnl} are computed from each transaction's native
 * {@code price}/{@code fees} and are never converted, in either run (ADR-0011).
 */
public final class ProjectionEngine {

    private ProjectionEngine() {
    }

    public static ProjectionResult project(List<Txn> orderedTxns, ProjectionContext ctx) {
        List<Txn> sorted = new ArrayList<>(orderedTxns);
        sorted.sort(Comparator.comparing(Txn::executedAt).thenComparing(Txn::id, Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, HoldingState> states = new LinkedHashMap<>();
        BigDecimal cash = BigDecimal.ZERO;

        for (Txn txn : sorted) {
            BigDecimal fxRate = ctx.fxLookup().rate(txn.currency(), txn.executedAt().atZone(ZoneOffset.UTC).toLocalDate());
            cash = cash.add(nativeCashFlow(states, txn).multiply(fxRate));
        }

        return new ProjectionResult(Map.copyOf(states), MoneyUtils.money(cash));
    }

    /** Applies one transaction's effect on {@code states} and returns its native-currency cash delta. */
    private static BigDecimal nativeCashFlow(Map<Long, HoldingState> states, Txn txn) {
        return switch (txn.txnType()) {
            case BUY -> applyBuy(states, txn);
            case SELL -> applySell(states, txn);
            case DIVIDEND -> txn.price();
            case DEPOSIT -> txn.price();
            case WITHDRAWAL -> txn.price().negate();
            case FEE -> txn.price().negate();
        };
    }

    private static BigDecimal applyBuy(Map<Long, HoldingState> states, Txn txn) {
        HoldingState prev = states.get(txn.instrumentId());
        BigDecimal prevQty = prev == null ? BigDecimal.ZERO : prev.quantity();
        BigDecimal prevAvgCost = prev == null ? BigDecimal.ZERO : prev.avgCost();
        BigDecimal prevRealisedPnl = prev == null ? BigDecimal.ZERO : prev.realisedPnl();

        BigDecimal newQty = prevQty.add(txn.quantity());
        BigDecimal totalCost = prevQty.multiply(prevAvgCost)
                .add(txn.quantity().multiply(txn.price()))
                .add(txn.fees());
        BigDecimal newAvgCost = totalCost.divide(newQty, MoneyUtils.MONEY_SCALE, MoneyUtils.ROUNDING);

        states.put(txn.instrumentId(), new HoldingState(
                txn.instrumentId(), MoneyUtils.quantity(newQty), newAvgCost, prevRealisedPnl));

        return txn.quantity().multiply(txn.price()).add(txn.fees()).negate();
    }

    private static BigDecimal applySell(Map<Long, HoldingState> states, Txn txn) {
        HoldingState prev = states.get(txn.instrumentId());
        BigDecimal held = prev == null ? BigDecimal.ZERO : prev.quantity();

        if (held.compareTo(txn.quantity()) < 0) {
            throw new InsufficientQuantityException(String.valueOf(txn.instrumentId()), txn.quantity(), held);
        }

        BigDecimal avgCost = prev == null ? BigDecimal.ZERO : prev.avgCost();
        BigDecimal prevRealisedPnl = prev == null ? BigDecimal.ZERO : prev.realisedPnl();
        BigDecimal newQty = held.subtract(txn.quantity());
        BigDecimal realisedPnlDelta = txn.price().subtract(avgCost).multiply(txn.quantity()).subtract(txn.fees());
        BigDecimal newRealisedPnl = MoneyUtils.money(prevRealisedPnl.add(realisedPnlDelta));

        states.put(txn.instrumentId(), new HoldingState(
                txn.instrumentId(), MoneyUtils.quantity(newQty), avgCost, newRealisedPnl));

        return txn.quantity().multiply(txn.price()).subtract(txn.fees());
    }
}
