import type { Performance, Transaction } from '../api/types';

export interface DailyReturn {
  date: string;
  /** A fraction, e.g. `0.0125` is +1.25% — not net of fees beyond what the server's valuation
   * already folds in. */
  value: number;
}

export interface AnalyticsResult {
  /** Time-weighted return over the window, as a fraction. `null` when there are fewer than two
   * priced points to compute a return from. */
  twr: number | null;
  /** TWR compounded to a 365-day year. `null` under the same condition as {@link twr}, or when
   * the window is zero days wide. */
  annualisedReturn: number | null;
  /** Largest peak-to-trough decline in `totalValue` over the window, as a non-positive fraction
   * (0 means the value never fell below an earlier high). Computed on the raw value series, not
   * cash-flow-adjusted like {@link twr} — a deposit can still show as a "rise" here, which is the
   * conventional, simpler meaning of "max drawdown" most portfolio trackers show. */
  maxDrawdown: number | null;
  bestDay: DailyReturn | null;
  worstDay: DailyReturn | null;
}

const EMPTY: AnalyticsResult = {
  twr: null,
  annualisedReturn: null,
  maxDrawdown: null,
  bestDay: null,
  worstDay: null,
};

/**
 * day-5-dev-C.md D5-C2. There is no backend `AnalyticsService` (Dev A's, never built) — every
 * figure here is derived client-side from data the app already has: `GET .../performance`'s
 * daily points, and `DEPOSIT`/`WITHDRAWAL` transactions for the cash flows that separate "the
 * market moved" from "the customer moved money" (the same distinction `PerformanceSummary`
 * documents for its own `percentChange`).
 *
 * <p>TWR links one daily return per day: `(value_t - cashFlow_t) / value_{t-1} - 1`, so a deposit
 * or withdrawal on a given day is subtracted out of that day's return before it's linked into the
 * product — depositing money is never counted as a gain, and withdrawing it is never counted as
 * a loss. `bestDay`/`worstDay` are the largest and smallest of those same adjusted returns, so a
 * deposit day cannot masquerade as the best trading day.
 */
export function computeAnalytics(performance: Performance | null, transactions: Transaction[]): AnalyticsResult {
  if (!performance || performance.points.length < 2) {
    return EMPTY;
  }

  const cashFlowByDate = externalCashFlowsByDate(transactions);
  const points = performance.points;

  const dailyReturns: DailyReturn[] = [];
  let twrFactor = 1;
  let peak = Number(points[0].totalValue.amount);
  let maxDrawdown = 0;

  for (let i = 1; i < points.length; i++) {
    const previousValue = Number(points[i - 1].totalValue.amount);
    const value = Number(points[i].totalValue.amount);
    const cashFlow = cashFlowByDate.get(points[i].date) ?? 0;

    if (previousValue > 0) {
      const dailyReturn = (value - cashFlow) / previousValue - 1;
      dailyReturns.push({ date: points[i].date, value: dailyReturn });
      twrFactor *= 1 + dailyReturn;
    }

    if (value > peak) {
      peak = value;
    } else if (peak > 0) {
      maxDrawdown = Math.min(maxDrawdown, (value - peak) / peak);
    }
  }

  if (dailyReturns.length === 0) {
    return EMPTY;
  }

  const twr = twrFactor - 1;
  const windowDays = daysBetween(performance.from, performance.to);
  const annualisedReturn = windowDays > 0 ? Math.pow(twrFactor, 365 / windowDays) - 1 : null;

  const bestDay = dailyReturns.reduce((best, day) => (day.value > best.value ? day : best));
  const worstDay = dailyReturns.reduce((worst, day) => (day.value < worst.value ? day : worst));

  return { twr, annualisedReturn, maxDrawdown, bestDay, worstDay };
}

function externalCashFlowsByDate(transactions: Transaction[]): Map<string, number> {
  const byDate = new Map<string, number>();
  for (const txn of transactions) {
    if (txn.type !== 'DEPOSIT' && txn.type !== 'WITHDRAWAL') {
      continue;
    }
    if (!txn.totalBase) {
      continue;
    }
    const date = txn.executedAt.slice(0, 10);
    const signedAmount = Number(txn.totalBase.amount) * (txn.type === 'WITHDRAWAL' ? -1 : 1);
    byDate.set(date, (byDate.get(date) ?? 0) + signedAmount);
  }
  return byDate;
}

function daysBetween(from: string, to: string): number {
  const start = Date.parse(from);
  const end = Date.parse(to);
  return Math.round((end - start) / 86_400_000);
}
