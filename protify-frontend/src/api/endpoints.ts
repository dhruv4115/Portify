import { query, request } from './client';
import type {
  Allocation,
  AllocationDimension,
  CreateTransactionRequest,
  CurrencyCode,
  Holding,
  ImportResult,
  Insights,
  Instrument,
  Page,
  Performance,
  PerformanceInterval,
  Portfolio,
  Transaction,
  TransactionType,
} from './types';

/** One function per endpoint in docs/API_CONTRACT.md. No component builds a URL itself. */

export const getPortfolios = () => request<Portfolio[]>('/portfolios');

export const getPortfolio = (id: number) => request<Portfolio>(`/portfolios/${id}`);

export const createPortfolio = (body: { name: string; baseCurrency: CurrencyCode }) =>
  request<Portfolio>('/portfolios', { method: 'POST', body: JSON.stringify(body) });

export const updatePortfolio = (id: number, body: { name?: string; baseCurrency?: CurrencyCode }) =>
  request<Portfolio>(`/portfolios/${id}`, { method: 'PATCH', body: JSON.stringify(body) });

export const getHoldings = (id: number, options: { currency?: CurrencyCode; includeZero?: boolean } = {}) =>
  request<Holding[]>(`/portfolios/${id}/holdings${query({ ...options })}`);

export const getPerformance = (
  id: number,
  options: { from?: string; to?: string; interval?: PerformanceInterval; currency?: CurrencyCode } = {},
) => request<Performance>(`/portfolios/${id}/performance${query({ ...options })}`);

/**
 * `type` and `q` are sent to the server rather than applied to the rows after they arrive.
 * Filtering an already-loaded page can only ever see what has been paged in, so a search for a
 * symbol bought two years ago renders an empty table that looks exactly like a genuine "no
 * results" — the one answer a filter must never get wrong.
 */
export const getTransactions = (
  id: number,
  options: { page?: number; size?: number; type?: TransactionType; q?: string } = {},
) => request<Page<Transaction>>(`/portfolios/${id}/transactions${query({ ...options })}`);

/**
 * Every transaction, not just one page — for CSV export and for the analytics panel's TWR,
 * which needs every `DEPOSIT`/`WITHDRAWAL` in the window, not only the newest ones.
 *
 * `pageSize` is the contract's maximum (API_CONTRACT.md §0.6), not one above it. Asking for 200
 * did not fetch 200 rows — it failed the whole request with a 400, so the analytics panel and
 * CSV export were silently getting nothing at all. The loop below already walks every page, so
 * the ceiling only decides how many round trips it takes, never how much data comes back.
 */
export const getAllTransactions = async (id: number): Promise<Transaction[]> => {
  const pageSize = 100;
  const all: Transaction[] = [];
  let page = 0;
  let last = false;
  while (!last) {
    const result = await getTransactions(id, { page, size: pageSize });
    all.push(...result.content);
    last = result.last;
    page += 1;
  }
  return all;
};

export const createTransaction = (id: number, body: CreateTransactionRequest) =>
  request<Transaction>(`/portfolios/${id}/transactions`, { method: 'POST', body: JSON.stringify(body) });

/**
 * Bulk-adds transactions from a CSV file. There is no holdings equivalent by design — holdings are
 * a projection of the ledger, so importing the transactions is what updates them; see the
 * endpoint's own Javadoc.
 *
 * <p>`dryRun` runs the entire import and rolls it back, which is what makes the preview
 * trustworthy: it is the same code path, so anything it accepts the real import accepts.
 *
 * <p>A file whose rows are wrong comes back as a 200 with `errors[]`, not a rejected promise —
 * the caller renders those, and only a genuinely unusable upload (empty, wrong columns, not your
 * portfolio) throws {@link ApiError}.
 */
export const importTransactions = (id: number, file: File, options: { dryRun?: boolean } = {}) => {
  const body = new FormData();
  body.append('file', file);
  return request<ImportResult>(
    `/portfolios/${id}/transactions/import${query({ dryRun: options.dryRun })}`,
    { method: 'POST', body },
  );
};

export const deleteTransaction = (id: number, txnId: number) =>
  request<void>(`/portfolios/${id}/transactions/${txnId}`, { method: 'DELETE' });

export const searchInstruments = (search: string) =>
  request<Instrument[]>(`/instruments${query({ query: search, limit: 8 })}`);

/**
 * `limit` folds everything past the top N−1 slices into a single `OTHER`, summed on the server.
 * That is not a rendering convenience: the fold is a sum of money, and §0.2 keeps amounts as
 * decimal strings precisely so this app never adds them as doubles. Folding in the browser would
 * be exactly that, and the "Other" figure would drift from the total beside it.
 */
export const getAllocation = (
  id: number,
  options: { by?: AllocationDimension; currency?: CurrencyCode; limit?: number } = {},
) => request<Allocation>(`/portfolios/${id}/allocation${query({ ...options })}`);

/** Dev B's endpoint (day-5-dev-C.md D5-C2) — see {@link Insights} for why this contract is a
 * best guess rather than a frozen one. `InsightsCard` treats any failure here (404 today, since
 * the endpoint doesn't exist yet) as an empty state, not an error toast. */
export const getInsights = (id: number) => request<Insights>(`/portfolios/${id}/insights`);
