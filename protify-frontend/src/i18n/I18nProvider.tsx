import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { en, type Catalog, type TranslationKey } from './catalogs/en';
import {
  DEFAULT_LANGUAGE,
  detectLanguage,
  isLanguage,
  languageMeta,
  type Language,
  type LanguageMeta,
} from './languages';

const STORAGE_KEY = 'protify.language';

export type TranslationVars = Record<string, string | number>;

interface I18nValue {
  language: Language;
  meta: LanguageMeta;
  dir: 'ltr' | 'rtl';
  /** True while a catalogue is being fetched. The UI stays on English rather than blanking. */
  loading: boolean;
  setLanguage: (next: Language) => void;
  t: (key: TranslationKey, vars?: TranslationVars) => string;
}

/**
 * Unicode FIRST STRONG ISOLATE / POP DIRECTIONAL ISOLATE.
 *
 * <p>Wrapping a substituted value in these tells the bidi algorithm to lay it out as a self-
 * contained run whose direction is decided by its own first strong character. Without them,
 * `'قديم · {date}'` with `date = '2026-07-28'` renders as `28-07-2026 · قديم` — the date's own
 * digits get dragged into the surrounding right-to-left flow and come out reversed.
 */
const ISOLATE_START = '⁨';
const ISOLATE_END = '⁩';

/**
 * Named placeholders, resolved on the rendered string.
 *
 * <p>An unknown placeholder is left exactly as written rather than replaced with `undefined`.
 * A visible `{count}` on screen is a bug report; the word "undefined" is a mystery.
 *
 * <p>`isolate` is applied only in right-to-left languages. The characters are invisible either
 * way, but adding them in left-to-right text would put them inside strings that tests and
 * `getByText` match on for no benefit.
 */
function interpolate(template: string, vars?: TranslationVars, isolate = false): string {
  if (!vars) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => {
    if (!(name in vars)) {
      return whole;
    }
    const value = String(vars[name]);
    return isolate ? `${ISOLATE_START}${value}${ISOLATE_END}` : value;
  });
}

function translateWith(
  catalog: Partial<Catalog>,
  key: TranslationKey,
  vars?: TranslationVars,
  isolate = false,
): string {
  return interpolate(catalog[key] ?? en[key], vars, isolate);
}

/**
 * The default value is a working English implementation, not `null`.
 *
 * <p>That is deliberate and load-bearing: components are rendered directly in tests, outside any
 * provider, and a hook that threw there would force every test to wrap its subject in scaffolding
 * it does not care about. A component with no provider above it simply renders the source
 * language — which is exactly the right answer, and the same answer the tests assert against.
 */
const I18nContext = createContext<I18nValue>({
  language: DEFAULT_LANGUAGE,
  meta: languageMeta(DEFAULT_LANGUAGE),
  dir: 'ltr',
  loading: false,
  setLanguage: () => {},
  t: (key, vars) => interpolate(en[key], vars),
});

/**
 * Catalogues are fetched on demand, so the initial bundle carries English only. Everything else
 * arrives as its own chunk the first time somebody asks for it, and is then cached for the
 * session — switching back and forth costs one request, not one per switch.
 */
const LOADERS: Record<Exclude<Language, 'en'>, () => Promise<Partial<Catalog>>> = {
  hi: () => import('./catalogs/hi').then((module) => module.hi),
  es: () => import('./catalogs/es').then((module) => module.es),
  fr: () => import('./catalogs/fr').then((module) => module.fr),
  de: () => import('./catalogs/de').then((module) => module.de),
  pt: () => import('./catalogs/pt').then((module) => module.pt),
  ja: () => import('./catalogs/ja').then((module) => module.ja),
  zh: () => import('./catalogs/zh').then((module) => module.zh),
  ar: () => import('./catalogs/ar').then((module) => module.ar),
};

const cache = new Map<Language, Partial<Catalog>>([['en', en]]);

function readStoredLanguage(): Language {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (isLanguage(stored)) {
      return stored;
    }
    return detectLanguage();
  } catch {
    return DEFAULT_LANGUAGE;
  }
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(readStoredLanguage);
  const [catalog, setCatalog] = useState<Partial<Catalog>>(() => cache.get(language) ?? en);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const cached = cache.get(language);
    if (cached) {
      setCatalog(cached);
      return;
    }

    let cancelled = false;
    setLoading(true);
    LOADERS[language as Exclude<Language, 'en'>]()
      .then((loaded) => {
        cache.set(language, loaded);
        if (!cancelled) {
          setCatalog(loaded);
        }
      })
      // A catalogue that fails to load leaves the UI in English. That is a degraded experience,
      // not a broken one — the same principle the stale-price badge follows.
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [language]);

  // `lang` and `dir` belong on the document, not on a wrapper div: screen readers switch voice
  // from `lang`, and `dir` has to be above every portal (toasts, modals) to mirror them too.
  useEffect(() => {
    const meta = languageMeta(language);
    document.documentElement.setAttribute('lang', meta.code);
    document.documentElement.setAttribute('dir', meta.dir);
  }, [language]);

  const setLanguage = useCallback((next: Language) => {
    setLanguageState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* Private browsing can refuse writes; the choice still applies for this session. */
    }
  }, []);

  const value = useMemo<I18nValue>(() => {
    const meta = languageMeta(language);
    return {
      language,
      meta,
      dir: meta.dir,
      loading,
      setLanguage,
      t: (key, vars) => translateWith(catalog, key, vars, meta.dir === 'rtl'),
    };
  }, [language, catalog, loading, setLanguage]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}

/** The common case — just the translate function. */
export function useT(): I18nValue['t'] {
  return useContext(I18nContext).t;
}
