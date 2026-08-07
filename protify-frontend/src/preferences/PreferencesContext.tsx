import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export type ThemeChoice = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';
export type Density = 'comfortable' | 'compact';
export type MotionChoice = 'full' | 'reduced';

/** The same keys the inline bootstrap script in `index.html` reads. Change both or neither. */
const KEYS = {
  theme: 'protify.theme',
  density: 'protify.density',
  motion: 'protify.motion',
} as const;

interface PreferencesValue {
  theme: ThemeChoice;
  /** What `theme` actually resolves to right now — `system` collapsed to light or dark. */
  resolvedTheme: ResolvedTheme;
  density: Density;
  motion: MotionChoice;
  /** True when the OS asks for reduced motion, which is honoured whatever `motion` says. */
  systemReducedMotion: boolean;
  setTheme: (next: ThemeChoice) => void;
  setDensity: (next: Density) => void;
  setMotion: (next: MotionChoice) => void;
  toggleTheme: () => void;
  reset: () => void;
}

const DEFAULTS = { theme: 'system', density: 'comfortable', motion: 'full' } as const;

/**
 * `matchMedia` is absent in jsdom and in some embedded webviews, so it is never called directly.
 * A missing implementation means "no opinion", which resolves to the light, full-motion default —
 * not a crash on the first render.
 */
function mediaQuery(query: string): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return null;
  }
  return window.matchMedia(query);
}

function prefersDark(): boolean {
  return mediaQuery('(prefers-color-scheme: dark)')?.matches ?? false;
}

function prefersReducedMotion(): boolean {
  return mediaQuery('(prefers-reduced-motion: reduce)')?.matches ?? false;
}

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const stored = localStorage.getItem(key) as T | null;
    return stored && allowed.includes(stored) ? stored : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* Private browsing can refuse writes; the choice still applies for this session. */
  }
}

/**
 * Like {@link I18nProvider}'s, this default is a working implementation rather than `null`, so a
 * component rendered outside the provider (every unit test) gets sane values instead of a throw.
 * The setters are no-ops there — nothing in a test should be persisting a theme anyway.
 */
const PreferencesContext = createContext<PreferencesValue>({
  theme: 'system',
  resolvedTheme: 'light',
  density: 'comfortable',
  motion: 'full',
  systemReducedMotion: false,
  setTheme: () => {},
  setDensity: () => {},
  setMotion: () => {},
  toggleTheme: () => {},
  reset: () => {},
});

/**
 * Device-local display preferences.
 *
 * <p>Every value here is written straight onto `<html>` as a data attribute rather than threaded
 * through React. The CSS in `styles/` keys off those attributes, so a preference change repaints
 * without re-rendering the tree, and the inline script in `index.html` can apply the same
 * attributes before React boots — which is what stops the first frame flashing the wrong theme.
 *
 * <p>Nothing here is sent to the server. These are display choices, not account settings.
 */
export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeChoice>(() =>
    read(KEYS.theme, ['light', 'dark', 'system'] as const, DEFAULTS.theme),
  );
  const [density, setDensityState] = useState<Density>(() =>
    read(KEYS.density, ['comfortable', 'compact'] as const, DEFAULTS.density),
  );
  const [motion, setMotionState] = useState<MotionChoice>(() =>
    read(KEYS.motion, ['full', 'reduced'] as const, DEFAULTS.motion),
  );
  const [systemDark, setSystemDark] = useState(prefersDark);
  const [systemReducedMotion, setSystemReducedMotion] = useState(prefersReducedMotion);

  // Following "system" means following it as it changes, not only as it was at load.
  useEffect(() => {
    const darkQuery = mediaQuery('(prefers-color-scheme: dark)');
    const motionQuery = mediaQuery('(prefers-reduced-motion: reduce)');
    const onDark = (event: MediaQueryListEvent) => setSystemDark(event.matches);
    const onMotion = (event: MediaQueryListEvent) => setSystemReducedMotion(event.matches);

    darkQuery?.addEventListener('change', onDark);
    motionQuery?.addEventListener('change', onMotion);
    return () => {
      darkQuery?.removeEventListener('change', onDark);
      motionQuery?.removeEventListener('change', onMotion);
    };
  }, []);

  const resolvedTheme: ResolvedTheme = theme === 'system' ? (systemDark ? 'dark' : 'light') : theme;

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', resolvedTheme);
    // Keeps the browser chrome (address bar, form controls) in step with the page.
    document
      .querySelector('meta[name="theme-color"]:not([media])')
      ?.setAttribute('content', resolvedTheme === 'dark' ? '#0a0b0f' : '#f6f7f9');
  }, [resolvedTheme]);

  useEffect(() => {
    document.documentElement.setAttribute('data-density', density);
  }, [density]);

  useEffect(() => {
    document.documentElement.setAttribute('data-motion', motion);
  }, [motion]);

  const setTheme = useCallback((next: ThemeChoice) => {
    setThemeState(next);
    write(KEYS.theme, next);
  }, []);

  const setDensity = useCallback((next: Density) => {
    setDensityState(next);
    write(KEYS.density, next);
  }, []);

  const setMotion = useCallback((next: MotionChoice) => {
    setMotionState(next);
    write(KEYS.motion, next);
  }, []);

  // The header's one-tap toggle. It commits to an explicit choice rather than cycling through
  // "system", because someone reaching for the toggle wants *this* theme, not a third state.
  const toggleTheme = useCallback(() => {
    setTheme(resolvedTheme === 'dark' ? 'light' : 'dark');
  }, [resolvedTheme, setTheme]);

  const reset = useCallback(() => {
    setTheme(DEFAULTS.theme);
    setDensity(DEFAULTS.density);
    setMotion(DEFAULTS.motion);
  }, [setTheme, setDensity, setMotion]);

  const value = useMemo<PreferencesValue>(
    () => ({
      theme,
      resolvedTheme,
      density,
      motion,
      systemReducedMotion,
      setTheme,
      setDensity,
      setMotion,
      toggleTheme,
      reset,
    }),
    [theme, resolvedTheme, density, motion, systemReducedMotion, setTheme, setDensity, setMotion, toggleTheme, reset],
  );

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences(): PreferencesValue {
  return useContext(PreferencesContext);
}
