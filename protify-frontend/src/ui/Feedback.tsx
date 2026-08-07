import type { ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './cx';

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx('skeleton', className)} />;
}

/**
 * A skeleton shaped like the thing it stands in for.
 *
 * <p>Rows of differing width read as text rather than as a loading bar, which is what stops the
 * placeholder registering as "broken" in the half-second before data lands.
 */
export function SkeletonRows({ rows = 3, testId }: { rows?: number; testId?: string }) {
  const widths = ['100%', '92%', '96%', '88%', '94%'];
  return (
    <div data-testid={testId} className="space-y-2">
      {Array.from({ length: rows }, (_, index) => (
        <div
          key={index}
          className="skeleton skeleton-row"
          style={{ width: widths[index % widths.length] }}
        />
      ))}
    </div>
  );
}

interface EmptyStateProps {
  /** Kept as a plain text child of one element — nothing splits it, so tests can match it. */
  title: string;
  body?: string;
  icon?: IconName;
  action?: ReactNode;
  testId?: string;
  className?: string;
  /** Compact variant for empty states inside an already-titled card. */
  inset?: boolean;
}

/**
 * The empty state.
 *
 * <p>Every one of these says what is missing *and* what to do about it. "No transactions" alone
 * leaves the user to guess whether the app is broken or simply new; the second sentence is what
 * turns a dead end into a next step.
 */
export function EmptyState({ title, body, icon, action, testId, className, inset }: EmptyStateProps) {
  return (
    <div
      data-testid={testId}
      className={cx(
        'flex flex-col items-center justify-center gap-3 text-center animate-fade-in',
        inset ? 'py-8' : 'py-14',
        className,
      )}
    >
      {icon && (
        <span className="grid h-11 w-11 place-items-center rounded-2xl border border-hairline bg-surface-2 text-ink-3">
          <Icon name={icon} size={19} />
        </span>
      )}
      <p className="text-[0.9375rem] font-semibold text-ink">{title}</p>
      {body && <p className="max-w-sm text-[0.8125rem] leading-relaxed text-ink-3">{body}</p>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}

/**
 * A failure, stated plainly. `role="alert"` because unlike an empty state this is unexpected and
 * worth interrupting for.
 */
export function ErrorState({
  title,
  action,
  className,
}: {
  title: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div
      role="alert"
      className={cx(
        'flex flex-col items-center justify-center gap-3 py-12 text-center animate-fade-in',
        className,
      )}
    >
      <span className="grid h-11 w-11 place-items-center rounded-2xl border border-[var(--warn-border)] bg-[var(--warn-soft)] text-[var(--warn)]">
        <Icon name="alert" size={19} />
      </span>
      <p className="text-[0.9375rem] font-semibold text-ink">{title}</p>
      {action}
    </div>
  );
}
