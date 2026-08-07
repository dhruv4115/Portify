import type { CurrencyCode, Money } from '../api/types';

/**
 * Exact arithmetic on the server's decimal strings, for the cross-portfolio overview.
 *
 * <p>`formatMoney.ts` says all arithmetic lives on the server, and that remains the rule for
 * anything the server can compute. The overview is the one place the client must combine figures
 * the API only ever returns per-portfolio, so the addition happens here — under two constraints
 * that keep the spirit of that rule intact:
 *
 * <ol>
 *   <li><b>Never through a float.</b> Every operation below is `BigInt` on the digits, so adding
 *       `"0.10"` and `"0.20"` yields exactly `"0.30"` — not the `0.30000000000000004` a double
 *       would hand back.
 *   <li><b>Never across currencies.</b> {@link sumByCurrency} groups first and totals within each
 *       group. Converting dollars into rupees needs a rate for a date, which is the server's job
 *       and is not guessed at here.
 * </ol>
 *
 * <p>A server-side overview endpoint would be the better home for this. Until one exists, this is
 * the honest version: real totals per currency rather than one invented number.
 */

function scaleOf(amount: string): number {
  const dot = amount.indexOf('.');
  return dot === -1 ? 0 : amount.length - dot - 1;
}

/** The amount as an integer count of its smallest unit at `scale` decimal places. */
function toUnits(amount: string, scale: number): bigint {
  const trimmed = amount.trim();
  const negative = trimmed.startsWith('-');
  const [whole, fraction = ''] = trimmed.replace('-', '').split('.');
  const padded = `${fraction}${'0'.repeat(scale)}`.slice(0, scale);
  const units = BigInt(`${whole || '0'}${padded}`);
  return negative ? -units : units;
}

function fromUnits(units: bigint, scale: number): string {
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(scale + 1, '0');
  const head = scale === 0 ? digits : digits.slice(0, -scale);
  const tail = scale === 0 ? '' : `.${digits.slice(-scale)}`;
  return `${negative ? '-' : ''}${head}${tail}`;
}

/** Exact sum of two decimal strings. The result keeps the wider of the two scales. */
export function addAmounts(left: string, right: string): string {
  const scale = Math.max(scaleOf(left), scaleOf(right));
  return fromUnits(toUnits(left, scale) + toUnits(right, scale), scale);
}

/**
 * Orders two decimal strings exactly: negative when `left` is smaller, as `Array.sort` wants.
 *
 * <p>Comparison, not conversion — safe to use across currencies for *ordering* a list, which is a
 * presentation choice, while never implying the two amounts are equivalent in value.
 */
export function compareAmounts(left: string, right: string): number {
  const scale = Math.max(scaleOf(left), scaleOf(right));
  const a = toUnits(left, scale);
  const b = toUnits(right, scale);
  return a === b ? 0 : a < b ? -1 : 1;
}

export interface CurrencyTotal {
  currency: CurrencyCode;
  total: Money;
  /** How many portfolios contributed to this total. */
  count: number;
}

/**
 * Totals a set of amounts per currency, largest first.
 *
 * <p>Returning a list rather than a single figure is the whole point: four portfolios in three
 * currencies is genuinely three numbers, and flattening them into one would require an exchange
 * rate this layer has no business inventing.
 */
export function sumByCurrency(amounts: (Money | null | undefined)[]): CurrencyTotal[] {
  const buckets = new Map<CurrencyCode, { amount: string; count: number }>();

  for (const money of amounts) {
    if (!money) {
      continue;
    }
    const existing = buckets.get(money.currency);
    buckets.set(money.currency, {
      amount: existing ? addAmounts(existing.amount, money.amount) : money.amount,
      count: (existing?.count ?? 0) + 1,
    });
  }

  return [...buckets.entries()]
    .map(([currency, { amount, count }]) => ({ currency, total: { amount, currency }, count }))
    .sort((left, right) => compareAmounts(right.total.amount, left.total.amount));
}

/** Parses a percentage string for ordering only — never for display. Null sorts last. */
export function comparePercent(left: string | null, right: string | null): number {
  if (left === right) {
    return 0;
  }
  if (left === null) {
    return 1;
  }
  if (right === null) {
    return -1;
  }
  return compareAmounts(left, right);
}
