import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { Holding, Transaction } from '../api/types';
import { getAllTransactions } from '../api/endpoints';
import { ExportButtons } from '../components/ExportButtons';
import { ToastProvider } from '../components/Toast';
import * as csv from '../lib/csv';

vi.mock('../api/endpoints', () => ({
  getAllTransactions: vi.fn(),
}));

/** The card lives under the app-wide `ToastProvider`, which is where a failed export is reported. */
function renderCard(holdings: Holding[]) {
  return render(
    <ToastProvider>
      <ExportButtons portfolioId={7} holdings={holdings} />
    </ToastProvider>,
  );
}

function holding(): Holding {
  return {
    instrument: {
      id: 1,
      symbol: 'RELIANCE',
      name: 'Reliance Industries',
      assetType: 'STOCK',
      currency: 'INR',
      exchange: 'NSE',
      sector: 'Energy',
    },
    quantity: '10.000000',
    avgCost: { amount: '1450.2500', currency: 'INR' },
    lastPrice: { amount: '1500.0000', currency: 'INR' },
    marketValue: { amount: '15000.0000', currency: 'INR' },
    costBasis: { amount: '14502.5000', currency: 'INR' },
    unrealisedPnl: { amount: '497.5000', currency: 'INR' },
    unrealisedPnlPct: '3.4300',
    realisedPnl: { amount: '0.0000', currency: 'INR' },
    weightPct: '100.0000',
    fxRate: '1.00000000',
    dataQuality: { priceAsOf: '2026-07-19', rateAsOf: null, stale: false },
  };
}

function transaction(): Transaction {
  return {
    id: 1,
    type: 'BUY',
    instrument: holding().instrument,
    quantity: '10.000000',
    price: { amount: '1450.2500', currency: 'INR' },
    fees: { amount: '24.5000', currency: 'INR' },
    totalNative: { amount: '14527.0000', currency: 'INR' },
    totalBase: { amount: '14527.0000', currency: 'INR' },
    fxRateApplied: '1.00000000',
    executedAt: '2026-06-15T10:15:00Z',
    note: null,
    warnings: [],
  };
}

describe('ExportButtons', () => {
  beforeEach(() => {
    vi.mocked(getAllTransactions).mockReset();
  });

  it('exports holdings as a CSV built from already-loaded data, no fetch needed', async () => {
    const downloadSpy = vi.spyOn(csv, 'downloadCsv').mockImplementation(() => {});
    const user = userEvent.setup();

    renderCard([holding()]);
    await user.click(screen.getByRole('button', { name: 'Export holdings (CSV)' }));

    expect(downloadSpy).toHaveBeenCalledTimes(1);
    expect(downloadSpy.mock.calls[0][0]).toBe('portfolio-7-holdings.csv');
    expect(downloadSpy.mock.calls[0][1]).toContain('RELIANCE');
    expect(getAllTransactions).not.toHaveBeenCalled();

    downloadSpy.mockRestore();
  });

  it('exports every transaction, fetched fresh, not just the visible page', async () => {
    vi.mocked(getAllTransactions).mockResolvedValue([transaction()]);
    const downloadSpy = vi.spyOn(csv, 'downloadCsv').mockImplementation(() => {});
    const user = userEvent.setup();

    renderCard([]);
    await user.click(screen.getByRole('button', { name: 'Export transactions (CSV)' }));

    await waitFor(() => expect(downloadSpy).toHaveBeenCalledTimes(1));
    expect(downloadSpy.mock.calls[0][0]).toBe('portfolio-7-transactions.csv');
    expect(downloadSpy.mock.calls[0][1]).toContain('RELIANCE');
    expect(getAllTransactions).toHaveBeenCalledWith(7);

    downloadSpy.mockRestore();
  });

  it('disables the holdings export when there is nothing to export', () => {
    renderCard([]);

    expect(screen.getByRole('button', { name: 'Export holdings (CSV)' })).toBeDisabled();
  });

  /**
   * The regression this file did not previously cover. A rejected fetch handed to `void` prints to
   * the console and stops there, so a failing transactions export was indistinguishable from a
   * dead button — which is exactly how it presented when a stale build asked the server for a page
   * size it refuses. Failing loudly is the fix; the button must also come back out of its busy
   * state, or a retry is impossible.
   */
  it('toasts and re-enables the button when the fetch fails, instead of failing silently', async () => {
    vi.mocked(getAllTransactions).mockRejectedValue(
      new ApiError(400, { status: 400, title: 'Validation failed', detail: 'size must be at most 100' }),
    );
    const downloadSpy = vi.spyOn(csv, 'downloadCsv').mockImplementation(() => {});
    const user = userEvent.setup();

    renderCard([]);
    await user.click(screen.getByRole('button', { name: 'Export transactions (CSV)' }));

    expect(await screen.findByTestId('toast')).toHaveTextContent('size must be at most 100');
    expect(downloadSpy).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Export transactions (CSV)' })).toBeEnabled(),
    );

    downloadSpy.mockRestore();
  });
});
