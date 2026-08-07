import { cx } from './cx';

interface Option<T extends string> {
  value: T;
  label: string;
  /** Announced instead of the abbreviated label — "1M" is not a word. */
  title?: string;
}

interface Props<T extends string> {
  options: readonly Option<T>[];
  value: T;
  onChange: (next: T) => void;
  /** Names the group for assistive tech, since the buttons alone have no shared context. */
  label: string;
  className?: string;
  size?: 'sm' | 'md';
}

/**
 * A row of mutually exclusive choices — time range, interval, view mode.
 *
 * <p>Modelled as a `radiogroup` rather than a set of toggle buttons: "exactly one of these is
 * true" is what a radio group means, and it gives keyboard users arrow-key navigation for free
 * through the browser's own handling once roving focus is in place.
 */
export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
  className,
  size = 'md',
}: Props<T>) {
  function move(direction: 1 | -1) {
    const index = options.findIndex((option) => option.value === value);
    const next = options[(index + direction + options.length) % options.length];
    onChange(next.value);
  }

  return (
    <div
      role="radiogroup"
      aria-label={label}
      className={cx('segmented', className)}
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
          event.preventDefault();
          move(1);
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
          event.preventDefault();
          move(-1);
        }
      }}
    >
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            aria-selected={selected}
            title={option.title}
            // Roving tabindex: the group is one tab stop, and arrows move within it.
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(option.value)}
            className={cx('segmented-item', size === 'sm' && 'px-2 py-0.5 text-[0.6875rem]')}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
