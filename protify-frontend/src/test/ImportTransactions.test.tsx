import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { importTransactions } from '../api/endpoints';
import type { ImportResult } from '../api/types';
import { ImportTransactions } from '../components/ImportTransactions';
import { ToastProvider } from '../components/Toast';

vi.mock('../api/endpoints', () => ({
  importTransactions: vi.fn(),
}));

const CSV = 'Date,Type,Symbol,Quantity,Price,Currency,Fees,Note\n2026-06-15,DEPOSIT,,,5000,USD,,';

function csvFile(): File {
  return new File([CSV], 'ledger.csv', { type: 'text/csv' });
}

function result(overrides: Partial<ImportResult> = {}): ImportResult {
  return {
    dryRun: false,
    totalRows: 2,
    imported: 2,
    failed: 0,
    errors: [],
    warnings: [],
    ...overrides,
  };
}

function renderCard(onImported = vi.fn()) {
  render(
    <ToastProvider>
      <ImportTransactions portfolioId={7} onImported={onImported} />
    </ToastProvider>,
  );
  return { onImported };
}

/** The file input is visually hidden and driven by the button, so tests address it by its label. */
function fileInput(): HTMLInputElement {
  return screen.getByLabelText('Choose CSV file') as HTMLInputElement;
}

describe('ImportTransactions', () => {
  beforeEach(() => {
    vi.mocked(importTransactions).mockReset();
  });

  it('previews a chosen file with a dry run before writing anything', async () => {
    vi.mocked(importTransactions).mockResolvedValue(result({ dryRun: true }));
    const user = userEvent.setup();
    renderCard();

    await user.upload(fileInput(), csvFile());

    expect(importTransactions).toHaveBeenCalledWith(7, expect.any(File), { dryRun: true });
    expect(await screen.findByTestId('import-preview')).toHaveTextContent('ledger.csv');
    expect(screen.getByText('2 transactions are ready to import.')).toBeInTheDocument();
    // Nothing is written until the second click — this is a bulk write to a ledger.
    expect(importTransactions).toHaveBeenCalledTimes(1);
  });

  it('imports for real on confirm and tells the page to refetch', async () => {
    vi.mocked(importTransactions)
      .mockResolvedValueOnce(result({ dryRun: true }))
      .mockResolvedValueOnce(result());
    const user = userEvent.setup();
    const { onImported } = renderCard();

    await user.upload(fileInput(), csvFile());
    await user.click(await screen.findByRole('button', { name: 'Import 2 transactions' }));

    // No `dryRun` this time — the second call is the one that writes.
    expect(importTransactions).toHaveBeenNthCalledWith(2, 7, expect.any(File));
    expect(await screen.findByTestId('toast')).toHaveTextContent('Imported 2 transactions.');
    // Holdings are a projection of the ledger, so an import moves every panel on the page.
    await waitFor(() => expect(onImported).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('import-preview')).not.toBeInTheDocument();
  });

  it('lists every rejected row against its file line and offers no import button', async () => {
    vi.mocked(importTransactions).mockResolvedValue(
      result({
        dryRun: true,
        imported: 0,
        failed: 2,
        errors: [
          { line: 3, message: 'type must be one of BUY, SELL, ... but was "PURCHASE".' },
          { line: 4, message: 'date must be YYYY-MM-DD or an ISO-8601 timestamp.' },
        ],
      }),
    );
    const user = userEvent.setup();
    renderCard();

    await user.upload(fileInput(), csvFile());

    expect(await screen.findByRole('alert')).toHaveTextContent('2 rows need fixing first');
    expect(screen.getByText('Line 3')).toBeInTheDocument();
    expect(screen.getByText(/but was "PURCHASE"/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Import/ })).not.toBeInTheDocument();
  });

  it('shows a warning from an otherwise importable file without blocking it', async () => {
    vi.mocked(importTransactions).mockResolvedValue(
      result({ dryRun: true, warnings: ["These transactions leave the portfolio's cash balance negative."] }),
    );
    const user = userEvent.setup();
    renderCard();

    await user.upload(fileInput(), csvFile());

    expect(await screen.findByTestId('import-warning')).toHaveTextContent('cash balance negative');
    expect(screen.getByRole('button', { name: 'Import 2 transactions' })).toBeEnabled();
  });

  it('toasts and clears the selection when the file itself is rejected', async () => {
    vi.mocked(importTransactions).mockRejectedValue(
      new ApiError(400, {
        status: 400,
        title: 'Validation failed',
        detail: 'The header row is missing required column(s): date.',
      }),
    );
    const user = userEvent.setup();
    renderCard();

    await user.upload(fileInput(), csvFile());

    expect(await screen.findByTestId('toast')).toHaveTextContent('missing required column');
    expect(screen.queryByTestId('import-preview')).not.toBeInTheDocument();
    // Cleared, so re-picking the same file after fixing it fires a fresh change event.
    expect(fileInput().value).toBe('');
  });

  it('clears a chosen file on request', async () => {
    vi.mocked(importTransactions).mockResolvedValue(result({ dryRun: true }));
    const user = userEvent.setup();
    renderCard();

    await user.upload(fileInput(), csvFile());
    await user.click(await screen.findByRole('button', { name: 'Clear' }));

    expect(screen.queryByTestId('import-preview')).not.toBeInTheDocument();
  });
});
