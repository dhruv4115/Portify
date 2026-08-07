import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PerformanceChart } from '../components/PerformanceChart';
import type { Money, Performance, PerformancePoint } from '../api/types';

const inr = (amount: string): Money => ({ amount, currency: 'INR' });

function point(dayOffset: number, total: string): PerformancePoint {
  const date = new Date(Date.UTC(2026, 0, 1 + dayOffset)).toISOString().slice(0, 10);
  return {
    date,
    marketValue: inr(total),
    costBasis: inr('900.0000'),
    cashBalance: inr('0.0000'),
    totalValue: inr(total),
    unrealisedPnl: inr('100.0000'),
    filled: false,
  };
}

function performance(points: PerformancePoint[], stale = false): Performance {
  const first = points[0]?.totalValue ?? inr('0.0000');
  const last = points[points.length - 1]?.totalValue ?? inr('0.0000');
  return {
    portfolioId: 7,
    currency: 'INR',
    from: '2026-01-01',
    to: '2026-07-19',
    interval: 'DAILY',
    points,
    summary: {
      startValue: first,
      endValue: last,
      absoluteChange: inr('199.0000'),
      percentChange: '19.9000',
      netContributions: inr('0.0000'),
    },
    dataQuality: { priceAsOf: '2026-07-19', rateAsOf: '2026-07-19', stale },
  };
}

describe('PerformanceChart', () => {
  it('shows an empty state rather than a blank axis when there are no points', () => {
    render(<PerformanceChart performance={performance([])} />);

    expect(screen.getByTestId('chart-empty')).toHaveTextContent('No performance history yet');
    expect(screen.queryByTestId('performance-chart')).not.toBeInTheDocument();
  });

  it('renders a single point and says why the line is missing', () => {
    render(<PerformanceChart performance={performance([point(0, '1000.0000')])} />);

    const chart = screen.getByTestId('performance-chart');
    expect(chart).toBeInTheDocument();
    expect(chart).toHaveAttribute('data-points', '1');
    // A line through one point draws nothing, so the point itself has to be explained
    expect(screen.getByTestId('chart-single-point')).toBeInTheDocument();
  });

  it('renders 200 points without falling over', () => {
    const points = Array.from({ length: 200 }, (_, day) => point(day, `${1000 + day}.0000`));

    render(<PerformanceChart performance={performance(points)} />);

    expect(screen.getByTestId('performance-chart')).toHaveAttribute('data-points', '200');
    expect(screen.queryByTestId('chart-single-point')).not.toBeInTheDocument();
    expect(screen.queryByTestId('chart-empty')).not.toBeInTheDocument();
  });

  it('shows the summary change in the base currency, formatted from the server string', () => {
    render(<PerformanceChart performance={performance([point(0, '1000.0000'), point(1, '1199.0000')])} />);

    expect(screen.getByText('₹199.00 (19.90%)')).toBeInTheDocument();
  });

  it('shows a stale badge when the data is old, and still draws the chart', () => {
    render(<PerformanceChart performance={performance([point(0, '1000.0000'), point(1, '1199.0000')], true)} />);

    expect(screen.getByTestId('stale-badge')).toBeInTheDocument();
    expect(screen.getByTestId('performance-chart')).toBeInTheDocument();
  });

  it('shows a skeleton while loading instead of an empty chart', () => {
    render(<PerformanceChart performance={null} loading />);

    expect(screen.getByTestId('chart-skeleton')).toBeInTheDocument();
    expect(screen.queryByTestId('chart-empty')).not.toBeInTheDocument();
  });
});
