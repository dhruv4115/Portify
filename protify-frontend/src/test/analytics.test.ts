import { describe, expect, it } from 'vitest';
import { computeAnalytics } from '../lib/analytics';
import type { Performance, Transaction } from '../api/types';

function point(date: string, totalValue: string) {
  return {
    date,
    marketValue: { amount: totalValue, currency: 'USD' as const },
    costBasis: { amount: '0', currency: 'USD' as const },
    cashBalance: { amount: '0', currency: 'USD' as const },
    totalValue: { amount: totalValue, currency: 'USD' as const },
    unrealisedPnl: { amount: '0', currency: 'USD' as const },
    filled: false,
  };
}

function performance(overrides: Partial<Performance> = {}): Performance {
  return {
    portfolioId: 1,
    currency: 'USD',
    from: '2026-01-01',
    to: '2026-01-04',
    interval: 'DAILY',
    points: [],
    summary: {
      startValue: { amount: '0', currency: 'USD' },
      endValue: { amount: '0', currency: 'USD' },
      absoluteChange: { amount: '0', currency: 'USD' },
      percentChange: null,
      netContributions: { amount: '0', currency: 'USD' },
    },
    dataQuality: { priceAsOf: null, rateAsOf: null, stale: false },
    ...overrides,
  };
}

function deposit(date: string, amount: string): Transaction {
  return {
    id: 1,
    type: 'DEPOSIT',
    instrument: null,
    quantity: '0',
    price: { amount, currency: 'USD' },
    fees: { amount: '0', currency: 'USD' },
    totalNative: { amount, currency: 'USD' },
    totalBase: { amount, currency: 'USD' },
    fxRateApplied: null,
    executedAt: `${date}T09:00:00Z`,
    note: null,
    warnings: [],
  };
}

describe('computeAnalytics', () => {
  it('returns nulls when there are fewer than two priced points', () => {
    const result = computeAnalytics(performance({ points: [point('2026-01-01', '1000')] }), []);
    expect(result.twr).toBeNull();
    expect(result.annualisedReturn).toBeNull();
    expect(result.maxDrawdown).toBeNull();
    expect(result.bestDay).toBeNull();
    expect(result.worstDay).toBeNull();
  });

  it('returns nulls when performance is not loaded yet', () => {
    expect(computeAnalytics(null, []).twr).toBeNull();
  });

  it('computes a pure market TWR with no cash flows', () => {
    // +10% day 1, then flat — TWR should compound to +10%, not be diluted by day 2.
    const perf = performance({
      from: '2026-01-01',
      to: '2026-01-03',
      points: [point('2026-01-01', '1000'), point('2026-01-02', '1100'), point('2026-01-03', '1100')],
    });

    const result = computeAnalytics(perf, []);

    expect(result.twr).not.toBeNull();
    expect(result.twr!).toBeCloseTo(0.1, 6);
  });

  it('excludes a deposit from counting as investment gain', () => {
    // Day 2: value jumps from 1000 to 1500 purely because of a 500 deposit — the market itself
    // did not move, so TWR for that day must be ~0, not +50%.
    const perf = performance({
      from: '2026-01-01',
      to: '2026-01-02',
      points: [point('2026-01-01', '1000'), point('2026-01-02', '1500')],
    });

    const result = computeAnalytics(perf, [deposit('2026-01-02', '500')]);

    expect(result.twr!).toBeCloseTo(0, 6);
  });

  it('excludes a withdrawal from counting as investment loss', () => {
    const perf = performance({
      from: '2026-01-01',
      to: '2026-01-02',
      points: [point('2026-01-01', '1000'), point('2026-01-02', '700')],
    });

    const result = computeAnalytics(perf, [
      { ...deposit('2026-01-02', '300'), type: 'WITHDRAWAL' },
    ]);

    expect(result.twr!).toBeCloseTo(0, 6);
  });

  it('finds max drawdown as the deepest peak-to-trough decline', () => {
    const perf = performance({
      from: '2026-01-01',
      to: '2026-01-04',
      points: [
        point('2026-01-01', '1000'),
        point('2026-01-02', '1200'), // new peak
        point('2026-01-03', '900'), // -25% from peak
        point('2026-01-04', '1100'), // recovers, still below peak
      ],
    });

    const result = computeAnalytics(perf, []);

    expect(result.maxDrawdown!).toBeCloseTo(-0.25, 6);
  });

  it('picks the best and worst adjusted daily returns', () => {
    const perf = performance({
      from: '2026-01-01',
      to: '2026-01-03',
      points: [point('2026-01-01', '1000'), point('2026-01-02', '1200'), point('2026-01-03', '900')],
    });

    const result = computeAnalytics(perf, []);

    expect(result.bestDay!.date).toBe('2026-01-02');
    expect(result.worstDay!.date).toBe('2026-01-03');
  });

  it('annualises the TWR to a 365-day year', () => {
    // Exactly a 365-day window at +10% TWR should annualise back to +10%.
    const perf = performance({
      from: '2026-01-01',
      to: '2027-01-01',
      points: [point('2026-01-01', '1000'), point('2027-01-01', '1100')],
    });

    const result = computeAnalytics(perf, []);

    expect(result.annualisedReturn!).toBeCloseTo(0.1, 4);
  });
});
