import type { ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './cx';

interface CardProps {
  children: ReactNode;
  className?: string;
  /** Adds the lit top edge. On by default; off for cards that sit inside another surface. */
  sheen?: boolean;
  interactive?: boolean;
  id?: string;
  'aria-busy'?: boolean;
}

export function Card({ children, className, sheen = true, interactive, id, ...rest }: CardProps) {
  return (
    <div
      id={id}
      className={cx('card', sheen && 'card-sheen', interactive && 'card-interactive', className)}
      {...rest}
    >
      {children}
    </div>
  );
}

interface SectionCardProps {
  title: string;
  /** Sits above the title in small caps — the section's category, not a second title. */
  eyebrow?: string;
  icon?: IconName;
  description?: string;
  /** Controls, badges or a summary figure, aligned to the far end of the header row. */
  actions?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
  id?: string;
  busy?: boolean;
  /** Heading level, so a page keeps one h1 and its sections nest correctly beneath it. */
  as?: 'h2' | 'h3';
}

/**
 * A titled panel — the shape almost every block on the dashboard takes.
 *
 * <p>The title is a real heading rather than styled text, which is what lets a screen-reader user
 * jump between "Performance", "Holdings" and "Transactions" the same way a sighted user scans for
 * them. The `<section>` is labelled by that heading for the same reason.
 */
export function SectionCard({
  title,
  eyebrow,
  icon,
  description,
  actions,
  footer,
  children,
  className,
  id,
  busy,
  as: Heading = 'h2',
}: SectionCardProps) {
  const headingId = id ? `${id}-title` : undefined;

  return (
    <section
      id={id}
      aria-labelledby={headingId}
      aria-busy={busy || undefined}
      className={cx('card card-sheen scroll-mt-24', className)}
    >
      <header className="card-header">
        <div className="min-w-0">
          {eyebrow && <p className="card-eyebrow mb-1">{eyebrow}</p>}
          <Heading id={headingId} className="card-title flex items-center gap-2">
            {icon && <Icon name={icon} size={15} className="text-ink-3" />}
            {title}
          </Heading>
          {description && <p className="mt-1 text-[0.8125rem] text-ink-3">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </header>

      {children}

      {footer && <div className="mt-4 border-t border-hairline-subtle pt-3">{footer}</div>}
    </section>
  );
}
