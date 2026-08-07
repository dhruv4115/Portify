/**
 * The language registry.
 *
 * <p>English is not one option among many — it is the source. Every other catalogue is a partial
 * overlay on top of it, so a key that has not been translated yet renders the English sentence
 * rather than a key name or a blank. That is what makes adding a language a safe, incremental
 * act: a half-finished catalogue is a usable one.
 *
 * <p>`endonym` is the name of the language *in that language*. A switcher that lists "Hindi" to
 * someone who reads only Hindi has not helped them; one that lists "हिन्दी" has.
 */

export const LANGUAGES = [
  { code: 'en', endonym: 'English', english: 'English', dir: 'ltr', locale: 'en-US' },
  { code: 'hi', endonym: 'हिन्दी', english: 'Hindi', dir: 'ltr', locale: 'hi-IN' },
  { code: 'es', endonym: 'Español', english: 'Spanish', dir: 'ltr', locale: 'es-ES' },
  { code: 'fr', endonym: 'Français', english: 'French', dir: 'ltr', locale: 'fr-FR' },
  { code: 'de', endonym: 'Deutsch', english: 'German', dir: 'ltr', locale: 'de-DE' },
  { code: 'pt', endonym: 'Português', english: 'Portuguese', dir: 'ltr', locale: 'pt-BR' },
  { code: 'ja', endonym: '日本語', english: 'Japanese', dir: 'ltr', locale: 'ja-JP' },
  { code: 'zh', endonym: '中文', english: 'Chinese (Simplified)', dir: 'ltr', locale: 'zh-CN' },
  { code: 'ar', endonym: 'العربية', english: 'Arabic', dir: 'rtl', locale: 'ar-SA' },
] as const;

export type Language = (typeof LANGUAGES)[number]['code'];

export type LanguageMeta = (typeof LANGUAGES)[number];

export const DEFAULT_LANGUAGE: Language = 'en';

const BY_CODE = new Map<string, LanguageMeta>(LANGUAGES.map((entry) => [entry.code, entry]));

export function isLanguage(value: string | null | undefined): value is Language {
  return !!value && BY_CODE.has(value);
}

export function languageMeta(code: Language): LanguageMeta {
  return BY_CODE.get(code) ?? LANGUAGES[0];
}

/**
 * Best match for what the browser asked for, narrowed to what we actually ship.
 *
 * <p>`navigator.languages` arrives as full tags (`pt-BR`, `zh-Hans-CN`), so the primary subtag is
 * compared rather than the whole string — someone whose browser says `fr-CA` gets French rather
 * than falling through to English on a region mismatch.
 */
export function detectLanguage(candidates: readonly string[] = navigator.languages ?? []): Language {
  for (const candidate of candidates) {
    const primary = candidate.toLowerCase().split('-')[0];
    if (isLanguage(primary)) {
      return primary;
    }
  }
  return DEFAULT_LANGUAGE;
}
