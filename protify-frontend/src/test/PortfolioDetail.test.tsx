import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import {
  deleteTransaction,
  getAllocation,
  getAllTransactions,
  getHoldings,
  getInsights,
  getPerformance,
  getPortfolio,
  getTransactions,
  updatePortfolio,
} from '../api/endpoints';
import type { Allocation, Page, Performance, Portfolio, Transaction } from '../api/types';
import { ToastProvider } from '../components/Toast';
import { PortfolioDetail } from '../pages/PortfolioDetail';

vi.mock('../api/endpoints', () => ({
  getPortfolio: vi.fn(),
  getHoldings: vi.fn(),
  getPerformance: vi.fn(),
  getTransactions: vi.fn(),
  getAllTransactions: vi.fn(),
  getInsights: vi.fn(),
  deleteTransaction: vi.fn(),
  updatePortfolio: vi.fn(),
  getAllocation: vi.fn(),
  createTransaction: vi.fn(),
  searchInstruments: vi.fn(),
}));

function portfolio(overrides: Partial<Portfolio> = {}): Portfolio {
  return {
    id: 7,
    name: 'Retirement',
    baseCurrency: 'INR',
    holdingCount: 1,
    marketValue: { amount: '70000.0000', currency: 'INR' },
    costBasis: { amount: '60000.0000', currency: 'INR' },
    cashBalance: { amount: '5000.0000', currency: 'INR' },
    totalValue: { amount: '75000.0000', currency: 'INR' },
    unrealisedPnl: { amount: '10000.0000', currency: 'INR' },
    realisedPnl: { amount: '0.0000', currency: 'INR' },
    unrealisedPnlPct: '16.6700',
    dataQuality: { priceAsOf: '2026-07-19', rateAsOf: '2026-07-19', stale: false },
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function performance(): Performance {
  return {
    portfolioId: 7,
    currency: 'INR',
    from: '2026-01-01',
    to: '2026-07-19',
    interval: 'DAILY',
    points: [],
    summary: {
      startValue: { amount: '0.0000', currency: 'INR' },
      endValue: { amount: '0.0000', currency: 'INR' },
      absoluteChange: { amount: '0.0000', currency: 'INR' },
      percentChange: null,
      netContributions: { amount: '0.0000', currency: 'INR' },
    },
    dataQuality: { priceAsOf: null, rateAsOf: null, stale: false },
  };
}

function allocation(): Allocation {
  return {
    portfolioId: 7,
    by: 'CURRENCY',
    currency: 'INR',
    total: { amount: '70000.0000', currency: 'INR' },
    slices: [],
    dataQuality: { priceAsOf: null, rateAsOf: null, stale: false },
  };
}

function transaction(id: number): Transaction {
  return {
    id,
    type: 'BUY',
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
    price: { amount: '1450.2500', currency: 'INR' },
    fees: { amount: '24.5000', currency: 'INR' },
    totalNative: { amount: '14527.0000', currency: 'INR' },
    totalBase: null,
    fxRateApplied: null,
    executedAt: '2026-06-15T10:15:00Z',
    note: null,
    warnings: [],
  };
}

function page(transactions: Transaction[]): Page<Transaction> {
  return {
    content: transactions,
    page: 0,
    size: 20,
    totalElements: transactions.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/portfolios/7']}>
      <ToastProvider>
        <Routes>
          <Route path="/portfolios/:id" element={<PortfolioDetail />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>,
  );
}

/** Resolves/rejects on demand, so a test can assert what the UI looks like mid-flight. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('PortfolioDetail', () => {
  beforeEach(() => {
    vi.mocked(getPortfolio).mockReset().mockResolvedValue(portfolio());
    vi.mocked(getHoldings).mockReset().mockResolvedValue([]);
    vi.mocked(getPerformance).mockReset().mockResolvedValue(performance());
    vi.mocked(getTransactions).mockReset().mockResolvedValue(page([transaction(1)]));
    vi.mocked(getAllTransactions).mockReset().mockResolvedValue([]);
    vi.mocked(getInsights).mockReset().mockRejectedValue(new Error('not built yet'));
    vi.mocked(getAllocation).mockReset().mockResolvedValue(allocation());
    vi.mocked(deleteTransaction).mockReset();
    vi.mocked(updatePortfolio).mockReset();
  });

  it('switching the base currency PATCHes the portfolio and reloads every data source, including allocation', async () => {
    const user = userEvent.setup();
    vi.mocked(updatePortfolio).mockResolvedValue(portfolio({ baseCurrency: 'USD' }));
    vi.mocked(getPortfolio).mockResolvedValueOnce(portfolio()).mockResolvedValue(portfolio({ baseCurrency: 'USD' }));

    renderDetail();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Retirement' })).toBeInTheDocument());
    // `waitFor`, not a bare assertion. The heading and `AllocationChart` mount in the same commit,
    // but the chart's fetch lives in a passive effect, which React flushes *after* the DOM
    // mutation this `waitFor` observed — so a bare check here races the scheduler and fails
    // intermittently (reproducible on the pre-redesign code too). Retrying still proves exactly
    // one initial fetch: two calls would never satisfy `toHaveBeenCalledTimes(1)`.
    await waitFor(() => expect(getAllocation).toHaveBeenCalledTimes(1));

    await user.selectOptions(screen.getByLabelText('Base currency'), 'USD');

    await waitFor(() => expect(updatePortfolio).toHaveBeenCalledWith(7, { baseCurrency: 'USD' }));
    await waitFor(() => expect(getPortfolio).toHaveBeenCalledTimes(2));
    // Holdings, performance, transactions and allocation are all reloaded, not just the summary.
    expect(getHoldings).toHaveBeenCalledTimes(2);
    expect(getPerformance).toHaveBeenCalledTimes(2);
    expect(getTransactions).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(getAllocation).toHaveBeenCalledTimes(2));

    expect(await screen.findByText('Base currency switched to USD')).toBeInTheDocument();
  });

  it('shows a toast and leaves the base currency unchanged when the switch fails', async () => {
    const user = userEvent.setup();
    vi.mocked(updatePortfolio).mockRejectedValue(
      new ApiError(409, { status: 409, title: 'No FX rate available', detail: 'No INR/USD rate for today.' }),
    );

    renderDetail();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Retirement' })).toBeInTheDocument());

    await user.selectOptions(screen.getByLabelText('Base currency'), 'USD');

    expect(await screen.findByText('No FX rate available')).toBeInTheDocument();
    expect(screen.getByText('No INR/USD rate for today.')).toBeInTheDocument();
    // Only the failed attempt happened — no optimistic rewrite of the header.
    expect(getPortfolio).toHaveBeenCalledTimes(1);
  });

  it('removes a transaction the instant delete is confirmed, before the server responds', async () => {
    const user = userEvent.setup();
    const inFlight = deferred<void>();
    vi.mocked(deleteTransaction).mockReturnValue(inFlight.promise);

    renderDetail();
    await screen.findByTestId('transactions-table');

    await user.click(screen.getByRole('button', { name: /Delete BUY/ }));
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    // Optimistic: the row and the dialog are both gone even though deleteTransaction has not
    // resolved yet (the deferred promise above is still pending). With only one transaction in
    // the fixture, removing it collapses the table into the empty state entirely.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('RELIANCE')).not.toBeInTheDocument();
    expect(screen.queryByTestId('transactions-table')).not.toBeInTheDocument();

    inFlight.resolve();
    await waitFor(() => expect(screen.getByText('Transaction deleted')).toBeInTheDocument());
  });

  it('rolls the transaction back via a reload if the delete request fails', async () => {
    const user = userEvent.setup();
    vi.mocked(deleteTransaction).mockRejectedValue(new ApiError(404, { status: 404, title: 'Not found' }));

    renderDetail();
    await screen.findByTestId('transactions-table');

    await user.click(screen.getByRole('button', { name: /Delete BUY/ }));
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.getByText('Not found')).toBeInTheDocument());
    // The reload the failure triggers still has the transaction (the server never deleted it).
    await waitFor(() =>
      expect(within(screen.getByTestId('transactions-table')).getByText('RELIANCE')).toBeInTheDocument(),
    );
  });
});
