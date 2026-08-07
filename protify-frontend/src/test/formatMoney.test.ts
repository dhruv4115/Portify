import { describe, expect, it } from 'vitest';
import { formatDecimal, formatMoney, formatPercent, isNegative } from '../lib/formatMoney';

describe('formatMoney', () => {
  it('formats each currency with its own symbol and thousands separators', () => {
    expect(formatMoney({ amount: '238136.4000', currency: 'INR' })).toBe('₹238,136.40');
    expect(formatMoney({ amount: '2575.9900', currency: 'USD' })).toBe('$2,575.99');
    expect(formatMoney({ amount: '27.4400', currency: 'GBP' })).toBe('£27.44');
  });

  it('rounds half-up on the digits, never through a float', () => {
    // 1450.2567 as a double is 1450.2566999999999...; rounding it as text is exact
    expect(formatMoney({ amount: '1450.2567', currency: 'USD' })).toBe('$1,450.26');
    expect(formatMoney({ amount: '0.005', currency: 'USD' })).toBe('$0.01');
    // the classic double failure: 0.1 + 0.2. Formatting must not reintroduce it.
    expect(formatMoney({ amount: '0.3000', currency: 'USD' })).toBe('$0.30');
  });

  it('keeps every digit of a value too large for a double to hold exactly', () => {
    expect(formatMoney({ amount: '9007199254740993.0000', currency: 'INR' })).toBe(
      '₹9,007,199,254,740,993.00',
    );
  });

  it('renders a missing amount as a dash rather than zero', () => {
    // null means "could not be priced", and showing 0 would claim the position is worthless
    expect(formatMoney(null)).toBe('—');
    expect(formatMoney(undefined)).toBe('—');
  });

  it('keeps a negative sign but never produces -0.00', () => {
    expect(formatMoney({ amount: '-4307.3900', currency: 'INR' })).toBe('₹-4,307.39');
    expect(formatMoney({ amount: '-0.001', currency: 'INR' })).toBe('₹0.00');
    expect(isNegative({ amount: '-4307.3900', currency: 'INR' })).toBe(true);
    expect(isNegative({ amount: '4307.3900', currency: 'INR' })).toBe(false);
  });

  it('renders quantities at their own scale', () => {
    expect(formatDecimal('12.000000', 6)).toBe('12.000000');
    expect(formatDecimal('0.523100', 6)).toBe('0.523100');
  });

  it('renders a null percentage as a dash, because null is a real answer', () => {
    // cost basis of zero means a percentage would be a divide-by-zero (TEST_PLAN.md §4.2)
    expect(formatPercent(null)).toBe('—');
    expect(formatPercent('6.3800')).toBe('6.38%');
  });
});
