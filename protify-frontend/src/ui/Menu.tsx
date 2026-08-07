import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cx } from './cx';

/**
 * Closes a popover when attention moves away from it — a click elsewhere, Escape, or focus
 * leaving the subtree entirely.
 *
 * <p>The focus check is what makes this keyboard-safe: a menu that only listens for outside
 * *clicks* stays stuck open for someone tabbing past it.
 */
export function useDismissable(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }

    function onPointerDown(event: MouseEvent | TouchEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        onClose();
      }
    }

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }

    function onFocusIn(event: FocusEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        onClose();
      }
    }

    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [open, onClose]);

  return ref;
}

interface MenuProps {
  /** Renders the control that opens the menu. Receives the props it must spread onto it. */
  trigger: (props: {
    onClick: () => void;
    'aria-expanded': boolean;
    'aria-haspopup': 'menu';
    ref: React.Ref<HTMLButtonElement>;
  }) => ReactNode;
  children: (close: () => void) => ReactNode;
  label: string;
  align?: 'start' | 'end';
  className?: string;
}

/**
 * A dropdown menu. Deliberately not a `<select>`: these hold actions and checkable settings, and
 * a native select cannot carry an icon, a description or a keyboard hint.
 */
export function Menu({ trigger, children, label, align = 'end', className }: MenuProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const containerRef = useDismissable(open, () => setOpen(false));

  function close() {
    setOpen(false);
    // Escape and selection both return the user to where they were, not to the top of the page.
    triggerRef.current?.focus();
  }

  return (
    <div ref={containerRef} className={cx('relative', className)}>
      {trigger({
        onClick: () => setOpen((current) => !current),
        'aria-expanded': open,
        'aria-haspopup': 'menu',
        ref: triggerRef,
      })}

      {open && (
        <div
          role="menu"
          aria-label={label}
          className={cx('menu absolute top-[calc(100%+0.375rem)] z-50', align === 'end' ? 'end-0' : 'start-0')}
        >
          {children(close)}
        </div>
      )}
    </div>
  );
}

interface MenuItemProps {
  onSelect: () => void;
  children: ReactNode;
  icon?: ReactNode;
  /** Renders the item as a checkable option and marks the current one. */
  checked?: boolean;
  description?: string;
  trailing?: ReactNode;
}

export function MenuItem({ onSelect, children, icon, checked, description, trailing }: MenuItemProps) {
  return (
    <button
      type="button"
      role={checked === undefined ? 'menuitem' : 'menuitemradio'}
      aria-checked={checked}
      onClick={onSelect}
      className="menu-item"
    >
      {icon}
      <span className="flex-1 min-w-0">
        <span className="block truncate">{children}</span>
        {description && <span className="block truncate text-[0.6875rem] text-ink-3">{description}</span>}
      </span>
      {trailing}
    </button>
  );
}
