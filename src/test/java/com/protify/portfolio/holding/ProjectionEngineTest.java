package com.protify.portfolio.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The crown-jewel test class (day-2-dev-A.md). Every fixture below is hand-computed against the
 * rules in REFERENCE_DESIGN §3 / ADR-0005 / ADR-0011, not derived by running {@link ProjectionEngine}
 * and asserting on its own output.
 */
class ProjectionEngineTest {

    private static final long PORTFOLIO_ID = 1L;
    private static final long AAPL = 100L;
    private static final long MSFT = 200L;

    private static Txn txn(Long id, TransactionType type, Long instrumentId, String qty, String price,
            String fees, CurrencyCode currency, String executedAt) {
        return new Txn(id, PORTFOLIO_ID, instrumentId, type, new BigDecimal(qty), new BigDecimal(price),
                new BigDecimal(fees), currency, Instant.parse(executedAt), null, null);
    }

    private static Txn buy(long id, long instrumentId, String qty, String price, String fees, String executedAt) {
        return txn(id, TransactionType.BUY, instrumentId, qty, price, fees, CurrencyCode.USD, executedAt);
    }

    private static Txn sell(long id, long instrumentId, String qty, String price, String fees, String executedAt) {
        return txn(id, TransactionType.SELL, instrumentId, qty, price, fees, CurrencyCode.USD, executedAt);
    }

    private static Txn cashOnly(long id, TransactionType type, String amount, String executedAt) {
        return txn(id, type, null, "0", amount, "0", CurrencyCode.USD, executedAt);
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal(expected));
    }

    @Nested
    class BuyRules {

        @Test
        void singleBuySetsQuantityAndAvgCostIncludingFees() {
            var result = ProjectionEngine.project(
                    List.of(buy(1, AAPL, "10.000000", "100.0000", "5.0000", "2026-01-01T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "10.000000");
            assertMoney(state.avgCost(), "100.5000");
            assertMoney(state.realisedPnl(), "0.0000");
            assertMoney(result.cashBalance(), "-1005.0000");
        }

        @Test
        void secondBuyAtDifferentPriceProducesWeightedAverage() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "10.000000", "100.0000", "10.0000", "2026-01-01T00:00:00Z"),
                            buy(2, AAPL, "10.000000", "110.0000", "10.0000", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "20.000000");
            assertMoney(state.avgCost(), "106.0000");
        }

        @Test
        void buyBeyondCashSucceedsAndLeavesCashNegative() {
            var result = ProjectionEngine.project(
                    List.of(
                            cashOnly(1, TransactionType.DEPOSIT, "100.0000", "2026-01-01T00:00:00Z"),
                            buy(2, AAPL, "10.000000", "50.0000", "0", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            assertMoney(result.cashBalance(), "-400.0000");
            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "10.000000");
            assertMoney(state.avgCost(), "50.0000");
        }

        @Test
        void fractionalQuantitiesAtSixDpRoundTripExactly() {
            var result = ProjectionEngine.project(
                    List.of(buy(1, AAPL, "0.123456", "10.0000", "0", "2026-01-01T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertThat(state.quantity()).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("0.123456"));
            assertThat(state.quantity().scale()).isEqualTo(6);
        }

        @Test
        void roundingOfARecurringDecimalStaysAtScaleFour() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "1.000000", "100.0000", "0", "2026-01-01T00:00:00Z"),
                            buy(2, AAPL, "1.000000", "100.0000", "0", "2026-01-02T00:00:00Z"),
                            buy(3, AAPL, "1.000000", "101.0000", "0", "2026-01-03T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            // 301 / 3 = 100.3333333... -> HALF_UP at scale 4 -> 100.3333
            assertMoney(state.avgCost(), "100.3333");
            assertThat(state.avgCost().scale()).isEqualTo(4);
        }
    }

    @Nested
    class SellRules {

        @Test
        void sellReducesQuantityAndLeavesAvgCostUntouched() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "10.000000", "100.0000", "0", "2026-01-01T00:00:00Z"),
                            sell(2, AAPL, "4.000000", "150.0000", "0", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "6.000000");
            assertMoney(state.avgCost(), "100.0000");
        }

        @Test
        void sellComputesRealisedPnlAsPriceMinusAvgCostTimesQtyMinusFees() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "10.000000", "50.0000", "0", "2026-01-01T00:00:00Z"),
                            sell(2, AAPL, "5.000000", "70.0000", "3.0000", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.realisedPnl(), "97.0000");
        }

        @Test
        void shouldAllowSellOfExactlyTheHeldQuantityLeavingZero() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "10.000000", "50.0000", "0", "2026-01-01T00:00:00Z"),
                            sell(2, AAPL, "10.000000", "80.0000", "0", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            assertThat(result.holdingsByInstrument()).containsKey(AAPL);
            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "0.000000");
            assertMoney(state.avgCost(), "50.0000");
            assertMoney(state.realisedPnl(), "300.0000");
        }

        @Test
        void shouldRejectSellWhenQuantityExceedsHolding() {
            List<Txn> txns = List.of(
                    buy(1, AAPL, "10.000000", "50.0000", "0", "2026-01-01T00:00:00Z"),
                    sell(2, AAPL, "10.000001", "80.0000", "0", "2026-01-02T00:00:00Z"));

            assertThatThrownBy(() -> ProjectionEngine.project(txns, ProjectionContext.identity(CurrencyCode.USD)))
                    .isInstanceOf(InsufficientQuantityException.class)
                    .satisfies(ex -> {
                        InsufficientQuantityException iqe = (InsufficientQuantityException) ex;
                        assertThat(iqe.requested()).usingComparator(BigDecimal::compareTo)
                                .isEqualTo(new BigDecimal("10.000001"));
                        assertThat(iqe.held()).usingComparator(BigDecimal::compareTo)
                                .isEqualTo(new BigDecimal("10.000000"));
                    });
        }

        @Test
        void sellOfFractionalBoundaryExactlyHeldSucceeds() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "0.523100", "10.0000", "0", "2026-01-01T00:00:00Z"),
                            sell(2, AAPL, "0.523100", "12.0000", "0", "2026-01-02T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            assertMoney(result.holdingsByInstrument().get(AAPL).quantity(), "0.000000");
        }

        /** TEST_PLAN.md §4.1's fractional boundary, negative half: one micro-unit past a
         * fractional holding must fail. The pair with the test above is the point — a
         * {@code <=} where the engine has {@code <} would pass one of them and fail the other. */
        @Test
        void shouldRejectSellOfOneMicroUnitBeyondAFractionalHolding() {
            List<Txn> txns = List.of(
                    buy(1, AAPL, "0.523100", "10.0000", "0", "2026-01-01T00:00:00Z"),
                    sell(2, AAPL, "0.523101", "12.0000", "0", "2026-01-02T00:00:00Z"));

            assertThatThrownBy(() -> ProjectionEngine.project(txns, ProjectionContext.identity(CurrencyCode.USD)))
                    .isInstanceOf(InsufficientQuantityException.class)
                    .satisfies(ex -> {
                        InsufficientQuantityException iqe = (InsufficientQuantityException) ex;
                        assertThat(iqe.requested()).usingComparator(BigDecimal::compareTo)
                                .isEqualTo(new BigDecimal("0.523101"));
                        assertThat(iqe.held()).usingComparator(BigDecimal::compareTo)
                                .isEqualTo(new BigDecimal("0.523100"));
                    });
        }
    }

    @Nested
    class CashRules {

        @Test
        void depositWithdrawalDividendAndFeeEachMoveCashCorrectly() {
            var result = ProjectionEngine.project(
                    List.of(
                            cashOnly(1, TransactionType.DEPOSIT, "1000.0000", "2026-01-01T00:00:00Z"),
                            cashOnly(2, TransactionType.WITHDRAWAL, "200.0000", "2026-01-02T00:00:00Z"),
                            cashOnly(3, TransactionType.DIVIDEND, "50.0000", "2026-01-03T00:00:00Z"),
                            cashOnly(4, TransactionType.FEE, "10.0000", "2026-01-04T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            assertMoney(result.cashBalance(), "840.0000");
            assertThat(result.holdingsByInstrument()).isEmpty();
        }
    }

    @Nested
    class StructuralRules {

        @Test
        void transactionsOutOfChronologicalOrderAreSortedBeforeFolding() {
            Txn buy1 = buy(1, AAPL, "10.000000", "100.0000", "0", "2026-01-01T00:00:00Z");
            Txn buy2 = buy(2, AAPL, "10.000000", "200.0000", "0", "2026-01-02T00:00:00Z");
            Txn sell1 = sell(3, AAPL, "5.000000", "300.0000", "0", "2026-01-03T00:00:00Z");

            // Deliberately out of order — if the engine folded this order literally, the SELL
            // would run against an empty holding and throw.
            var result = ProjectionEngine.project(List.of(sell1, buy1, buy2), ProjectionContext.identity(CurrencyCode.USD));

            HoldingState state = result.holdingsByInstrument().get(AAPL);
            assertMoney(state.quantity(), "15.000000");
            assertMoney(state.avgCost(), "150.0000");
            assertMoney(state.realisedPnl(), "750.0000");
        }

        @Test
        void twoInstrumentsStayIndependent() {
            var result = ProjectionEngine.project(
                    List.of(
                            buy(1, AAPL, "10.000000", "100.0000", "0", "2026-01-01T00:00:00Z"),
                            buy(2, MSFT, "5.000000", "40.0000", "0", "2026-01-01T00:00:00Z")),
                    ProjectionContext.identity(CurrencyCode.USD));

            assertMoney(result.holdingsByInstrument().get(AAPL).quantity(), "10.000000");
            assertMoney(result.holdingsByInstrument().get(AAPL).avgCost(), "100.0000");
            assertMoney(result.holdingsByInstrument().get(MSFT).quantity(), "5.000000");
            assertMoney(result.holdingsByInstrument().get(MSFT).avgCost(), "40.0000");
        }

        @Test
        void emptyTransactionListYieldsEmptyResultZeroCashNoException() {
            var result = ProjectionEngine.project(List.of(), ProjectionContext.identity(CurrencyCode.USD));

            assertThat(result.holdingsByInstrument()).isEmpty();
            assertMoney(result.cashBalance(), "0.0000");
        }

        @Test
        void sameInputTwiceProducesIdenticalResultDeterminism() {
            List<Txn> txns = List.of(
                    buy(1, AAPL, "10.000000", "100.0000", "5.0000", "2026-01-01T00:00:00Z"),
                    sell(2, AAPL, "4.000000", "150.0000", "1.0000", "2026-01-02T00:00:00Z"),
                    buy(3, MSFT, "3.000000", "40.0000", "0", "2026-01-01T00:00:00Z"));

            var first = ProjectionEngine.project(txns, ProjectionContext.identity(CurrencyCode.USD));
            var second = ProjectionEngine.project(txns, ProjectionContext.identity(CurrencyCode.USD));

            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    class BaseCurrencyRules {

        @Test
        void baseCurrencyRunConvertsAtEachTransactionsDateNotToday() {
            Map<LocalDate, BigDecimal> rates = Map.of(
                    LocalDate.parse("2026-01-01"), new BigDecimal("80"),
                    LocalDate.parse("2026-01-10"), new BigDecimal("90"));
            ProjectionContext ctx = new ProjectionContext(CurrencyCode.INR, (from, on) -> rates.get(on));

            var result = ProjectionEngine.project(
                    List.of(
                            cashOnly(1, TransactionType.DEPOSIT, "100.0000", "2026-01-01T00:00:00Z"),
                            cashOnly(2, TransactionType.DEPOSIT, "100.0000", "2026-01-10T00:00:00Z")),
                    ctx);

            // 100 * 80 + 100 * 90 = 17000 — proves each txn's own date is used, not a single rate.
            assertMoney(result.cashBalance(), "17000.0000");
        }

        @Test
        void baseCurrencyRunOfSingleCurrencyPortfolioEqualsNativeRun() {
            List<Txn> txns = List.of(
                    buy(1, AAPL, "10.000000", "100.0000", "5.0000", "2026-01-01T00:00:00Z"),
                    sell(2, AAPL, "4.000000", "150.0000", "1.0000", "2026-01-02T00:00:00Z"));

            var nativeRun = ProjectionEngine.project(txns, ProjectionContext.identity(CurrencyCode.USD));
            var baseRun = ProjectionEngine.project(txns, new ProjectionContext(CurrencyCode.USD, (from, on) -> BigDecimal.ONE));

            assertThat(baseRun).isEqualTo(nativeRun);
        }

        @Test
        void fxLookupFallsBackToTheMostRecentPriorRate() {
            NavigableMap<LocalDate, BigDecimal> rates = new TreeMap<>();
            rates.put(LocalDate.parse("2026-01-01"), new BigDecimal("80"));
            // No rate stored for 2026-01-05 — the lookup itself falls back via floorEntry.
            ProjectionContext ctx = new ProjectionContext(CurrencyCode.INR,
                    (from, on) -> rates.floorEntry(on).getValue());

            var result = ProjectionEngine.project(
                    List.of(cashOnly(1, TransactionType.DEPOSIT, "100.0000", "2026-01-05T00:00:00Z")),
                    ctx);

            assertMoney(result.cashBalance(), "8000.0000");
        }

        @Test
        void avgCostStaysInNativeCurrencyEvenInABaseCurrencyRun() {
            ProjectionContext ctx = new ProjectionContext(CurrencyCode.INR, (from, on) -> new BigDecimal("80"));

            var result = ProjectionEngine.project(
                    List.of(buy(1, AAPL, "10.000000", "100.0000", "0", "2026-01-01T00:00:00Z")),
                    ctx);

            // Not 8000 — avgCost is never FX-converted, only cash is.
            assertMoney(result.holdingsByInstrument().get(AAPL).avgCost(), "100.0000");
            assertMoney(result.cashBalance(), "-80000.0000");
        }
    }
}
