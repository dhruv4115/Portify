import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { createTransaction, searchInstruments } from '../api/endpoints';
import { AddTransactionForm } from '../components/AddTransactionForm';
import type { Transaction } from '../api/types';

vi.mock('../api/endpoints', () => ({
  createTransaction: vi.fn(),
  searchInstruments: vi.fn(),
}));

const created: Transaction = {
  id: 91,
  type: 'BUY',
  instrument: {
    id: 1,
    symbol: 'AAPL',
    name: 'Apple Inc.',
    assetType: 'STOCK',
    currency: 'USD',
    exchange: 'NASDAQ',
    sector: 'Technology',
  },
  quantity: '3.000000',
  price: { amount: '228.1000', currency: 'USD' },
  fees: { amount: '0.0000', currency: 'USD' },
  totalNative: { amount: '684.3000', currency: 'USD' },
  totalBase: { amount: '59534.1000', currency: 'INR' },
  fxRateApplied: '87.00000000',
  executedAt: '2026-07-30T09:00:00Z',
  note: null,
  warnings: [],
};

/** The `.field` wrapper an input sits in, so an assertion can be scoped to one field. */
function fieldFor(label: string): HTMLElement {
  return screen.getByLabelText(label).closest<HTMLElement>('.field')!;
}

function renderForm() {
  const onAdded = vi.fn();
  render(<AddTransactionForm portfolioId={7} baseCurrency="INR" onAdded={onAdded} />);
  return { onAdded, user: userEvent.setup() };
}

async function fillSellOfFifty(user: ReturnType<typeof userEvent.setup>) {
  await user.selectOptions(screen.getByLabelText('Type'), 'SELL');
  await user.type(screen.getByLabelText('Symbol'), 'AAPL');
  await user.type(screen.getByLabelText('Quantity'), '50');
  await user.type(screen.getByLabelText('Price'), '228.10');
  await user.click(screen.getByRole('button', { name: 'Add transaction' }));
}

describe('AddTransactionForm', () => {
  beforeEach(() => {
    vi.mocked(searchInstruments).mockResolvedValue([]);
    vi.mocked(createTransaction).mockReset();
  });

  it('puts a 422 from a domain rule against the field errors[] names', async () => {
    vi.mocked(createTransaction).mockRejectedValue(
      new ApiError(422, {
        type: 'https://portfolio.local/errors/insufficient-quantity',
        title: 'Insufficient quantity',
        status: 422,
        detail: 'Cannot sell 50 of AAPL; holding is 12.',
        errors: [{ field: 'quantity', message: 'must not exceed holding of 12.000000' }],
      }),
    );
    const { user } = renderForm();

    await fillSellOfFifty(user);

    const quantityField = fieldFor('Quantity');
    // The sentence the server wrote for a person, shown against the input it blames
    expect(await within(quantityField).findByRole('alert')).toHaveTextContent(
      'Cannot sell 50 of AAPL; holding is 12.',
    );
    expect(screen.getByLabelText('Quantity')).toHaveAttribute('aria-invalid', 'true');

    // and nowhere else
    expect(within(fieldFor('Price')).queryByRole('alert')).toBeNull();
    expect(screen.queryByTestId('form-error')).not.toBeInTheDocument();
  });

  it('puts a currency mismatch against the currency field', async () => {
    vi.mocked(createTransaction).mockRejectedValue(
      new ApiError(422, {
        type: 'https://portfolio.local/errors/currency-mismatch',
        title: 'Currency mismatch',
        status: 422,
        detail: 'AAPL trades in USD; transaction supplied INR.',
        errors: [{ field: 'currency', message: 'must be USD' }],
      }),
    );
    const { user } = renderForm();

    await fillSellOfFifty(user);

    const currencyField = fieldFor('Currency');
    expect(await within(currencyField).findByRole('alert')).toHaveTextContent(
      'AAPL trades in USD; transaction supplied INR.',
    );
  });

  it('spreads a multi-field 400 across each field named in errors[]', async () => {
    vi.mocked(createTransaction).mockRejectedValue(
      new ApiError(400, {
        type: 'https://portfolio.local/errors/validation-failed',
        title: 'Validation failed',
        status: 400,
        detail: 'The request failed validation.',
        errors: [
          { field: 'quantity', message: 'must be greater than or equal to 0.000001' },
          { field: 'symbol', message: 'is required for a SELL transaction' },
        ],
      }),
    );
    const { user } = renderForm();

    await fillSellOfFifty(user);

    // With more than one field at fault there is no single sentence to spread, so each field
    // gets its own message rather than all of them getting `detail`.
    expect(
      await within(fieldFor('Quantity')).findByRole('alert'),
    ).toHaveTextContent('must be greater than or equal to 0.000001');
    expect(
      within(fieldFor('Symbol')).getByRole('alert'),
    ).toHaveTextContent('is required for a SELL transaction');
  });

  it('falls back to a form-level message when the problem names no field', async () => {
    vi.mocked(createTransaction).mockRejectedValue(
      new ApiError(404, {
        type: 'https://portfolio.local/errors/instrument-not-found',
        title: 'Instrument not found',
        status: 404,
        detail: "No instrument with symbol 'TSLAA'.",
      }),
    );
    const { user } = renderForm();

    await fillSellOfFifty(user);

    expect(await screen.findByTestId('form-error')).toHaveTextContent("No instrument with symbol 'TSLAA'.");
  });

  it('surfaces a 201 warning without treating it as a failure', async () => {
    vi.mocked(createTransaction).mockResolvedValue({
      ...created,
      warnings: ['This transaction leaves the portfolio’s cash balance negative.'],
    });
    const { user, onAdded } = renderForm();

    await fillSellOfFifty(user);

    expect(await screen.findByTestId('txn-warning')).toHaveTextContent('cash balance negative');
    expect(screen.queryByTestId('form-error')).not.toBeInTheDocument();
    expect(onAdded).toHaveBeenCalledOnce();
  });

  it('fills the currency in from the instrument picked in the type-ahead', async () => {
    vi.mocked(searchInstruments).mockResolvedValue([
      {
        id: 1,
        symbol: 'AAPL',
        name: 'Apple Inc.',
        assetType: 'STOCK',
        currency: 'USD',
        exchange: 'NASDAQ',
        sector: 'Technology',
      },
    ]);
    const { user } = renderForm();

    // the portfolio is in INR, and a USD instrument must not be sent as INR
    expect(screen.getByLabelText('Currency')).toHaveValue('INR');

    await user.type(screen.getByLabelText('Symbol'), 'AAP');
    await user.click(await screen.findByRole('button', { name: /AAPL/ }));

    await waitFor(() => expect(screen.getByLabelText('Currency')).toHaveValue('USD'));
    expect(screen.getByLabelText('Symbol')).toHaveValue('AAPL');
  });

  it('hides symbol and quantity for a cash transaction', async () => {
    const { user } = renderForm();

    await user.selectOptions(screen.getByLabelText('Type'), 'DEPOSIT');

    expect(screen.queryByLabelText('Symbol')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument();
    // for a cash transaction the price field carries the whole amount
    expect(screen.getByLabelText('Amount')).toBeInTheDocument();
  });
});
