import type { PerformanceInterval } from '../api/types';

export type RangeKey = '1M' | '3M' | '6M' | 'YTD' | '1Y' | 'ALL';

export const RANGE_KEYS: RangeKey[] = ['1M', '3M', '6M', 'YTD', '1Y', 'ALL'];

export const INTERVALS: PerformanceInterval[] = ['DAILY', 'WEEKLY', 'MONTHLY'];

/**
 * Turns a range button into the `from` date the performance endpoint already accepts.
 *
 * <p>Dates are computed in UTC and formatted as `YYYY-MM-DD`, matching what the API returns. Doing
 * this in local time would put someone in Auckland a day ahead of the series they are asking for.
 *
 * <p>`ALL` returns `undefined` — no `from` parameter at all — so the server applies its own
 * default window rather than the client guessing when the portfolio started.
 */
export function rangeStart(range: RangeKey, now: Date = new Date()): string | undefined {
  if (range === 'ALL') {
    return undefined;
  }

  const start = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()),
  );

  switch (range) {
    case '1M':
      start.setUTCMonth(start.getUTCMonth() - 1);
      break;
    case '3M':
      start.setUTCMonth(start.getUTCMonth() - 3);
      break;
    case '6M':
      start.setUTCMonth(start.getUTCMonth() - 6);
      break;
    case '1Y':
      start.setUTCFullYear(start.getUTCFullYear() - 1);
      break;
    case 'YTD':
      start.setUTCMonth(0, 1);
      break;
  }

  return start.toISOString().slice(0, 10);
}

/**
 * Shortens an ISO date for an axis tick.
 *
 * <p>Formatted with `Intl` in the active locale, because "Mar 5" and "5 mars" and "3月5日" are the
 * same information and only one of them is readable to any given user. A long window drops the
 * day entirely — 200 daily ticks cannot all be labelled, so the axis shows months instead.
 */
export function formatAxisDate(iso: string, locale: string, span: number): string {
  const date = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const options: Intl.DateTimeFormatOptions =
    span > 180
      ? { month: 'short', year: '2-digit', timeZone: 'UTC' }
      : { month: 'short', day: 'numeric', timeZone: 'UTC' };
  return new Intl.DateTimeFormat(locale, options).format(date);
}

/** The full date, for a tooltip where there is room to be unambiguous. */
export function formatFullDate(iso: string, locale: string): string {
  const date = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return new Intl.DateTimeFormat(locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(date);
}
