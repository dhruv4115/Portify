import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Performance, Transaction } from '../api/types';
import { getAllTransactions } from '../api/endpoints';
import { AnalyticsPanel } from '../components/AnalyticsPanel';

vi.mock('../api/endpoints', () => ({
  getAllTransactions: vi.fn(),
}));

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

function performance(): Performance {
  return {
    portfolioId: 7,
    currency: 'USD',
    from: '2026-01-01',
    to: '2026-01-03',
    interval: 'DAILY',
    points: [point('2026-01-01', '1000'), point('2026-01-02', '1100'), point('2026-01-03', '1100')],
    summary: {
      startValue: { amount: '1000', currency: 'USD' },
      endValue: { amount: '1100', currency: 'USD' },
      absoluteChange: { amount: '100', currency: 'USD' },
      percentChange: '10.0000',
      netContributions: { amount: '0', currency: 'USD' },
    },
    dataQuality: { priceAsOf: '2026-01-03', rateAsOf: '2026-01-03', stale: false },
  };
}

describe('AnalyticsPanel', () => {
  beforeEach(() => {
    vi.mocked(getAllTransactions).mockReset();
  });

  it('shows a skeleton while loading', () => {
    vi.mocked(getAllTransactions).mockReturnValue(new Promise<Transaction[]>(() => {}));

    render(<AnalyticsPanel portfolioId={7} performance={null} loading version={0} />);

    expect(screen.getByTestId('analytics-skeleton')).toBeInTheDocument();
  });

  it('shows an empty state when there is not enough performance history', async () => {
    vi.mocked(getAllTransactions).mockResolvedValue([]);

    render(<AnalyticsPanel portfolioId={7} performance={null} loading={false} version={0} />);

    await waitFor(() => expect(screen.getByTestId('analytics-empty')).toBeInTheDocument());
  });

  it('renders TWR, annualised return, drawdown and best/worst day once loaded', async () => {
    vi.mocked(getAllTransactions).mockResolvedValue([]);

    render(<AnalyticsPanel portfolioId={7} performance={performance()} loading={false} version={0} />);

    await waitFor(() => expect(screen.getByTestId('analytics-panel')).toBeInTheDocument());
    expect(screen.getByText(/TWR \(time-weighted return\)/)).toBeInTheDocument();
    expect(screen.getByText('Annualised return')).toBeInTheDocument();
    expect(screen.getByText('Max drawdown')).toBeInTheDocument();
    expect(screen.getByText('Best day')).toBeInTheDocument();
    expect(screen.getByText('Worst day')).toBeInTheDocument();
  });

  it('refetches transactions when the version bumps', async () => {
    vi.mocked(getAllTransactions).mockResolvedValue([]);

    const { rerender } = render(
      <AnalyticsPanel portfolioId={7} performance={performance()} loading={false} version={0} />,
    );
    await waitFor(() => expect(getAllTransactions).toHaveBeenCalledTimes(1));

    rerender(<AnalyticsPanel portfolioId={7} performance={performance()} loading={false} version={1} />);
    await waitFor(() => expect(getAllTransactions).toHaveBeenCalledTimes(2));
  });
});
