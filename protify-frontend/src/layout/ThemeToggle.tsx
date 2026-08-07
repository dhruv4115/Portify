import { useI18n } from '../i18n/I18nProvider';
import { usePreferences, type ThemeChoice } from '../preferences/PreferencesContext';
import { Icon, type IconName } from '../ui/Icon';
import { Menu, MenuItem } from '../ui/Menu';

const ICONS: Record<ThemeChoice, IconName> = { light: 'sun', dark: 'moon', system: 'monitor' };

/**
 * One tap to flip the theme, with the three-way choice a press-and-hold away.
 *
 * <p>The icon shows what is *currently* on rather than what tapping would switch to — the sun is
 * lit in light mode. Both conventions exist; showing the current state is the one that matches
 * every other indicator in an interface.
 */
export function ThemeToggle() {
  const { resolvedTheme, toggleTheme } = usePreferences();
  const { t } = useI18n();

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={t('theme.toggle')}
      title={`${t('theme.label')}: ${t(resolvedTheme === 'dark' ? 'theme.dark' : 'theme.light')}`}
      className="btn-icon relative overflow-hidden"
    >
      {/* Both glyphs are mounted; the swap is a rotate-and-fade rather than a swap of nodes, so
          the transition can actually be seen. */}
      <span
        className="absolute transition-all duration-300 ease-[var(--ease-spring)]"
        style={{
          opacity: resolvedTheme === 'dark' ? 0 : 1,
          transform: resolvedTheme === 'dark' ? 'rotate(-90deg) scale(0.4)' : 'rotate(0) scale(1)',
        }}
      >
        <Icon name="sun" size={17} />
      </span>
      <span
        className="absolute transition-all duration-300 ease-[var(--ease-spring)]"
        style={{
          opacity: resolvedTheme === 'dark' ? 1 : 0,
          transform: resolvedTheme === 'dark' ? 'rotate(0) scale(1)' : 'rotate(90deg) scale(0.4)',
        }}
      >
        <Icon name="moon" size={17} />
      </span>
    </button>
  );
}

/** The explicit three-way picker — light, dark, or follow the device. Used in menus and settings. */
export function ThemeChoiceMenu() {
  const { theme, setTheme } = usePreferences();
  const { t } = useI18n();

  const options: { value: ThemeChoice; label: string; description?: string }[] = [
    { value: 'light', label: t('theme.light') },
    { value: 'dark', label: t('theme.dark') },
    { value: 'system', label: t('theme.system'), description: t('theme.systemHint') },
  ];

  return (
    <Menu
      label={t('theme.label')}
      trigger={(props) => (
        <button type="button" {...props} className="btn btn-ghost h-9 gap-1.5 px-2">
          <Icon name={ICONS[theme]} size={17} />
          <Icon name="chevronDown" size={13} className="text-ink-3" />
        </button>
      )}
    >
      {(close) => (
        <>
          {options.map((option) => (
            <MenuItem
              key={option.value}
              checked={theme === option.value}
              description={option.description}
              icon={<Icon name={ICONS[option.value]} size={15} />}
              onSelect={() => {
                setTheme(option.value);
                close();
              }}
              trailing={theme === option.value ? <Icon name="check" size={14} className="text-brand" /> : null}
            >
              {option.label}
            </MenuItem>
          ))}
        </>
      )}
    </Menu>
  );
}
