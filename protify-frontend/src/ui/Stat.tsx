import type { ReactNode } from 'react';
import type { Money } from '../api/types';
import { formatMoney, formatPercent, isNegative } from '../lib/formatMoney';
import { Icon } from './Icon';
import { cx } from './cx';

/**
 * A signed change — the P&L figure that appears on nearly every surface.
 *
 * <p>Direction is carried by an arrow *and* the sign of the number, never by colour alone. Around
 * one man in twelve cannot reliably separate the red from the green, and a portfolio screen where
 * the difference between a gain and a loss is a hue is a screen that lies to them.
 *
 * <p>`isNegative` reads the sign off the server's decimal string rather than comparing a parsed
 * number, so nothing here routes an amount through a float.
 */
export function Delta({
  money,
  percent,
  size = 'md',
  showIcon = true,
  className,
}: {
  money: Money | null | undefined;
  percent?: string | null;
  size?: 'sm' | 'md' | 'lg';
  showIcon?: boolean;
  className?: string;
}) {
  const negative = isNegative(money);
  const sizes = {
    sm: 'text-[0.75rem] gap-1',
    md: 'text-[0.8125rem] gap-1',
    lg: 'text-[0.9375rem] gap-1.5',
  } as const;

  // Assembled into one string rather than nested spans on purpose. Split across elements the
  // amount and its percentage stop being one readable phrase — a screen reader pauses between
  // them, and `getByText('₹199.00 (19.90%)')` stops matching, because the DOM no longer holds
  // that sentence anywhere.
  const label =
    percent === undefined ? formatMoney(money) : `${formatMoney(money)} (${formatPercent(percent)})`;

  return (
    <span
      className={cx(
        'inline-flex items-center font-semibold tabular-nums',
        sizes[size],
        negative ? 'text-neg' : 'text-pos',
        className,
      )}
    >
      {showIcon && <Icon name={negative ? 'trendDown' : 'trendUp'} size={size === 'lg' ? 15 : 13} strokeWidth={2.1} />}
      {label}
    </span>
  );
}

/** The same signal as {@link Delta}, as a filled chip. Used where space is tight. */
export function DeltaBadge({ money, percent }: { money: Money | null | undefined; percent?: string | null }) {
  const negative = isNegative(money);
  return (
    <span className={cx('badge tabular-nums', negative ? 'badge-neg' : 'badge-pos')}>
      <Icon name={negative ? 'trendDown' : 'trendUp'} size={11} strokeWidth={2.3} />
      {percent !== undefined ? formatPercent(percent) : formatMoney(money)}
    </span>
  );
}

interface StatTileProps {
  label: string;
  value: ReactNode;
  sub?: ReactNode;
  /** Sits beside the label — a stale badge, a currency code, an info affordance. */
  meta?: ReactNode;
  hint?: string;
  className?: string;
  /** Larger treatment for the one figure a page is actually about. */
  emphasis?: boolean;
}

/**
 * A single figure with its label.
 *
 * <p>The value is set in proportional figures at display size and tabular ones only where it has
 * to line up in a column — tabular digits are for alignment, and using them on a hero number just
 * makes it look loose.
 */
export function StatTile({ label, value, sub, meta, hint, className, emphasis }: StatTileProps) {
  return (
    <div className={cx('min-w-0', className)}>
      <div className="flex items-center gap-1.5">
        <p className="truncate text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3" title={hint}>
          {label}
        </p>
        {meta}
      </div>
      <p
        className={cx(
          'mt-1 font-semibold tabular-nums text-ink',
          emphasis ? 'text-[1.75rem] leading-tight tracking-[-0.025em]' : 'text-[1.0625rem] tracking-[-0.015em]',
        )}
      >
        {value}
      </p>
      {sub && <div className="mt-0.5 text-[0.8125rem]">{sub}</div>}
    </div>
  );
}
