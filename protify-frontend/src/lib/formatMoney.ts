import type { Money } from '../api/types';

const SYMBOLS: Record<string, string> = { USD: '$', EUR: '€', GBP: '£', INR: '₹' };

/**
 * The one place a server amount becomes display text.
 *
 * <p>It never calls `parseFloat`/`Number` on the amount. The server sends decimal strings
 * precisely so the value that left `BigDecimal` is the value the browser renders, and routing it
 * through a double on the way to the screen would throw that away for nothing. Rescaling is done
 * on the digits with `BigInt`, so `"1450.2567"` shown to 2dp is `1,450.26` — rounded, not
 * truncated, and exact.
 *
 * <p>All arithmetic lives on the server. Nothing here adds, subtracts or compares amounts.
 */
export function formatMoney(money: Money | null | undefined, decimals = 2): string {
  if (!money) {
    return '—';
  }
  return `${SYMBOLS[money.currency] ?? `${money.currency} `}${group(rescale(money.amount, decimals))}`;
}

/** Amounts with no currency of their own — quantities, percentages, FX rates. */
export function formatDecimal(value: string | null | undefined, decimals = 2): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return group(rescale(value, decimals));
}

export function formatPercent(value: string | null | undefined, decimals = 2): string {
  // null is a real answer, not missing data: it means the cost basis was zero and a percentage
  // would have been a divide-by-zero (TEST_PLAN.md §4.2).
  return value === null || value === undefined ? '—' : `${formatDecimal(value, decimals)}%`;
}

export function isNegative(money: Money | null | undefined): boolean {
  return !!money && money.amount.trimStart().startsWith('-');
}

/** Half-up rescale of a decimal string, done on the digits — never through a float. */
function rescale(amount: string, decimals: number): string {
  const negative = amount.trimStart().startsWith('-');
  const [whole, fraction = ''] = amount.trim().replace('-', '').split('.');
  const padded = `${fraction}${'0'.repeat(decimals + 1)}`.slice(0, decimals + 1);

  let digits = BigInt(`${whole}${padded.slice(0, decimals)}`);
  if (Number(padded[decimals]) >= 5) {
    digits += 1n;
  }

  const text = digits.toString().padStart(decimals + 1, '0');
  const head = decimals === 0 ? text : text.slice(0, -decimals);
  const tail = decimals === 0 ? '' : `.${text.slice(-decimals)}`;
  const rescaled = `${head}${tail}`;
  // -0.00 is never what anyone means
  return negative && /[1-9]/.test(rescaled) ? `-${rescaled}` : rescaled;
}

function group(value: string): string {
  const negative = value.startsWith('-');
  const [whole, fraction] = value.replace('-', '').split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}${grouped}${fraction ? `.${fraction}` : ''}`;
}
