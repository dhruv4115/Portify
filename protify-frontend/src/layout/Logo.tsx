import { cx } from '../ui/cx';

/**
 * The mark: a "P" cut out of a gradient tile, with the counter of the bowl left open so it reads
 * as a rising line at small sizes. Drawn rather than imported so it inherits the brand gradient
 * tokens and stays crisp at any density.
 */
export function Logo({ size = 30, className }: { size?: number; className?: string }) {
  return (
    <span
      className={cx('grid shrink-0 place-items-center rounded-[0.5rem] shadow-[var(--shadow-brand)]', className)}
      style={{ width: size, height: size, background: 'var(--gradient-brand)' }}
      aria-hidden="true"
    >
      <svg width={size * 0.62} height={size * 0.62} viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M7 19V5h6.4a4.6 4.6 0 0 1 0 9.2H10"
          stroke="white"
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </span>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cx('text-[0.9375rem] font-bold tracking-[-0.02em] text-ink', className)}>Protify</span>
  );
}
