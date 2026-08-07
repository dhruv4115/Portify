import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react';
import { cx } from './cx';

interface FieldProps {
  label: string;
  htmlFor: string;
  error?: string;
  hint?: ReactNode;
  /** Spans the whole form grid rather than one column. */
  wide?: boolean;
  className?: string;
  children: ReactNode;
}

/**
 * A labelled form control with its error and hint.
 *
 * <p>The wrapper keeps the class name `field` because the form tests locate a control's error by
 * walking up from the input (`getByLabelText(…).closest('.field')`). That coupling is the reason
 * the error must also stay *inside* this element rather than being hoisted to a form-level list.
 *
 * <p>The error carries `role="alert"` so it is announced when it appears; the hint deliberately
 * does not, because a hint that interrupts a screen-reader user mid-sentence is noise.
 */
export function Field({ label, htmlFor, error, hint, wide, className, children }: FieldProps) {
  return (
    <div className={cx('field', wide && 'sm:col-span-full', className)}>
      <label className="field-label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error && (
        <span className="field-error" role="alert">
          {error}
        </span>
      )}
      {hint && <span className="field-hint">{hint}</span>}
    </div>
  );
}

interface TextInputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export function TextInput({ invalid, className, ...rest }: TextInputProps) {
  return <input className={cx('control', className)} aria-invalid={invalid || undefined} {...rest} />;
}

interface SelectInputProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

export function SelectInput({ invalid, className, children, ...rest }: SelectInputProps) {
  return (
    <select className={cx('control', className)} aria-invalid={invalid || undefined} {...rest}>
      {children}
    </select>
  );
}

/**
 * A search box with a leading magnifier and a clear button that only exists when there is
 * something to clear — a permanently-visible disabled × is a target that punishes the user for
 * aiming at it.
 */
export function SearchInput({
  value,
  onValueChange,
  label,
  clearLabel,
  id,
  placeholder,
  className,
}: {
  value: string;
  onValueChange: (next: string) => void;
  label: string;
  clearLabel: string;
  id: string;
  placeholder?: string;
  className?: string;
}) {
  return (
    <div className={cx('relative', className)}>
      <svg
        viewBox="0 0 24 24"
        width="15"
        height="15"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.9"
        strokeLinecap="round"
        className="pointer-events-none absolute inset-y-0 my-auto start-3 text-ink-3"
        aria-hidden="true"
      >
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </svg>
      <input
        id={id}
        type="search"
        aria-label={label}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onValueChange(event.target.value)}
        className="control ps-9 pe-9 [&::-webkit-search-cancel-button]:hidden"
      />
      {value && (
        <button
          type="button"
          aria-label={clearLabel}
          onClick={() => onValueChange('')}
          className="absolute inset-y-0 my-auto end-2 grid h-6 w-6 place-items-center rounded-md text-ink-3 transition-colors hover:bg-surface-3 hover:text-ink"
        >
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      )}
    </div>
  );
}
