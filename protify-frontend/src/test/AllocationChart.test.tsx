import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Allocation } from '../api/types';
import { getAllocation } from '../api/endpoints';
import { AllocationChart } from '../components/AllocationChart';

vi.mock('../api/endpoints', () => ({
  getAllocation: vi.fn(),
}));

function allocation(overrides: Partial<Allocation> = {}): Allocation {
  return {
    portfolioId: 7,
    by: 'CURRENCY',
    currency: 'INR',
    total: { amount: '100000.0000', currency: 'INR' },
    slices: [
      {
        key: 'INR',
        label: 'Indian Rupee',
        value: { amount: '70000.0000', currency: 'INR' },
        weightPct: '70.0000',
        instrumentCount: 2,
      },
      {
        key: 'USD',
        label: 'US Dollar',
        value: { amount: '30000.0000', currency: 'INR' },
        weightPct: '30.0000',
        instrumentCount: 1,
      },
    ],
    dataQuality: { priceAsOf: '2026-07-19', rateAsOf: '2026-07-19', stale: false },
    ...overrides,
  };
}

describe('AllocationChart', () => {
  beforeEach(() => {
    vi.mocked(getAllocation).mockReset();
  });

  it('defaults to grouping by currency — the view that shows multi-currency at all', async () => {
    vi.mocked(getAllocation).mockResolvedValue(allocation());

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);

    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());
    expect(getAllocation).toHaveBeenCalledWith(7, { by: 'CURRENCY', currency: 'INR', limit: 8 });
    expect(screen.getByRole('combobox', { name: 'Group allocation by' })).toHaveValue('CURRENCY');
  });

  it('re-fetches under the newly chosen dimension when the selector changes', async () => {
    vi.mocked(getAllocation).mockResolvedValue(allocation());
    const user = userEvent.setup();

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);
    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());

    await user.selectOptions(screen.getByRole('combobox', { name: 'Group allocation by' }), 'ASSET_TYPE');

    await waitFor(() =>
      expect(getAllocation).toHaveBeenLastCalledWith(7, { by: 'ASSET_TYPE', currency: 'INR', limit: 8 }),
    );
  });

  it('shows an empty state rather than an empty pie when there are no priced holdings', async () => {
    vi.mocked(getAllocation).mockResolvedValue(allocation({ slices: [], total: { amount: '0.0000', currency: 'INR' } }));

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);

    await waitFor(() => expect(screen.getByTestId('allocation-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('allocation-chart')).not.toBeInTheDocument();
  });

  it('shows a stale badge without hiding the chart', async () => {
    vi.mocked(getAllocation).mockResolvedValue(
      allocation({ dataQuality: { priceAsOf: '2026-07-01', rateAsOf: '2026-07-01', stale: true } }),
    );

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);

    await waitFor(() => expect(screen.getByTestId('stale-badge')).toBeInTheDocument());
    expect(screen.getByTestId('allocation-chart')).toBeInTheDocument();
  });

  it('re-fetches when the parent bumps the version after a write, even if the selector did not change', async () => {
    vi.mocked(getAllocation).mockResolvedValue(allocation());

    const { rerender } = render(<AllocationChart portfolioId={7} currency="INR" version={0} />);
    await waitFor(() => expect(getAllocation).toHaveBeenCalledTimes(1));

    rerender(<AllocationChart portfolioId={7} currency="INR" version={1} />);

    await waitFor(() => expect(getAllocation).toHaveBeenCalledTimes(2));
  });

  // ---- the "Other" fold ------------------------------------------------------------------

  function slice(key: string, amount: string, weightPct: string, instrumentCount = 1) {
    return {
      key,
      label: key === 'OTHER' ? 'Other' : `${key} Ltd`,
      value: { amount, currency: 'INR' as const },
      weightPct,
      instrumentCount,
    };
  }

  /** Seven named slices plus the server's fold — what `limit: 8` comes back as. */
  const FOLDED = allocation({
    slices: [
      slice('A', '30000.0000', '30.0000'),
      slice('B', '20000.0000', '20.0000'),
      slice('C', '15000.0000', '15.0000'),
      slice('D', '10000.0000', '10.0000'),
      slice('E', '8000.0000', '8.0000'),
      slice('F', '7000.0000', '7.0000'),
      slice('G', '5000.0000', '5.0000'),
      slice('OTHER', '5000.0000', '5.0000', 12),
    ],
  });

  const UNFOLDED = allocation({
    slices: [
      ...FOLDED.slices.slice(0, 7),
      slice('H', '3000.0000', '3.0000'),
      slice('I', '2000.0000', '2.0000'),
    ],
  });

  /** Answers by what was asked for rather than by call order: `limit` gets the folded view,
   * its absence gets the full one — exactly the distinction the component relies on. */
  function givenFoldedWithTail() {
    vi.mocked(getAllocation).mockImplementation((_id, options) =>
      Promise.resolve(options?.limit ? FOLDED : UNFOLDED),
    );
  }

  it('asks the server to cap the slices at the palette size', async () => {
    vi.mocked(getAllocation).mockResolvedValue(FOLDED);

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);

    // Eight is the palette's size. Past it the old code reused colours, so two different wedges
    // were drawn identically — the legend is the required relief channel and could not fix that.
    await waitFor(() => expect(getAllocation).toHaveBeenCalledWith(7, expect.objectContaining({ limit: 8 })));
  });

  it('names how many were grouped, and lists them on demand', async () => {
    givenFoldedWithTail();
    const user = userEvent.setup();

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);
    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());

    const other = screen.getByRole('button', { name: /Other \(12\)/ });
    expect(other).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('allocation-other-rows')).not.toBeInTheDocument();

    await user.click(other);

    // The tail is fetched unfolded, and only the rows the folded response did not name appear.
    await waitFor(() => expect(screen.getByTestId('allocation-other-rows')).toBeInTheDocument());
    expect(getAllocation).toHaveBeenLastCalledWith(7, { by: 'CURRENCY', currency: 'INR' });
    expect(screen.getByText('H Ltd')).toBeInTheDocument();
    expect(screen.getByText('I Ltd')).toBeInTheDocument();
    expect(screen.queryByText('A Ltd')).toBeInTheDocument();
  });

  it('fetches the tail once, however often it is toggled', async () => {
    givenFoldedWithTail();
    const user = userEvent.setup();

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);
    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());

    const other = screen.getByRole('button', { name: /Other \(12\)/ });
    await user.click(other);
    await waitFor(() => expect(screen.getByTestId('allocation-other-rows')).toBeInTheDocument());
    await user.click(other);
    await waitFor(() => expect(screen.queryByTestId('allocation-other-rows')).not.toBeInTheDocument());
    await user.click(other);
    await waitFor(() => expect(screen.getByTestId('allocation-other-rows')).toBeInTheDocument());

    // One fetch for the folded view, one for the tail. Nothing more.
    expect(getAllocation).toHaveBeenCalledTimes(2);
  });

  /** The tail belongs to the dimension it was fetched for; keeping it open across a switch would
   * show sectors under an instrument breakdown. */
  it('collapses and discards the tail when the dimension changes', async () => {
    vi.mocked(getAllocation).mockResolvedValue(FOLDED);
    const user = userEvent.setup();

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);
    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Other \(12\)/ }));
    await waitFor(() => expect(screen.getByTestId('allocation-other-rows')).toBeInTheDocument());

    await user.selectOptions(screen.getByRole('combobox', { name: 'Group allocation by' }), 'SECTOR');

    await waitFor(() => expect(screen.queryByTestId('allocation-other-rows')).not.toBeInTheDocument());
  });

  it('shows no grouping affordance when nothing was folded', async () => {
    vi.mocked(getAllocation).mockResolvedValue(allocation());

    render(<AllocationChart portfolioId={7} currency="INR" version={0} />);

    await waitFor(() => expect(screen.getByTestId('allocation-chart')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Other/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('allocation-legend').children).toHaveLength(2);
  });
});
