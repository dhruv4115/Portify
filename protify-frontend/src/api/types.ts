/**
 * Mirrors of the backend DTOs (docs/API_CONTRACT.md). Every amount is a `string`, never a
 * `number` — §0.2: JavaScript parses a bare JSON number as an IEEE-754 double, which cannot
 * represent decimal money exactly, so typing these as `number` would reintroduce exactly the bug
 * the string is there to prevent. Nothing in this app does arithmetic on them.
 */

export type CurrencyCode = 'USD' | 'EUR' | 'GBP' | 'INR';

export type TransactionType = 'BUY' | 'SELL' | 'DIVIDEND' | 'DEPOSIT' | 'WITHDRAWAL' | 'FEE';

export type PerformanceInterval = 'DAILY' | 'WEEKLY' | 'MONTHLY';

export type AllocationDimension = 'ASSET_TYPE' | 'SECTOR' | 'CURRENCY' | 'INSTRUMENT';

export interface Money {
  amount: string;
  currency: CurrencyCode;
}

export interface DataQuality {
  priceAsOf: string | null;
  rateAsOf: string | null;
  stale: boolean;
}

export interface Instrument {
  id: number;
  symbol: string;
  name: string;
  assetType: string;
  currency: CurrencyCode;
  exchange: string | null;
  sector: string | null;
}

export interface Portfolio {
  id: number;
  name: string;
  baseCurrency: CurrencyCode;
  holdingCount: number;
  marketValue: Money;
  costBasis: Money;
  cashBalance: Money;
  totalValue: Money;
  unrealisedPnl: Money;
  realisedPnl: Money;
  unrealisedPnlPct: string | null;
  dataQuality: DataQuality;
  createdAt: string;
  updatedAt: string;
}

export interface Holding {
  instrument: Instrument;
  quantity: string;
  /** Native currency (§0.3). */
  avgCost: Money;
  /** Native currency, null when the position could not be priced. */
  lastPrice: Money | null;
  /** Base currency, null when the position could not be priced. */
  marketValue: Money | null;
  costBasis: Money;
  unrealisedPnl: Money | null;
  unrealisedPnlPct: string | null;
  realisedPnl: Money;
  weightPct: string;
  fxRate: string | null;
  dataQuality: DataQuality;
}

export interface Transaction {
  id: number;
  type: TransactionType;
  instrument: Instrument | null;
  quantity: string;
  price: Money;
  fees: Money;
  totalNative: Money;
  totalBase: Money | null;
  fxRateApplied: string | null;
  executedAt: string;
  note: string | null;
  warnings: string[];
}

export interface PerformancePoint {
  date: string;
  marketValue: Money;
  costBasis: Money;
  cashBalance: Money;
  totalValue: Money;
  unrealisedPnl: Money;
  /** True when this day's price or rate was carried forward — a weekend or a holiday. */
  filled: boolean;
}

export interface PerformanceSummary {
  startValue: Money;
  endValue: Money;
  absoluteChange: Money;
  percentChange: string | null;
  netContributions: Money;
}

export interface Performance {
  portfolioId: number;
  currency: CurrencyCode;
  from: string;
  to: string;
  interval: PerformanceInterval;
  points: PerformancePoint[];
  summary: PerformanceSummary;
  dataQuality: DataQuality;
}

export interface AllocationSlice {
  key: string;
  label: string;
  /** Base (or requested override) currency — the same currency as {@link Allocation.total}. */
  value: Money;
  weightPct: string;
  instrumentCount: number;
}

/** API_CONTRACT.md §13. Cash is excluded from `total` and the slices; it is reported separately. */
export interface Allocation {
  portfolioId: number;
  by: AllocationDimension;
  currency: CurrencyCode;
  total: Money;
  slices: AllocationSlice[];
  dataQuality: DataQuality;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CreateTransactionRequest {
  type: TransactionType;
  symbol?: string | null;
  quantity?: string | null;
  price: string;
  currency: CurrencyCode;
  fees?: string | null;
  executedAt: string;
  note?: string | null;
}

/** One rejected row of a CSV import. `line` is the 1-based physical line in the uploaded file,
 * header included — the line number a spreadsheet shows, so it can be acted on directly. */
export interface ImportRowError {
  line: number;
  message: string;
}

/**
 * The outcome of a CSV import (API_CONTRACT.md §8.1).
 *
 * <p>A rejected file is a 200 carrying this, not an error status: the whole point is to say
 * *which* rows are wrong and why, which a `ProblemDetail` cannot express. `imported > 0` is the
 * only thing that means anything was written — the import is all-or-nothing, so a file with any
 * error at all leaves the ledger untouched.
 */
export interface ImportResult {
  dryRun: boolean;
  totalRows: number;
  imported: number;
  failed: number;
  errors: ImportRowError[];
  warnings: string[];
}

/** RFC 9457, §0.7. `errors[]` appears only for field-level failures. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  timestamp?: string;
  errors?: { field: string; message: string }[];
}

/**
 * day-5-dev-C.md D5-C2. Dev B's endpoint (`portfolio-insights`, ARCHITECTURE.md §1 — a
 * feature-flagged, 2-second-timeout call to a companion Python FastAPI+LLM service) does not
 * exist in the backend yet, so this shape is this session's best-effort guess at its contract,
 * not something copied from a frozen spec. `engine` is rendered honestly rather than assumed:
 * `RULE_BASED` means the LLM path was off, timed out, or failed, and the summary is a
 * deterministic fallback — not a broken feature, a deliberate one.
 */
export type InsightsEngine = 'AI_GENERATED' | 'RULE_BASED';

/** One engine's reading of the portfolio. Every variant in a response was generated from the
 * same snapshot, so they are comparable — see {@link Insights.variants}. */
export interface InsightsVariant {
  engine: InsightsEngine;
  summary: string;
}

export interface Insights {
  engine: InsightsEngine;
  summary: string;
  generatedAt: string;
  /**
   * Every reading the server produced, most-preferred first — `[AI_GENERATED, RULE_BASED]` when
   * the model answered, `[RULE_BASED]` alone when it was off, timed out or failed. The top-level
   * `engine`/`summary` mirror `variants[0]`, so nothing here is required reading.
   *
   * Optional because a server predating this field omits it entirely; `InsightsCard` falls back
   * to synthesising a single variant from `engine`/`summary` rather than rendering nothing.
   */
  variants?: InsightsVariant[];
}

