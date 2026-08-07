import { useI18n } from '../i18n/I18nProvider';
import { LANGUAGES, type Language } from '../i18n/languages';
import { Icon } from '../ui/Icon';
import { Menu, MenuItem } from '../ui/Menu';
import { cx } from '../ui/cx';

/**
 * The language control.
 *
 * <p>Placed in the header rather than buried in settings, and always showing the current language
 * code beside a globe. Someone who cannot read the interface cannot find a control labelled with
 * a word in a language they do not speak — so the affordance is an icon plus a code (`EN`, `हि`,
 * `عر`), both of which survive not being able to read anything else on the page.
 *
 * <p>Each option is written in its own language, and the menu is a radio group so assistive tech
 * announces which one is currently active.
 */
export function LanguageSwitcher({ compact = false }: { compact?: boolean }) {
  const { language, meta, setLanguage, t } = useI18n();

  return (
    <Menu
      label={t('language.label')}
      trigger={(props) => (
        <button
          type="button"
          {...props}
          aria-label={t('language.current', { name: meta.english })}
          title={t('language.change')}
          className={cx(
            'btn btn-ghost gap-1.5 px-2',
            compact ? 'h-9' : 'h-9 sm:px-2.5',
          )}
        >
          <Icon name="globe" size={17} />
          <span className="text-[0.75rem] font-semibold uppercase tracking-wide">{meta.code}</span>
          <Icon name="chevronDown" size={13} className="text-ink-3" />
        </button>
      )}
    >
      {(close) => (
        <>
          <p className="px-2.5 pb-1.5 pt-1 text-[0.6875rem] font-semibold uppercase tracking-[0.07em] text-ink-3">
            {t('language.label')}
          </p>
          <div className="max-h-[19rem] overflow-y-auto">
            {LANGUAGES.map((entry) => (
              <MenuItem
                key={entry.code}
                checked={entry.code === language}
                description={entry.english === entry.endonym ? undefined : entry.english}
                onSelect={() => {
                  setLanguage(entry.code as Language);
                  close();
                }}
                trailing={
                  entry.code === language ? <Icon name="check" size={14} className="text-brand" /> : null
                }
              >
                {/* See the note in Settings: `lang` without `dir`, so a right-to-left endonym
                    still renders correctly but stays aligned with the rest of the menu. */}
                <span lang={entry.code}>{entry.endonym}</span>
              </MenuItem>
            ))}
          </div>
          <p className="mt-1 border-t border-hairline-subtle px-2.5 pb-1 pt-2 text-[0.6875rem] leading-snug text-ink-3">
            {t('language.sourceNote')}
          </p>
        </>
      )}
    </Menu>
  );
}
