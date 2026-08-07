/**
 * Conditional class names. Small enough not to be worth a dependency, and keeping it local means
 * the falsy-filtering rule is written down where people can read it: `false`, `null`, `undefined`
 * and `''` all drop out, so `cx('btn', isActive && 'btn-active')` is safe.
 */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}
