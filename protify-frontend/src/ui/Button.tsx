import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Icon, type IconName } from './Icon';
import { cx } from './cx';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link' | 'linkDanger';
type Size = 'sm' | 'md' | 'lg';

const VARIANTS: Record<Variant, string> = {
  primary: 'btn btn-primary',
  secondary: 'btn btn-secondary',
  ghost: 'btn btn-ghost',
  danger: 'btn btn-danger',
  link: 'btn-link',
  linkDanger: 'btn-link-danger',
};

const SIZES: Record<Size, string> = { sm: 'btn-sm', md: '', lg: 'btn-lg' };

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  /** Rendered before the label. Decorative — the label is what names the button. */
  icon?: IconName;
  iconAfter?: IconName;
  /** Swaps the icon for a spinner and disables the button, without changing its width. */
  busy?: boolean;
  children?: ReactNode;
}

/**
 * The button.
 *
 * <p>`type` defaults to `button`. That is not a style preference: an unqualified `<button>` inside
 * a form is a submit button, which is how a "Cancel" ends up posting the form it was meant to
 * abandon. Submitting buttons opt in explicitly with `type="submit"`.
 */
export function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  iconAfter,
  busy = false,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: Props) {
  const isTextLink = variant === 'link' || variant === 'linkDanger';

  return (
    <button
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cx(VARIANTS[variant], !isTextLink && SIZES[size], className)}
      {...rest}
    >
      {busy ? (
        <Icon name="refresh" size={15} className="animate-[spin_0.8s_linear_infinite]" />
      ) : (
        icon && <Icon name={icon} size={15} />
      )}
      {children}
      {iconAfter && !busy && <Icon name={iconAfter} size={15} />}
    </button>
  );
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon: IconName;
  /** Required: with no visible text, this is the button's entire accessible name. */
  label: string;
  size?: number;
}

export function IconButton({ icon, label, size = 17, className, type = 'button', ...rest }: IconButtonProps) {
  return (
    <button type={type} aria-label={label} title={label} className={cx('btn-icon', className)} {...rest}>
      <Icon name={icon} size={size} />
    </button>
  );
}
