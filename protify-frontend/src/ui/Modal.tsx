import { useEffect, useId, useRef, type ReactNode } from 'react';
import { cx } from './cx';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
  /** Hides the visible heading but keeps it as the dialog's accessible name. */
  hideTitle?: boolean;
  labelledBy?: string;
}

/**
 * A modal dialog with the four behaviours that make one usable rather than merely visible:
 * Escape closes it, focus moves into it on open, Tab cycles inside it rather than escaping to the
 * page behind, and focus returns to whatever opened it on close.
 *
 * <p>The page behind is also frozen — not for looks, but because a dialog that scrolls the
 * document underneath it makes the user lose their place while making a decision.
 */
export function Modal({ title, onClose, children, footer, className, hideTitle, labelledBy }: Props) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const generatedId = useId();
  const titleId = labelledBy ?? generatedId;

  useEffect(() => {
    restoreTo.current = document.activeElement as HTMLElement | null;

    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';

    // The dialog itself is focusable as a last resort, so focus never stays on the page behind
    // even in a dialog whose content is entirely static text.
    const first = dialogRef.current?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? dialogRef.current)?.focus();

    return () => {
      document.body.style.overflow = overflow;
      restoreTo.current?.focus?.();
    };
  }, []);

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onClose();
      return;
    }

    if (event.key !== 'Tab') {
      return;
    }

    const focusable = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    if (focusable.length === 0) {
      return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;

    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx('modal', className)}
        // mousedown rather than click: a drag that starts inside the dialog and releases on the
        // backdrop (selecting text, for instance) should not count as "clicked outside".
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId} className={cx('text-base font-semibold', hideTitle && 'visually-hidden')}>
          {title}
        </h2>
        <div className={cx(!hideTitle && 'mt-2')}>{children}</div>
        {footer && <div className="mt-6 flex flex-wrap justify-end gap-2">{footer}</div>}
      </div>
    </div>
  );
}
